"""Entitlement verification and per-user Plivo line allocation.

One permanent DID + one SIP Endpoint per entitled user. Shared-DID pooling is
not used: call-forward webhooks only identify the Plivo DID, not the original
SIM number.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Literal, Optional
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..db.models import EntitlementDB, SipCredentialDB, VirtualNumberDB
from .plivo_provisioning import (
    PlivoProvisioningClient,
    PlivoProvisioningError,
    normalize_plivo_number,
)

logger = logging.getLogger("triplex.gateway.line")


class EntitlementError(ValueError):
    """User-facing reason a claim or allocation was refused."""


@dataclass(frozen=True)
class SipCredentialsView:
    provider: str
    username: str
    password: str
    domain: str
    realm: Optional[str]


@dataclass(frozen=True)
class LineAllocation:
    did: str
    sip: SipCredentialsView
    status: Literal["allocated", "existing"]
    entitlement_source: Literal["stub", "play"]
    product_id: str


class LineAllocationService:
    def __init__(
        self,
        db: AsyncSession,
        plivo: PlivoProvisioningClient,
        *,
        product_id: Optional[str] = None,
        stub_mode: Optional[bool] = None,
        stub_secret: Optional[str] = None,
    ):
        self.db = db
        self.plivo = plivo
        self.product_id = product_id or settings.entitlement_product_id
        self.stub_mode = (
            settings.entitlement_stub_mode if stub_mode is None else stub_mode
        )
        secret = stub_secret
        if secret is None and settings.entitlement_stub_secret is not None:
            secret = settings.entitlement_stub_secret.get_secret_value()
        self.stub_secret = secret

    async def get_active_entitlement(self, user_id: UUID) -> Optional[EntitlementDB]:
        result = await self.db.execute(
            select(EntitlementDB)
            .where(
                EntitlementDB.user_id == user_id,
                EntitlementDB.product_id == self.product_id,
                EntitlementDB.status == "active",
            )
            .order_by(EntitlementDB.created_at.desc())
            .limit(1)
        )
        return result.scalar_one_or_none()

    async def get_assigned_number(self, user_id: UUID) -> Optional[VirtualNumberDB]:
        result = await self.db.execute(
            select(VirtualNumberDB)
            .where(
                VirtualNumberDB.assigned_user_id == user_id,
                VirtualNumberDB.provider == "plivo",
                VirtualNumberDB.inbound_enabled.is_(True),
            )
            .order_by(VirtualNumberDB.created_at.asc())
            .limit(1)
        )
        return result.scalar_one_or_none()

    async def get_sip_credentials(self, user_id: UUID) -> Optional[SipCredentialDB]:
        result = await self.db.execute(
            select(SipCredentialDB).where(SipCredentialDB.user_id == user_id)
        )
        return result.scalar_one_or_none()

    async def get_line(self, user_id: UUID) -> Optional[LineAllocation]:
        entitlement = await self.get_active_entitlement(user_id)
        number = await self.get_assigned_number(user_id)
        credential = await self.get_sip_credentials(user_id)
        if entitlement is None or number is None or credential is None:
            return None
        return LineAllocation(
            did=normalize_plivo_number(number.number),
            sip=SipCredentialsView(
                provider=credential.provider,
                username=credential.username,
                password=credential.password,
                domain=credential.domain,
                realm=credential.realm,
            ),
            status="existing",
            entitlement_source=entitlement.source,  # type: ignore[arg-type]
            product_id=entitlement.product_id,
        )

    def verify_claim(
        self,
        *,
        product_id: str,
        purchase_token: Optional[str],
        stub_unlock: Optional[str],
    ) -> Literal["stub", "play"]:
        if product_id != self.product_id:
            raise EntitlementError(f"unsupported product_id: {product_id}")

        if self.stub_mode:
            if stub_unlock is not None and self.stub_secret is not None:
                if stub_unlock == self.stub_secret:
                    return "stub"
            if purchase_token and purchase_token.startswith("stub."):
                return "stub"
            if stub_unlock is not None and self.stub_secret is None:
                # Stub mode with no configured secret still accepts an explicit
                # unlock string so local/dev can claim without Play Console.
                if stub_unlock.strip():
                    return "stub"
            raise EntitlementError(
                "stub entitlement requires stub_unlock or purchase_token starting with stub."
            )

        if not purchase_token or purchase_token.startswith("stub."):
            raise EntitlementError("Play purchase_token required when stub mode is off")
        # Real Google Play Developer API verification lands here once the
        # Console product and service account exist. Fail closed until then.
        raise EntitlementError(
            "Play purchase verification is not configured; enable ENTITLEMENT_STUB_MODE "
            "or wire Google Play Developer API credentials"
        )

    async def claim_and_allocate(
        self,
        user_id: UUID,
        *,
        product_id: str,
        purchase_token: Optional[str] = None,
        stub_unlock: Optional[str] = None,
    ) -> LineAllocation:
        existing = await self.get_line(user_id)
        if existing is not None:
            return existing

        source = self.verify_claim(
            product_id=product_id,
            purchase_token=purchase_token,
            stub_unlock=stub_unlock,
        )

        entitlement = await self.get_active_entitlement(user_id)
        if entitlement is None:
            if purchase_token:
                collision = await self.db.execute(
                    select(EntitlementDB).where(
                        EntitlementDB.purchase_token == purchase_token
                    )
                )
                if collision.scalar_one_or_none() is not None:
                    raise EntitlementError("purchase_token already claimed")
            entitlement = EntitlementDB(
                user_id=user_id,
                product_id=product_id,
                source=source,
                purchase_token=purchase_token,
                status="active",
            )
            self.db.add(entitlement)
            await self.db.flush()

        credential = await self._ensure_sip_credentials(user_id)
        number = await self._ensure_virtual_number(user_id)
        await self.db.commit()
        await self.db.refresh(credential)
        await self.db.refresh(number)
        await self.db.refresh(entitlement)

        return LineAllocation(
            did=normalize_plivo_number(number.number),
            sip=SipCredentialsView(
                provider=credential.provider,
                username=credential.username,
                password=credential.password,
                domain=credential.domain,
                realm=credential.realm,
            ),
            status="allocated",
            entitlement_source=entitlement.source,  # type: ignore[arg-type]
            product_id=entitlement.product_id,
        )

    async def _ensure_sip_credentials(self, user_id: UUID) -> SipCredentialDB:
        existing = await self.get_sip_credentials(user_id)
        if existing is not None:
            return existing
        created = await self.plivo.create_endpoint(user_id)
        credential = SipCredentialDB(
            user_id=user_id,
            provider="plivo",
            username=created.username,
            password=created.password,
            domain=self.plivo.sip_domain,
            realm=self.plivo.sip_domain,
        )
        self.db.add(credential)
        await self.db.flush()
        return credential

    async def _ensure_virtual_number(self, user_id: UUID) -> VirtualNumberDB:
        existing = await self.get_assigned_number(user_id)
        if existing is not None:
            return existing

        claimed = await self._claim_inventory_number(user_id)
        if claimed is not None:
            if self.plivo.application_id:
                try:
                    await self.plivo.attach_number_to_application(claimed.number)
                except PlivoProvisioningError as error:
                    logger.warning(
                        "attach inventory number %s failed (continuing): %s",
                        claimed.number,
                        error,
                    )
            return claimed

        purchased = await self.plivo.buy_and_attach_number()
        row = VirtualNumberDB(
            number=purchased.number,
            number_type="permanent",
            assigned_user_id=user_id,
            provider="plivo",
            inbound_enabled=True,
            outbound_enabled=True,
        )
        self.db.add(row)
        await self.db.flush()
        return row

    async def _claim_inventory_number(self, user_id: UUID) -> Optional[VirtualNumberDB]:
        result = await self.db.execute(
            select(VirtualNumberDB)
            .where(
                VirtualNumberDB.assigned_user_id.is_(None),
                VirtualNumberDB.provider == "plivo",
                VirtualNumberDB.inbound_enabled.is_(True),
                VirtualNumberDB.outbound_enabled.is_(True),
            )
            .order_by(VirtualNumberDB.created_at.asc())
            .limit(1)
            .with_for_update()
        )
        row = result.scalar_one_or_none()
        if row is None:
            return None
        row.assigned_user_id = user_id
        await self.db.flush()
        return row

    async def seed_inventory_numbers(self, numbers: list[str]) -> list[str]:
        """Insert unassigned permanent DIDs for later claim (admin)."""
        seeded: list[str] = []
        for raw in numbers:
            normalized = normalize_plivo_number(raw)
            digits = "".join(c for c in normalized if c.isdigit())
            existing = await self.db.execute(
                select(VirtualNumberDB).where(
                    VirtualNumberDB.number.in_([digits, f"+{digits}", normalized])
                )
            )
            if existing.scalar_one_or_none() is not None:
                continue
            self.db.add(
                VirtualNumberDB(
                    number=normalized,
                    number_type="permanent",
                    assigned_user_id=None,
                    provider="plivo",
                    inbound_enabled=True,
                    outbound_enabled=True,
                )
            )
            seeded.append(normalized)
        await self.db.commit()
        return seeded
