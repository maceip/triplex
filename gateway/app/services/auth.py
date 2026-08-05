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
        existing = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.device_token == device_token
            )
        )
        if existing.scalar_one_or_none():
            device = existing.scalar_one()
            device.sip_endpoint = sip_endpoint
            device.push_token = push_token
            device.last_heartbeat = datetime.now(timezone.utc)
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

    async def set_device_ready(self, device_token: str, ready: bool) -> bool:
        result = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.device_token == device_token
            )
        )
        device = result.scalar_one_or_none()
        if not device:
            return False
        
        device.ready = ready
        device.last_heartbeat = datetime.now(timezone.utc)
        await self.db.commit()
        return True
