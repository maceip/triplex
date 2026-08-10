"""Inbound call routing.

The gateway decides where a call goes; it never carries the media. Everything
here produces provider XML that either points the call at the user's device or
says, out loud, why it cannot.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum
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


class InboundDecision(str, Enum):
    """What the gateway decided to do with an inbound call, and why.

    Returned alongside the XML so the caller logs and audits the decision
    itself rather than re-deriving it from the markup it produced.
    """

    #: The device advertised that it can carry audio. The caller is connected
    #: straight to it and Triplex answers on the phone, in the user's voice.
    BRIDGE_TO_DEVICE = "bridge_to_device"

    #: The device is registered and would answer an INVITE, but has not
    #: advertised that it can carry the caller's audio. The provider screens
    #: the caller instead and the user decides from the phone.
    SCREEN_CALLER = "screen_caller"

    #: The number is ours but no device is reachable. The caller is told so.
    UNAVAILABLE = "unavailable"

    #: The number is not assigned to anyone here. Nothing to say.
    UNASSIGNED = "unassigned"


@dataclass(frozen=True)
class InboundRouting:
    """The whole answer to "where does this call go", in one object."""

    decision: InboundDecision
    #: Who owns the dialled number, or None when nobody does. Present even on
    #: :attr:`InboundDecision.UNAVAILABLE` so the refusal can still be audited
    #: against the account it happened to.
    user_id: Optional[UUID]
    route: Optional["DeviceRoute"]


@dataclass(frozen=True)
class DeviceRoute:
    """A device the gateway is willing to send a call to."""

    sip_endpoint: str
    #: True when the device says it can bridge the caller's audio, not merely
    #: accept the INVITE.
    media_ready: bool
    last_heartbeat: datetime


class RoutingService:
    """Chooses between bridging, screening, and saying so.

    ``heartbeat_ttl_seconds`` bounds how long a device's readiness is believed
    after it last checked in. Readiness is a claim about right now — a phone
    that has been off since Tuesday still has ``ready = true`` in the row it
    wrote before it went away, and routing a live caller to it would ring into
    nothing. Zero disables the check, which is only ever right in a test that
    is pinning some other behavior.
    """

    DEFAULT_HEARTBEAT_TTL_SECONDS = 300

    def __init__(
        self,
        db: AsyncSession,
        provider: Literal["plivo", "chime"] = "plivo",
        heartbeat_ttl_seconds: int = DEFAULT_HEARTBEAT_TTL_SECONDS,
    ):
        self.db = db
        self.provider = provider
        self.heartbeat_ttl_seconds = heartbeat_ttl_seconds

    async def get_number_assignment(self, number: str) -> Optional[UUID]:
        """Who owns ``number``, whichever way it was written.

        Both spellings are matched because both are in use: the provider sends
        bare digits on the webhook, the grant path canonicalizes to ``+``
        E.164 for caller ID, and whichever form an operator happened to seed
        decided whether inbound calls to that number worked at all. Matching on
        the digits and on the canonical form removes the coin flip.
        """
        digits = "".join(character for character in number if character.isdigit())
        if not digits:
            return None
        result = await self.db.execute(
            select(VirtualNumberDB).where(
                VirtualNumberDB.number.in_([digits, f"+{digits}"]),
                VirtualNumberDB.inbound_enabled.is_(True),
            )
        )
        vnumber = result.scalars().first()
        return vnumber.assigned_user_id if vnumber else None

    async def get_device_route(self, user_id: UUID) -> Optional[DeviceRoute]:
        """The freshest device this user has that is still worth calling.

        Ordered by heartbeat because a user who reinstalls the app leaves the
        old registration behind: the rows are additive, and only the most
        recent one describes a phone that exists.
        """
        result = await self.db.execute(
            select(DeviceRegistrationDB)
            .where(
                DeviceRegistrationDB.user_id == user_id,
                DeviceRegistrationDB.ready.is_(True),
            )
            .order_by(DeviceRegistrationDB.last_heartbeat.desc())
            .limit(1)
        )
        device = result.scalar_one_or_none()
        if device is None or not self._heartbeat_is_fresh(device.last_heartbeat):
            return None
        # An endpoint of "" is not an address. The enrollment row is created
        # with one, and dialling it produces `<User></User>` — a provider
        # instruction to call nobody, which reads as a connected call and
        # sounds like a dead line. Nothing routable, so nothing routed.
        if not device.sip_endpoint or not device.sip_endpoint.strip():
            return None
        return DeviceRoute(
            sip_endpoint=device.sip_endpoint,
            media_ready=bool(device.media_ready),
            last_heartbeat=device.last_heartbeat,
        )

    async def get_device_endpoint(self, user_id: UUID) -> Optional[str]:
        """The SIP endpoint alone, for callers that only need the address."""
        route = await self.get_device_route(user_id)
        return route.sip_endpoint if route else None

    def _heartbeat_is_fresh(self, last_heartbeat: Optional[datetime]) -> bool:
        if self.heartbeat_ttl_seconds <= 0:
            return True
        if last_heartbeat is None:
            return False
        # Rows written before the column was tz-aware, or by a driver that
        # dropped the offset, would otherwise raise on comparison. A naive
        # timestamp is read as UTC, which is what every writer here stores.
        if last_heartbeat.tzinfo is None:
            last_heartbeat = last_heartbeat.replace(tzinfo=timezone.utc)
        age = datetime.now(timezone.utc) - last_heartbeat
        return age <= timedelta(seconds=self.heartbeat_ttl_seconds)

    async def decide_inbound(self, called_number: str) -> InboundRouting:
        """Where an inbound call to ``called_number`` should go.

        Split out from XML generation because the decision is the interesting
        part: it is what gets logged, audited, and asserted on, and it is
        answerable without knowing what a ``<Dial>`` element looks like.
        """
        user_id = await self.get_number_assignment(called_number)
        if user_id is None:
            return InboundRouting(InboundDecision.UNASSIGNED, None, None)

        route = await self.get_device_route(user_id)
        if route is None:
            return InboundRouting(InboundDecision.UNAVAILABLE, user_id, None)
        if route.media_ready:
            return InboundRouting(InboundDecision.BRIDGE_TO_DEVICE, user_id, route)
        return InboundRouting(InboundDecision.SCREEN_CALLER, user_id, route)

    async def generate_routing_xml(
        self,
        called_number: str,
        caller_id: Optional[str] = None,
    ) -> tuple[str, FallbackDecision]:
        routing = await self.decide_inbound(called_number)
        if routing.decision is InboundDecision.BRIDGE_TO_DEVICE and routing.route is not None:
            return (
                self._generate_dial_xml(routing.route.sip_endpoint, caller_id),
                "route_to_device",
            )
        return self.generate_reject_xml(), "reject"

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

    def generate_bridge_to_device_xml(
        self, sip_endpoint: str, caller_id: Optional[str] = None
    ) -> str:
        """Connect the caller straight to the device's SIP endpoint.

        This is the whole media handoff. When the device says it can carry
        audio, the provider stops being an interactive voice response system
        and becomes a wire: the caller's audio arrives on the phone, Triplex
        answers in the user's voice, and nothing in this service is in the
        path. Until then the same call gets :meth:`generate_screening_prompt_xml`
        instead — which is a different product, not a degraded version of this
        one, so the two are separate methods with separate names.
        """
        return self._generate_dial_xml(sip_endpoint, caller_id)

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
        prompt_url = escape(f"{base}/prompts/screening-intro.wav")
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <GetInput action="{result_url}" method="POST" inputType="speech" language="en-US" speechModel="phone_call" executionTimeout="20" speechEndTimeout="2" interimSpeechResultsCallback="{interim_url}" interimSpeechResultsCallbackMethod="POST">
        <Play>{prompt_url}</Play>
    </GetInput>
    <Redirect method="POST">{hold_url}</Redirect>
</Response>'''

    def generate_screening_hold_xml(self, call_uuid: str, public_base_url: str) -> str:
        base = public_base_url.rstrip("/")
        hold_url = escape(f"{base}/screening/{call_uuid}/hold")
        prompt_url = escape(f"{base}/prompts/screening-hold.wav")
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Play>{prompt_url}</Play>
    <Wait length="20"/>
    <Redirect method="POST">{hold_url}</Redirect>
</Response>'''

    def generate_screening_wait_xml(self, call_uuid: str, public_base_url: str) -> str:
        hold_url = escape(
            f"{public_base_url.rstrip('/')}/screening/{call_uuid}/hold"
        )
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Wait length="20"/>
    <Redirect method="POST">{hold_url}</Redirect>
</Response>'''

    def generate_screening_connect_xml(
        self, sip_endpoint: str, caller_id: Optional[str]
    ) -> str:
        return self._generate_dial_xml(sip_endpoint, caller_id)

    def generate_screening_decline_xml(self, public_base_url: str) -> str:
        prompt_url = escape(
            f"{public_base_url.rstrip('/')}/prompts/screening-decline.wav"
        )
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Play>{prompt_url}</Play>
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
        prompt_asset = {
            "book_zoom": "book-zoom",
            "explain_delay": "explain-delay",
        }.get(automation_id)
        if prompt_asset is None:
            raise ValueError("Unsupported screening automation")
        base = public_base_url.rstrip("/")
        prompt_url = escape(f"{base}/prompts/{prompt_asset}.wav")
        complete_url = escape(f"{base}/prompts/automation-complete.wav")
        return f'''<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <GetInput action="{message_url}" method="POST" inputType="speech" language="en-US" speechModel="phone_call" executionTimeout="30" speechEndTimeout="3" interimSpeechResultsCallback="{interim_url}" interimSpeechResultsCallbackMethod="POST">
        <Play>{prompt_url}</Play>
    </GetInput>
    <Play>{complete_url}</Play>
    <Hangup/>
</Response>'''

    def generate_reject_xml(self) -> str:
        """Refuse the call outright, without answering it.

        Used only when the dialed number is not ours: there is no user to be
        honest to, and answering a call we have nothing to do with is a charge
        for nothing.
        """
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
            return self.generate_reject_xml(), "reject"
        elif policy == "transfer" and transfer_number:
            return self._generate_transfer_xml(transfer_number), "transfer"
        elif policy == "cloud_agent":
            return self.generate_reject_xml(), "cloud_agent_not_implemented"
        else:
            return self.generate_reject_xml(), "reject"

    def _generate_transfer_xml(self, number: str) -> str:
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<Response>
    <Dial>{number}</Dial>
</Response>"""
