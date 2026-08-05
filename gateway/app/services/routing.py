from html import escape
from typing import Literal, Optional
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models.schemas import FallbackPolicy, InboundPolicy
from ..db.models import DeviceRegistrationDB, VirtualNumberDB


FallbackDecision = Literal[
    "route_to_device",
    "cloud_agent",
    "message_take",
    "transfer",
    "reject"
]


class RoutingService:
    def __init__(self, db: AsyncSession, provider: Literal["plivo", "chime"] = "plivo"):
        self.db = db
        self.provider = provider

    async def get_number_assignment(self, number: str) -> Optional[UUID]:
        result = await self.db.execute(
            select(VirtualNumberDB).where(
                VirtualNumberDB.number == number,
                VirtualNumberDB.inbound_enabled == True,
            )
        )
        vnumber = result.scalar_one_or_none()
        return vnumber.assigned_user_id if vnumber else None

    async def get_device_endpoint(self, user_id: UUID) -> Optional[str]:
        result = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.user_id == user_id,
                DeviceRegistrationDB.ready == True,
            )
        )
        device = result.scalar_one_or_none()
        return device.sip_endpoint if device else None

    async def generate_routing_xml(
        self,
        called_number: str,
        caller_id: Optional[str] = None,
    ) -> tuple[str, FallbackDecision]:
        user_id = await self.get_number_assignment(called_number)
        
        if not user_id:
            return self._generate_reject_xml(), "reject"
        
        sip_endpoint = await self.get_device_endpoint(user_id)
        
        if sip_endpoint:
            return self._generate_dial_xml(sip_endpoint, caller_id), "route_to_device"
        
        return self._generate_reject_xml(), "reject"

    def _generate_dial_xml(self, sip_endpoint: str, caller_id: Optional[str] = None) -> str:
        # Caller id and endpoint reach us from the provider webhook, so both
        # are escaped before they enter the XML we hand back.
        caller_attr = (
            f' callerId="{escape(caller_id, quote=True)}"' if caller_id else ""
        )
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial{caller_attr}>
        <Sip>{escape(sip_endpoint)}</Sip>
    </Dial>
</Response>'''

    def generate_unavailable_xml(self, message: str) -> str:
        """Answer honestly when no device can take the call.

        The plan requires a truthful unavailable response rather than a
        silent drop or a pretend connection.
        """
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Speak language="en-US" voice="Polly.Joanna">{escape(message)}</Speak>
    <Hangup/>
</Response>'''

    def _generate_reject_xml(self) -> str:
        return '''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Reject reason="busy"/>
</Response>'''

    def _generate_fallback_cloud_agent_xml(self, fallback_endpoint: str) -> str:
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial>
        <Sip>{fallback_endpoint}</Sip>
    </Dial>
</Response>'''

    async def apply_fallback_policy(
        self,
        policy: FallbackPolicy,
        transfer_number: Optional[str] = None,
    ) -> tuple[str, str]:
        if policy == "reject":
            return self._generate_reject_xml(), "reject"
        elif policy == "transfer" and transfer_number:
            return self._generate_transfer_xml(transfer_number), "transfer"
        elif policy == "cloud_agent":
            return self._generate_reject_xml(), "cloud_agent_not_implemented"
        else:
            return self._generate_reject_xml(), "reject"

    def _generate_transfer_xml(self, number: str) -> str:
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial>{number}</Dial>
</Response>'''
