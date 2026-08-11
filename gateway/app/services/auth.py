import secrets
from datetime import datetime, timedelta, timezone
from typing import Optional
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db.models import DeviceRegistrationDB, UserAccountDB


class AuthService:
    TOKEN_EXPIRY_HOURS = 24 * 7

    def __init__(self, db: AsyncSession):
        self.db = db

    async def create_user(self, email: str, phone_number: str) -> UserAccountDB:
        user = UserAccountDB(email=email, phone_number=phone_number)
        self.db.add(user)
        await self.db.commit()
        await self.db.refresh(user)
        return user

    async def get_user(self, user_id: UUID) -> Optional[UserAccountDB]:
        result = await self.db.execute(
            select(UserAccountDB).where(UserAccountDB.id == user_id)
        )
        return result.scalar_one_or_none()

    async def get_user_by_email(self, email: str) -> Optional[UserAccountDB]:
        result = await self.db.execute(
            select(UserAccountDB).where(UserAccountDB.email == email)
        )
        return result.scalar_one_or_none()

    async def generate_device_token(self, user_id: UUID) -> str:
        token = f"device_{secrets.token_urlsafe(32)}"
        return token

    async def register_device(
        self,
        user_id: UUID,
        device_token: str,
        sip_endpoint: str,
        push_token: Optional[str] = None,
    ) -> DeviceRegistrationDB:
        """Attach a SIP endpoint to the device that presented ``device_token``.

        The token identifies the phone; registration says where to reach it.
        Keeping those two facts on one row is the whole point, and they were
        drifting apart: ``/devices/register`` used to mint a *new* token and
        insert a *new* row, while the app went on presenting the token it got
        at enrollment. Readiness therefore landed on the enrollment row — the
        one whose ``sip_endpoint`` is the empty string it was created with — and
        routing would happily hand a caller an endpoint of ``""``. Every app
        launch added another orphan row on top.

        So: look the device up by the token the caller actually holds, and
        update it in place.
        """
        existing = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.device_token == device_token,
                DeviceRegistrationDB.user_id == user_id,
            )
        )
        device = existing.scalar_one_or_none()
        if device is not None:
            device.sip_endpoint = sip_endpoint
            device.push_token = push_token
            device.last_heartbeat = datetime.now(timezone.utc)
            # A device that has just registered an endpoint has not yet said it
            # can carry media, and inheriting the previous session's claim would
            # bridge a caller into silence.
            device.ready = False
            device.media_ready = False
        else:
            device = DeviceRegistrationDB(
                user_id=user_id,
                device_token=device_token,
                sip_endpoint=sip_endpoint,
                push_token=push_token,
            )
            self.db.add(device)

        await self.db.commit()
        await self.db.refresh(device)
        return device

    async def validate_device_token(self, device_token: str) -> Optional[UUID]:
        result = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.device_token == device_token
            )
        )
        device = result.scalar_one_or_none()
        return device.user_id if device else None

    async def set_device_ready(
        self, device_token: str, ready: bool, media_ready: bool = False
    ) -> bool:
        """Record what the device says it can do right now.

        ``media_ready`` is subordinate to ``ready`` on purpose: a device that
        is not registered cannot be carrying audio, whatever it claims, and
        letting the two disagree would put an inbound caller on a bridge to an
        endpoint that is not there.
        """
        result = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.device_token == device_token
            )
        )
        device = result.scalar_one_or_none()
        if not device:
            return False

        device.ready = ready
        device.media_ready = ready and media_ready
        device.last_heartbeat = datetime.now(timezone.utc)
        await self.db.commit()
        return True
