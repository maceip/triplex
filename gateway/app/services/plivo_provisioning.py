"""Plivo Number + Endpoint provisioning against the REST API.

The gateway owns DIDs and SIP endpoint credentials (placement rules). Devices
only ever receive credentials after entitlement; they REGISTER to Plivo Direct
themselves. This module talks to Plivo over HTTPS — it is not a SIP edge.
"""

from __future__ import annotations

import logging
import secrets
import string
from dataclasses import dataclass
from typing import Any, Optional, Protocol
from uuid import UUID

import httpx

logger = logging.getLogger("triplex.gateway.plivo")

_PLIVO_API = "https://api.plivo.com/v1/Account"


class PlivoProvisioningError(RuntimeError):
    """Provider refused or misconfigured a provisioning step."""


@dataclass(frozen=True)
class CreatedEndpoint:
    endpoint_id: str
    username: str
    password: str
    alias: str


@dataclass(frozen=True)
class PurchasedNumber:
    number: str


class PlivoHttpClient(Protocol):
    async def request(
        self,
        method: str,
        path: str,
        *,
        json: Optional[dict[str, Any]] = None,
        params: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]: ...


class HttpxPlivoClient:
    """Thin Basic-auth JSON client for Plivo Account APIs."""

    def __init__(
        self,
        auth_id: str,
        auth_token: str,
        *,
        http: Optional[httpx.AsyncClient] = None,
    ):
        self._auth_id = auth_id
        self._auth = (auth_id, auth_token)
        self._http = http
        self._owns_http = http is None

    @property
    def auth_id(self) -> str:
        return self._auth_id

    async def request(
        self,
        method: str,
        path: str,
        *,
        json: Optional[dict[str, Any]] = None,
        params: Optional[dict[str, Any]] = None,
    ) -> dict[str, Any]:
        url = f"{_PLIVO_API}/{self._auth_id}{path}"
        client = self._http
        close_after = False
        if client is None:
            client = httpx.AsyncClient(timeout=30.0)
            close_after = True
        try:
            response = await client.request(
                method,
                url,
                auth=self._auth,
                json=json,
                params=params,
                headers={"Content-Type": "application/json"},
            )
        finally:
            if close_after:
                await client.aclose()

        if response.status_code >= 400:
            detail = response.text[:500]
            raise PlivoProvisioningError(
                f"Plivo {method} {path} failed ({response.status_code}): {detail}"
            )
        if response.status_code == 204 or not response.content:
            return {}
        payload = response.json()
        if not isinstance(payload, dict):
            raise PlivoProvisioningError(f"Plivo {method} {path} returned non-object JSON")
        return payload


def endpoint_username_for_user(user_id: UUID) -> str:
    """Short alphanumeric prefix; Plivo appends a 12-digit suffix on create.

    Must start with a letter and stay well under the 25-char username cap
    after Plivo's append.
    """
    return f"tx{user_id.hex[:8]}"


def generate_endpoint_password() -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(24))


def normalize_plivo_number(number: str) -> str:
    digits = "".join(character for character in number if character.isdigit())
    if not digits:
        raise PlivoProvisioningError(f"empty Plivo number: {number!r}")
    return f"+{digits}"


class PlivoProvisioningClient:
    """Create Endpoints and buy/attach Numbers for entitled users."""

    def __init__(
        self,
        http: PlivoHttpClient,
        *,
        application_id: Optional[str],
        number_country: str = "US",
        number_type: str = "local",
        sip_domain: str = "phone.plivo.com",
    ):
        self._http = http
        self._application_id = application_id
        self._number_country = number_country.upper()
        self._number_type = number_type
        self.sip_domain = sip_domain

    @property
    def application_id(self) -> Optional[str]:
        return self._application_id

    def require_application_id(self) -> str:
        if not self._application_id:
            raise PlivoProvisioningError(
                "PLIVO_APPLICATION_ID is required to buy or attach numbers"
            )
        return self._application_id

    async def create_endpoint(self, user_id: UUID) -> CreatedEndpoint:
        username = endpoint_username_for_user(user_id)
        password = generate_endpoint_password()
        alias = f"triplex-{user_id.hex[:12]}"
        body: dict[str, Any] = {
            "username": username,
            "password": password,
            "alias": alias,
        }
        app_id = self._application_id
        if app_id:
            body["app_id"] = app_id

        payload = await self._http.request("POST", "/Endpoint/", json=body)
        created_username = str(payload.get("username") or username)
        endpoint_id = str(payload.get("endpoint_id") or "")
        if not endpoint_id:
            raise PlivoProvisioningError(
                f"Plivo Endpoint create missing endpoint_id: {payload!r}"
            )
        logger.info(
            "created Plivo endpoint endpoint_id=%s username=%s user_id=%s",
            endpoint_id,
            created_username,
            user_id,
        )
        return CreatedEndpoint(
            endpoint_id=endpoint_id,
            username=created_username,
            password=password,
            alias=alias,
        )

    async def search_numbers(self, *, limit: int = 5) -> list[str]:
        params = {
            "country_iso": self._number_country,
            "type": self._number_type,
            "services": "voice",
            "limit": str(limit),
        }
        payload = await self._http.request("GET", "/PhoneNumber/", params=params)
        objects = payload.get("objects") or []
        numbers: list[str] = []
        for item in objects:
            if not isinstance(item, dict):
                continue
            raw = item.get("number")
            if isinstance(raw, str) and raw:
                numbers.append(raw)
        return numbers

    async def buy_number(self, number: str) -> PurchasedNumber:
        app_id = self.require_application_id()
        digits = "".join(character for character in number if character.isdigit())
        payload = await self._http.request(
            "POST",
            f"/PhoneNumber/{digits}/",
            json={"app_id": app_id},
        )
        status = str(payload.get("status") or "").lower()
        if status and status not in {"fulfilled", "success"}:
            raise PlivoProvisioningError(f"Plivo buy number failed: {payload!r}")
        logger.info("bought Plivo number=%s app_id=%s", digits, app_id)
        return PurchasedNumber(number=normalize_plivo_number(digits))

    async def attach_number_to_application(self, number: str) -> None:
        app_id = self.require_application_id()
        digits = "".join(character for character in number if character.isdigit())
        await self._http.request(
            "POST",
            f"/Number/{digits}/",
            json={"app_id": app_id, "alias": f"triplex-{digits[-4:]}"},
        )
        logger.info("attached Plivo number=%s to app_id=%s", digits, app_id)

    async def buy_and_attach_number(self) -> PurchasedNumber:
        candidates = await self.search_numbers(limit=5)
        if not candidates:
            raise PlivoProvisioningError(
                f"no Plivo voice numbers available in {self._number_country}"
            )
        last_error: Optional[Exception] = None
        for candidate in candidates:
            try:
                purchased = await self.buy_number(candidate)
                # Buy already passed app_id; attach is idempotent insurance for
                # inventory that was bought without an application.
                await self.attach_number_to_application(purchased.number)
                return purchased
            except PlivoProvisioningError as error:
                last_error = error
                logger.warning("buy candidate %s failed: %s", candidate, error)
        raise PlivoProvisioningError(
            f"failed to buy any Plivo number: {last_error}"
        )
