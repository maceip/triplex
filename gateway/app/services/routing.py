from html import escape
from typing import Literal, Optional
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..db.models import DeviceRegistrationDB, VirtualNumberDB
from ..models.schemas import FallbackPolicy

FallbackDecision = Literal[
    "route_to_device", "cloud_agent", "message_take", "transfer", "reject"
]


class RoutingService:
    def __init__(self, db: AsyncSession, provider: Literal["plivo", "chime"] = "plivo"):
        self.db = db
        self.provider = provider

    async def get_number_assignment(self, number: str) -> Optional[UUID]:
        result = await self.db.execute(
            select(VirtualNumberDB).where(
                VirtualNumberDB.number == number,
                VirtualNumberDB.inbound_enabled.is_(True),
            )
        )
        vnumber = result.scalar_one_or_none()
        return vnumber.assigned_user_id if vnumber else None

    async def get_device_endpoint(self, user_id: UUID) -> Optional[str]:
        result = await self.db.execute(
            select(DeviceRegistrationDB).where(
                DeviceRegistrationDB.user_id == user_id,
                DeviceRegistrationDB.ready.is_(True),
            )
            .order_by(DeviceRegistrationDB.last_heartbeat.desc())
            .limit(1)
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

    def _generate_dial_xml(
        self, sip_endpoint: str, caller_id: Optional[str] = None
    ) -> str:
        # Caller id and endpoint reach us from the provider webhook, so both
        # are escaped before they enter the XML we hand back.
        caller_attr = (
            f' callerId="{escape(caller_id, quote=True)}"' if caller_id else ""
        )
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial{caller_attr}>
        <User>{escape(sip_endpoint)}</User>
    </Dial>
</Response>"""

    def generate_unavailable_xml(self, message: str) -> str:
        """Answer honestly when no device can take the call.

        The plan requires a truthful unavailable response rather than a
        silent drop or a pretend connection.
        """
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Speak language="en-US" voice="Polly.Joanna">{escape(message)}</Speak>
    <Hangup/>
</Response>"""

    def generate_outbound_dial_xml(self, number: str, caller_id: str) -> str:
        """Connect an already-authorized Android SIP leg to one PSTN number."""
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial callerId="{escape(caller_id, quote=True)}">
        <Number>{escape(number)}</Number>
    </Dial>
</Response>'''

    def generate_screening_prompt_xml(self, call_uuid: str, public_base_url: str) -> str:
        base = public_base_url.rstrip("/")
        result_url = escape(f"{base}/screening/{call_uuid}/result", quote=True)
        interim_url = escape(f"{base}/screening/{call_uuid}/interim", quote=True)
        hold_url = escape(f"{base}/screening/{call_uuid}/hold")
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <GetInput action="{result_url}" method="POST" inputType="speech" language="en-US" speechModel="phone_call" executionTimeout="20" speechEndTimeout="2" interimSpeechResultsCallback="{interim_url}" interimSpeechResultsCallbackMethod="POST">
        <Speak language="en-US" voice="Polly.Joanna">Hi. This is the Triplex screening assistant. Please say your name and why you are calling.</Speak>
    </GetInput>
    <Redirect method="POST">{hold_url}</Redirect>
</Response>'''

    def generate_screening_hold_xml(self, call_uuid: str, public_base_url: str) -> str:
        hold_url = escape(
            f"{public_base_url.rstrip('/')}/screening/{call_uuid}/hold"
        )
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Speak language="en-US" voice="Polly.Joanna">Thank you. Please hold while I check whether they are available.</Speak>
    <Wait length="20"/>
    <Redirect method="POST">{hold_url}</Redirect>
</Response>'''

    def generate_screening_connect_xml(
        self, sip_endpoint: str, caller_id: Optional[str]
    ) -> str:
        return self._generate_dial_xml(sip_endpoint, caller_id)

    def generate_screening_decline_xml(self) -> str:
        return '''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Speak language="en-US" voice="Polly.Joanna">They are not available to take this call. Goodbye.</Speak>
    <Hangup/>
</Response>'''

    def generate_screening_agent_xml(self, call_uuid: str, public_base_url: str) -> str:
        return self.generate_screening_automation_xml(
            call_uuid, public_base_url, "book_zoom"
        )

    def generate_screening_automation_xml(
        self, call_uuid: str, public_base_url: str, automation_id: str
    ) -> str:
        message_url = escape(
            f"{public_base_url.rstrip('/')}/screening/{call_uuid}/message",
            quote=True,
        )
        interim_url = escape(
            f"{public_base_url.rstrip('/')}/screening/{call_uuid}/message-interim",
            quote=True,
        )
        prompt = {
            "book_zoom": (
                "I can help arrange a Zoom call. Please tell me a day and time, "
                "your time zone, and the best email address for the invitation."
            ),
            "explain_delay": (
                "They are running late and asked me to let you know. "
                "Is there anything you would like me to pass along?"
            ),
        }.get(automation_id)
        if prompt is None:
            raise ValueError("Unsupported screening automation")
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <GetInput action="{message_url}" method="POST" inputType="speech" language="en-US" speechModel="phone_call" executionTimeout="30" speechEndTimeout="3" interimSpeechResultsCallback="{interim_url}" interimSpeechResultsCallbackMethod="POST">
        <Speak language="en-US" voice="Polly.Joanna">{escape(prompt)}</Speak>
    </GetInput>
    <Speak language="en-US" voice="Polly.Joanna">Thank you. I have the meeting details and will send them for confirmation. Goodbye.</Speak>
    <Hangup/>
</Response>'''

    def _generate_reject_xml(self) -> str:
        return """<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Reject reason="busy"/>
</Response>"""

    def _generate_fallback_cloud_agent_xml(self, fallback_endpoint: str) -> str:
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial>
        <User>{escape(fallback_endpoint)}</User>
    </Dial>
</Response>"""

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
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial>{number}</Dial>
</Response>"""
