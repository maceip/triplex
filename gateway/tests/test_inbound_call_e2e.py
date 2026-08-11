"""An inbound call, driven the way Plivo drives one.

Every request below is a signed provider webhook against the real ASGI app and
a real PostgreSQL database. The assertions are about what the gateway told the
provider to do with a live caller — which is the only thing about this service
that a caller can hear.
"""

from __future__ import annotations

import uuid
from xml.etree import ElementTree

import pytest
from sqlalchemy import select

from app.db.models import (
    AuditLogDB,
    DeviceRegistrationDB,
    ScreeningSessionDB,
    UserAccountDB,
)

from conftest import PUBLIC_BASE_URL, plivo_v3_signature

CALLER = "+14155550142"


def xml_of(response) -> ElementTree.Element:
    assert response.status_code == 200, response.text
    return ElementTree.fromstring(response.text)


async def answer(plivo, enrolled, call_uuid: str, **overrides):
    params = {
        "To": enrolled.virtual_number,
        "From": CALLER,
        "CallUUID": call_uuid,
        "Direction": "inbound",
        "CallStatus": "ringing",
    }
    params.update(overrides)
    return await plivo.post("/answer", params)


async def set_ready(client, enrolled, *, ready: bool, media_ready: bool):
    return await client.post(
        f"/devices/ready?ready={str(ready).lower()}&media_ready={str(media_ready).lower()}",
        headers=enrolled.auth,
    )


class TestDeviceReadyBridge:
    """The media handoff: a device that says it can carry audio gets the call."""

    async def test_a_media_ready_device_receives_the_caller_directly(
        self, client, plivo, enrolled, session_maker
    ):
        await set_ready(client, enrolled, ready=True, media_ready=True)

        call_uuid = uuid.uuid4().hex
        root = xml_of(await answer(plivo, enrolled, call_uuid))

        # One instruction: connect this caller to that endpoint. No prompt, no
        # speech synthesis, no screening session — the phone answers.
        dial = root.find("Dial")
        assert dial is not None, f"expected a bridge, got: {root and root[0].tag}"
        assert dial.find("User").text == enrolled.sip_endpoint
        assert dial.get("callerId") == CALLER
        assert root.find("GetInput") is None
        assert root.find("Speak") is None

        async with session_maker() as session:
            assert await session.get(ScreeningSessionDB, call_uuid) is None
            events = (
                await session.execute(
                    select(AuditLogDB.event_type).where(
                        AuditLogDB.user_id == enrolled.user_id
                    )
                )
            ).scalars().all()
        assert "inbound_bridged_to_device" in events

    async def test_a_registered_device_that_cannot_take_media_is_screened(
        self, client, plivo, enrolled, session_maker
    ):
        # Registered and reachable, but has not claimed the media path.
        await set_ready(client, enrolled, ready=True, media_ready=False)

        call_uuid = uuid.uuid4().hex
        root = xml_of(await answer(plivo, enrolled, call_uuid))

        assert root.find("Dial") is None, "an endpoint that cannot take media is not bridged"
        get_input = root.find("GetInput")
        assert get_input is not None
        assert get_input.get("action").endswith(f"/screening/{call_uuid}/result")

        async with session_maker() as session:
            session_row = await session.get(ScreeningSessionDB, call_uuid)
        assert session_row is not None
        assert session_row.status == "asking"
        assert session_row.sip_endpoint == enrolled.sip_endpoint
        assert session_row.caller_number == CALLER

    async def test_media_readiness_cannot_outlive_registration(
        self, client, plivo, enrolled
    ):
        await set_ready(client, enrolled, ready=True, media_ready=True)
        # The device goes away: SIP registration lost, so it is not carrying
        # anything, whatever it claimed a moment ago.
        response = await set_ready(client, enrolled, ready=False, media_ready=True)
        assert response.json() == {"ready": False, "media_ready": False}

        root = xml_of(await answer(plivo, enrolled, uuid.uuid4().hex))
        assert root.find("Speak") is not None, "no device: say so"
        assert root.find("Dial") is None


class TestNoDeviceIsHonest:
    """When nothing can take the call, the caller is told."""

    async def test_an_unready_device_gets_a_spoken_unavailable_message(
        self, plivo, enrolled
    ):
        root = xml_of(await answer(plivo, enrolled, uuid.uuid4().hex))

        speak = root.find("Speak")
        assert speak is not None
        assert speak.text == "The local agent is unavailable."
        assert root.find("Hangup") is not None
        # Not a silent drop, and not a pretend connection.
        assert root.find("Dial") is None
        assert root.find("Reject") is None

    async def test_a_device_that_stopped_checking_in_is_not_routed_to(
        self, client, plivo, enrolled, age_device
    ):
        await set_ready(client, enrolled, ready=True, media_ready=True)
        # The phone has been off for an hour. The row still says it is ready.
        await age_device(enrolled, seconds=3_600)

        root = xml_of(await answer(plivo, enrolled, uuid.uuid4().hex))

        assert root.find("Dial") is None, "a stale readiness claim must not route a caller"
        assert root.find("Speak").text == "The local agent is unavailable."

    async def test_a_call_to_an_unassigned_number_is_rejected(self, plivo, enrolled):
        root = xml_of(
            await answer(plivo, enrolled, uuid.uuid4().hex, To="+14155559999")
        )

        # Nothing of ours: nothing to say, and nothing to bill for answering.
        assert root.find("Reject") is not None
        assert root.find("Speak") is None

    async def test_a_screenable_call_with_no_call_uuid_is_answered_honestly(
        self, client, plivo, enrolled
    ):
        await set_ready(client, enrolled, ready=True, media_ready=False)

        root = xml_of(await answer(plivo, enrolled, "", CallUUID=""))

        # A screening session is keyed on the call UUID; with no UUID there is
        # nothing to hold the call in, so it is not held.
        assert root.find("GetInput") is None
        assert root.find("Speak") is not None


class TestWebhookAuthenticity:
    """The answer URL is public, so anything that reaches it can try to drive a call."""

    async def test_an_unsigned_webhook_cannot_route_a_call(
        self, client, plivo, enrolled
    ):
        await set_ready(client, enrolled, ready=True, media_ready=True)

        response = await plivo.post(
            "/answer",
            {"To": enrolled.virtual_number, "From": CALLER, "CallUUID": uuid.uuid4().hex},
            sign=False,
        )

        assert response.status_code == 403

    async def test_a_signature_over_different_parameters_is_rejected(
        self, client, plivo, enrolled
    ):
        await set_ready(client, enrolled, ready=True, media_ready=True)
        call_uuid = uuid.uuid4().hex

        # Sign one destination, then send another — the V3 signature covers the
        # POST parameters, so swapping them has to invalidate it.
        signed = {"To": enrolled.virtual_number, "From": CALLER, "CallUUID": call_uuid}
        nonce = uuid.uuid4().hex
        signature = plivo_v3_signature(f"{PUBLIC_BASE_URL}/answer", nonce, signed)

        response = await client.post(
            "/answer",
            data={**signed, "To": "+14155559999"},
            headers={
                "X-Plivo-Signature-V3-Nonce": nonce,
                "X-Plivo-Signature-V3": signature,
            },
        )

        assert response.status_code == 403


class TestScreenedCallLifecycle:
    """The whole screening path, callback by callback, as the provider walks it."""

    @pytest.fixture
    async def screened_call(self, client, plivo, enrolled):
        await set_ready(client, enrolled, ready=True, media_ready=False)
        call_uuid = uuid.uuid4().hex
        await answer(plivo, enrolled, call_uuid)
        return call_uuid

    async def test_the_caller_reason_is_captured_and_offered_to_the_phone(
        self, client, plivo, enrolled, screened_call
    ):
        # Plivo streams partial speech while the caller is still talking.
        await plivo.post(
            f"/screening/{screened_call}/interim",
            {"StableSpeech": "Hi, it's Marcus from", "UnstableSpeech": "Pinnacle Couriers"},
        )
        # Then the final recognition result.
        await plivo.post(
            f"/screening/{screened_call}/result",
            {
                "Speech": "Hi, it's Marcus from Pinnacle Couriers, I have a delivery.",
                "SpeechConfidenceScore": "0.94",
            },
        )

        active = await client.get("/screening/active", headers=enrolled.auth)
        assert active.status_code == 200
        body = active.json()
        assert body["call_uuid"] == screened_call
        assert body["status"] == "ready"
        assert body["transcript"] == (
            "Hi, it's Marcus from Pinnacle Couriers, I have a delivery."
        )
        assert body["confidence"] == "0.94"

    async def test_a_silent_caller_still_produces_a_decidable_session(
        self, client, plivo, enrolled, screened_call
    ):
        await plivo.post(f"/screening/{screened_call}/result", {"Speech": ""})

        body = (await client.get("/screening/active", headers=enrolled.auth)).json()
        # The user is shown the truth — the caller said nothing — rather than an
        # empty card they cannot act on.
        assert body["transcript"] == "The caller did not provide a reason."
        assert body["status"] == "ready"

    async def test_holding_keeps_the_caller_on_the_line_and_the_session_alive(
        self, plivo, screened_call, session_maker
    ):
        await plivo.post(f"/screening/{screened_call}/result", {"Speech": "It's Marcus."})
        root = xml_of(await plivo.post(f"/screening/{screened_call}/hold", {}))

        # Music, wait, and come back — a loop, not a hangup.
        assert root.find("Play") is not None
        assert root.find("Redirect").text.endswith(f"/screening/{screened_call}/hold")

        async with session_maker() as session:
            row = await session.get(ScreeningSessionDB, screened_call)
        assert row.status == "holding"

    async def test_accepting_connects_the_caller_to_the_registered_endpoint(
        self, plivo, enrolled, screened_call, session_maker
    ):
        await plivo.post(f"/screening/{screened_call}/result", {"Speech": "It's Marcus."})
        root = xml_of(await plivo.post(f"/screening/{screened_call}/connect", {}))

        assert root.find("Dial/User").text == enrolled.sip_endpoint
        async with session_maker() as session:
            row = await session.get(ScreeningSessionDB, screened_call)
        assert row.status == "connected"

    async def test_handing_the_call_to_an_automation_asks_and_then_closes(
        self, plivo, screened_call, session_maker
    ):
        await plivo.post(f"/screening/{screened_call}/result", {"Speech": "It's Marcus."})
        root = xml_of(
            await plivo.post(f"/screening/{screened_call}/automation/explain_delay", {})
        )

        get_input = root.find("GetInput")
        assert get_input is not None
        assert get_input.get("action").endswith(f"/screening/{screened_call}/message")
        assert get_input.find("Play").text.endswith("/prompts/explain-delay.wav")

        async with session_maker() as session:
            row = await session.get(ScreeningSessionDB, screened_call)
        assert row.decision == "explain_delay"

        # The caller leaves the message and the call ends with a spoken close.
        closing = xml_of(
            await plivo.post(
                f"/screening/{screened_call}/message",
                {"Speech": "Tell her the parcel is at the side door."},
            )
        )
        assert closing.find("Speak") is not None
        assert closing.find("Hangup") is not None

        async with session_maker() as session:
            row = await session.get(ScreeningSessionDB, screened_call)
        assert row.status == "completed"
        assert "side door" in row.transcript

    async def test_hangup_closes_the_session(self, plivo, screened_call, session_maker):
        await plivo.post(
            "/hangup",
            {"CallUUID": screened_call, "Duration": "37", "CallStatus": "completed"},
        )

        async with session_maker() as session:
            row = await session.get(ScreeningSessionDB, screened_call)
        assert row.status == "completed"

    async def test_a_decision_on_a_call_that_is_already_decided_is_refused(
        self, client, plivo, enrolled, screened_call, session_maker
    ):
        await plivo.post(f"/screening/{screened_call}/result", {"Speech": "It's Marcus."})
        async with session_maker() as session:
            row = await session.get(ScreeningSessionDB, screened_call)
            row.status = "connected"
            await session.commit()

        response = await client.post(
            f"/screening/{screened_call}/decision",
            json={"decision": "decline"},
            headers=enrolled.auth,
        )

        assert response.status_code == 409

    async def test_another_users_call_is_not_visible_or_decidable(
        self, client, plivo, enrolled, screened_call, session_maker
    ):
        stranger_id = uuid.uuid4()
        stranger_token = f"device_{uuid.uuid4().hex}"
        async with session_maker() as session:
            session.add(
                UserAccountDB(
                    id=stranger_id, email="stranger@example.test", phone_number="+15005550000"
                )
            )
            session.add(
                DeviceRegistrationDB(
                    user_id=stranger_id,
                    device_token=stranger_token,
                    sip_endpoint="sip:stranger@phone.plivo.com",
                )
            )
            await session.commit()

        stranger_auth = {"X-Device-Token": stranger_token}
        assert (await client.get("/screening/active", headers=stranger_auth)).json() is None
        decision = await client.post(
            f"/screening/{screened_call}/decision",
            json={"decision": "accept"},
            headers=stranger_auth,
        )
        assert decision.status_code == 404


class TestDeviceRegistrationIdentity:
    """Registration attaches an endpoint to the token the phone already holds."""

    async def test_registering_updates_the_callers_own_row_and_keeps_its_token(
        self, client, enrolled, session_maker
    ):
        response = await client.post(
            "/devices/register",
            json={"sip_endpoint": "sip:rotated@phone.plivo.com", "push_token": "fcm-1"},
            headers=enrolled.auth,
        )

        assert response.status_code == 200
        # The token the app is holding still works after registering.
        assert response.json()["device_token"] == enrolled.device_token

        async with session_maker() as session:
            rows = (
                await session.execute(
                    select(DeviceRegistrationDB).where(
                        DeviceRegistrationDB.user_id == enrolled.user_id
                    )
                )
            ).scalars().all()
        # One phone, one row — re-registering does not leave orphans behind.
        assert len(rows) == 1
        assert rows[0].sip_endpoint == "sip:rotated@phone.plivo.com"

    async def test_a_fresh_registration_does_not_inherit_the_previous_readiness(
        self, client, plivo, enrolled
    ):
        await set_ready(client, enrolled, ready=True, media_ready=True)

        await client.post(
            "/devices/register",
            json={"sip_endpoint": enrolled.sip_endpoint},
            headers=enrolled.auth,
        )

        status = (await client.get("/devices/status", headers=enrolled.auth)).json()
        assert status["ready"] is False
        assert status["media_ready"] is False
        # And a caller is told so rather than bridged into a device that has
        # just restarted and is not listening yet.
        root = xml_of(await answer(plivo, enrolled, uuid.uuid4().hex))
        assert root.find("Speak") is not None

    async def test_an_empty_sip_endpoint_is_refused(self, client, enrolled):
        response = await client.post(
            "/devices/register", json={"sip_endpoint": "   "}, headers=enrolled.auth
        )
        assert response.status_code == 422


class TestInboundDecision:
    """The routing decision itself, without the XML in the way."""

    async def test_the_decision_names_the_owner_even_when_no_device_is_reachable(
        self, session_maker, enrolled
    ):
        from app.services.routing import InboundDecision, RoutingService

        async with session_maker() as session:
            routing = await RoutingService(session).decide_inbound(enrolled.virtual_number)

        assert routing.decision is InboundDecision.UNAVAILABLE
        # The owner is still known, so the refusal can be audited against the
        # account it happened to rather than disappearing.
        assert routing.user_id == enrolled.user_id
        assert routing.route is None

    async def test_a_number_written_with_or_without_a_plus_resolves_the_same_way(
        self, client, session_maker, enrolled
    ):
        from app.services.routing import RoutingService

        await set_ready(client, enrolled, ready=True, media_ready=True)

        async with session_maker() as session:
            service = RoutingService(session)
            # Plivo sends bare digits on the webhook; the grant path stores the
            # canonical "+" form. Both have to find the same account.
            with_plus = await service.decide_inbound(enrolled.virtual_number)
            without_plus = await service.decide_inbound(
                enrolled.virtual_number.removeprefix("+")
            )

        assert with_plus.user_id == enrolled.user_id
        assert without_plus.user_id == enrolled.user_id
        assert with_plus.decision is without_plus.decision

    async def test_an_endpointless_registration_is_never_routed_to(
        self, client, session_maker, enrolled
    ):
        from app.services.routing import InboundDecision, RoutingService

        await set_ready(client, enrolled, ready=True, media_ready=True)
        # The shape the enrollment row is created in: ready, fresh, and with
        # nowhere to send anything.
        async with session_maker() as session:
            device = (
                await session.execute(
                    select(DeviceRegistrationDB).where(
                        DeviceRegistrationDB.user_id == enrolled.user_id
                    )
                )
            ).scalar_one()
            device.sip_endpoint = ""
            await session.commit()

        async with session_maker() as session:
            routing = await RoutingService(session).decide_inbound(enrolled.virtual_number)

        assert routing.decision is InboundDecision.UNAVAILABLE
        assert routing.route is None
