"""Short-lived authorization for direct Android-to-Plivo SIP calls.

The gateway never originates the call and never carries media. It authorizes
one typed task, then validates the resulting Plivo application callback before
returning the provider XML that connects the Android SIP leg to PSTN.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Iterable
from uuid import UUID, uuid4

import jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db.models import (
    DeviceRegistrationDB,
    OutboundRouteGrantDB,
    SipCredentialDB,
    TaskDefinitionDB,
    VirtualNumberDB,
)

_E164 = re.compile(r"^\+?[1-9][0-9]{7,14}$")
_SIP_USER = re.compile(r"sips?:([^@;>\s]+)@", re.IGNORECASE)
_WIRE_TOKEN = re.compile(r"^[0-9a-f]{64,4094}$")
_ISSUER = "triplex-gateway"
_AUDIENCE = "triplex-plivo-outbound"
_ALLOWED_TASK_TYPES = frozenset(
    {"item_return", "appointment_modify", "reservation_update"}
)


class RouteGrantError(ValueError):
    """A safe, user-facing reason an outbound route was refused."""


@dataclass(frozen=True)
class IssuedRouteGrant:
    task_id: UUID
    sip_uri: str
    header_name: str
    token: str
    expires_at: datetime
    #: The normalized destination, for the audit record. Held separately from
    #: :attr:`sip_uri` so an audit entry never has to be parsed back out of a
    #: URI, and never accidentally carries the grant token with it.
    destination_number_for_audit: str


@dataclass(frozen=True)
class AuthorizedOutboundCall:
    task_id: UUID
    user_id: UUID
    destination_number: str
    caller_id: str
    provider_call_uuid: str


def normalize_e164(raw: str) -> str:
    """Return canonical `+` E.164 and reject extensions or URI injection."""

    candidate = (
        raw.strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
    )
    if not _E164.fullmatch(candidate):
        raise RouteGrantError("Destination must be a valid E.164 phone number")
    digits = candidate.removeprefix("+")
    return f"+{digits}"


def destination_from_plivo(raw: str) -> str:
    """Extract the dialed user from Plivo's number or SIP-URI callback value."""

    match = _SIP_USER.search(raw)
    return normalize_e164(match.group(1) if match else raw)


def endpoint_identity_matches(
    expected_username: str, candidates: Iterable[str]
) -> bool:
    """Match an authenticated endpoint across Plivo's From/SIP-H-From forms."""

    for raw in candidates:
        value = raw.strip()
        match = _SIP_USER.search(value)
        user = match.group(1) if match else value.split("@", maxsplit=1)[0]
        if user.strip("<>") == expected_username:
            return True
    return False


def build_plivo_sip_uri(
    destination_number: str,
    provisioned_domain: str,
    allowed_domain: str,
) -> str:
    destination = normalize_e164(destination_number).removeprefix("+")
    normalized_domain = provisioned_domain.lower().rstrip(".")
    if normalized_domain != allowed_domain.lower().rstrip("."):
        raise RouteGrantError("The provisioned SIP provider domain is unsupported")
    # Plivo Direct endpoints register over UDP. Outbound INVITEs must match that
    # transport — sips:5061 is rejected with 488 before the answer URL runs.
    return f"sip:{destination}@{normalized_domain};transport=udp"


class OutboundRouteService:
    # Plivo permits only alphanumeric characters after its X-PH- prefix.
    HEADER_NAME = "X-PH-TriplexGrant"

    def __init__(
        self,
        db: AsyncSession,
        signing_key: str,
        ttl_seconds: int,
        sip_domain: str,
        device_heartbeat_ttl_seconds: int = 300,
    ):
        if len(signing_key.encode("utf-8")) < 32:
            raise RouteGrantError("Outbound route authorization is not configured")
        if ttl_seconds not in range(30, 301):
            raise RouteGrantError(
                "Outbound route TTL must be between 30 and 300 seconds"
            )
        self.db = db
        self.signing_key = signing_key
        self.ttl_seconds = ttl_seconds
        self.sip_domain = sip_domain
        self.device_heartbeat_ttl_seconds = device_heartbeat_ttl_seconds

    async def issue(
        self,
        user_id: UUID,
        task_id: UUID,
        requested_destination: str,
    ) -> IssuedRouteGrant:
        now = datetime.now(timezone.utc)
        destination = normalize_e164(requested_destination)

        task_result = await self.db.execute(
            select(TaskDefinitionDB)
            .where(
                TaskDefinitionDB.id == task_id,
                TaskDefinitionDB.user_id == user_id,
            )
            .with_for_update()
        )
        task = task_result.scalar_one_or_none()
        if task is None:
            raise RouteGrantError("Task not found")
        if task.task_type.lower() not in _ALLOWED_TASK_TYPES:
            raise RouteGrantError("Task type is not permitted for outbound calling")
        if normalize_e164(task.destination_number) != destination:
            raise RouteGrantError("Requested destination does not match the task")
        if task.status not in {"pending", "active"}:
            raise RouteGrantError("Task cannot place a call in its current state")

        credential_result = await self.db.execute(
            select(SipCredentialDB).where(
                SipCredentialDB.user_id == user_id,
                SipCredentialDB.provider == "plivo",
            )
        )
        credential = credential_result.scalar_one_or_none()
        if credential is None:
            raise RouteGrantError("No Plivo SIP endpoint is provisioned")

        # A grant authorizes a call the *device* will place, so the device has
        # to be there. `ready` alone does not establish that: it is a flag the
        # phone last wrote, and a phone that has been off since Tuesday leaves
        # it set. Requiring a recent heartbeat is what makes this check about
        # the present rather than about history.
        heartbeat_floor = now - timedelta(seconds=self.device_heartbeat_ttl_seconds)
        ready_result = await self.db.execute(
            select(DeviceRegistrationDB.id)
            .where(
                DeviceRegistrationDB.user_id == user_id,
                DeviceRegistrationDB.ready.is_(True),
                DeviceRegistrationDB.last_heartbeat >= heartbeat_floor,
                DeviceRegistrationDB.sip_endpoint != "",
            )
            .limit(1)
        )
        if ready_result.scalar_one_or_none() is None:
            raise RouteGrantError("The Android agent is not ready")

        number_result = await self.db.execute(
            select(VirtualNumberDB)
            .where(
                VirtualNumberDB.assigned_user_id == user_id,
                VirtualNumberDB.provider == "plivo",
                VirtualNumberDB.outbound_enabled.is_(True),
            )
            .order_by(VirtualNumberDB.created_at.asc())
            .limit(1)
        )
        number = number_result.scalar_one_or_none()
        if number is None:
            raise RouteGrantError("No outbound caller ID is assigned")

        sip_uri = build_plivo_sip_uri(
            destination,
            credential.domain,
            self.sip_domain,
        )
        caller_id = normalize_e164(number.number)
        expires_at = now + timedelta(seconds=self.ttl_seconds)

        grant_result = await self.db.execute(
            select(OutboundRouteGrantDB)
            .where(OutboundRouteGrantDB.task_id == task_id)
            .with_for_update()
        )
        grant = grant_result.scalar_one_or_none()
        if grant is not None and grant.consumed_call_uuid is not None:
            raise RouteGrantError("This task has already used its outbound call grant")
        if grant is None:
            grant = OutboundRouteGrantDB(task_id=task_id, user_id=user_id)
            self.db.add(grant)

        grant.token_id = uuid4()
        grant.destination_number = destination
        grant.caller_id = caller_id
        grant.endpoint_username = credential.username
        grant.sip_domain = credential.domain.lower().rstrip(".")
        grant.expires_at = expires_at
        grant.created_at = now

        if task.status == "pending":
            task.status = "active"
            task.started_at = now

        await self.db.commit()

        jwt_token = jwt.encode(
            {
                "iss": _ISSUER,
                "aud": _AUDIENCE,
                "sub": str(user_id),
                "jti": str(grant.token_id),
                "task_id": str(task_id),
                "destination": destination,
                "endpoint": credential.username,
                "iat": now,
                "exp": expires_at,
            },
            self.signing_key,
            algorithm="HS256",
        )
        # JWT compact serialization contains punctuation that Plivo drops from
        # custom SIP-header values. Hex is deterministic, strictly
        # alphanumeric, and remains well below the bounded header limit.
        token = jwt_token.encode("ascii").hex()
        return IssuedRouteGrant(
            task_id=task_id,
            sip_uri=sip_uri,
            header_name=self.HEADER_NAME,
            token=token,
            expires_at=expires_at,
            destination_number_for_audit=destination,
        )

    async def consume(
        self,
        token: str,
        provider_call_uuid: str,
        destination_value: str,
        endpoint_candidates: Iterable[str],
    ) -> AuthorizedOutboundCall:
        if not provider_call_uuid or len(provider_call_uuid) > 64:
            raise RouteGrantError("Plivo did not provide a valid call UUID")
        claims = self._decode(token)
        try:
            token_id = UUID(claims["jti"])
            task_id = UUID(claims["task_id"])
            user_id = UUID(claims["sub"])
            claimed_destination = normalize_e164(claims["destination"])
            claimed_endpoint = str(claims["endpoint"])
        except (KeyError, TypeError, ValueError) as error:
            raise RouteGrantError("Outbound route grant is malformed") from error

        callback_destination = destination_from_plivo(destination_value)
        if callback_destination != claimed_destination:
            raise RouteGrantError("Plivo destination does not match the route grant")
        if not endpoint_identity_matches(claimed_endpoint, endpoint_candidates):
            raise RouteGrantError("Plivo endpoint does not match the route grant")

        grant_result = await self.db.execute(
            select(OutboundRouteGrantDB)
            .where(OutboundRouteGrantDB.token_id == token_id)
            .with_for_update()
        )
        grant = grant_result.scalar_one_or_none()
        if grant is None:
            raise RouteGrantError("Outbound route grant is unknown or superseded")

        now = datetime.now(timezone.utc)
        if grant.expires_at <= now:
            raise RouteGrantError("Outbound route grant has expired")
        if (
            grant.task_id != task_id
            or grant.user_id != user_id
            or grant.destination_number != claimed_destination
            or grant.endpoint_username != claimed_endpoint
        ):
            raise RouteGrantError("Outbound route grant binding is invalid")

        task_result = await self.db.execute(
            select(TaskDefinitionDB).where(
                TaskDefinitionDB.id == task_id,
                TaskDefinitionDB.user_id == user_id,
                TaskDefinitionDB.status == "active",
            )
        )
        if task_result.scalar_one_or_none() is None:
            raise RouteGrantError("The authorized task is no longer active")

        if grant.consumed_call_uuid is None:
            grant.consumed_call_uuid = provider_call_uuid
            grant.consumed_at = now
            await self.db.commit()
        elif grant.consumed_call_uuid != provider_call_uuid:
            raise RouteGrantError("Outbound route grant has already been consumed")

        return AuthorizedOutboundCall(
            task_id=task_id,
            user_id=user_id,
            destination_number=grant.destination_number,
            caller_id=grant.caller_id,
            provider_call_uuid=provider_call_uuid,
        )

    def _decode(self, token: str) -> dict:
        try:
            if not _WIRE_TOKEN.fullmatch(token):
                raise ValueError("invalid Plivo header encoding")
            jwt_token = bytes.fromhex(token).decode("ascii")
            return jwt.decode(
                jwt_token,
                self.signing_key,
                algorithms=["HS256"],
                audience=_AUDIENCE,
                issuer=_ISSUER,
                options={"require": ["exp", "iat", "iss", "aud", "sub", "jti"]},
            )
        except (jwt.PyJWTError, ValueError, UnicodeError) as error:
            raise RouteGrantError(
                "Outbound route grant is invalid or expired"
            ) from error
