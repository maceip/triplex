"""An outbound call, end to end: task, grant, and the provider callback.

The gateway never places this call. The phone does — it holds the SIP
credentials and it sends the INVITE. What the gateway does is authorize exactly
one call for exactly one task, and then recognise its own authorization when
Plivo calls back before connecting PSTN.

That makes two things worth testing, and both are here against a real database:
the grant is issued only when it should be, and it can be spent exactly once,
for the destination and from the endpoint it was issued for.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone
from xml.etree import ElementTree

import pytest
from sqlalchemy import select, text

from app.db.models import AuditLogDB, OutboundRouteGrantDB, TaskDefinitionDB

HEADER_NAME = "X-PH-TriplexGrant"
DESTINATION = "+14155550188"


def xml_of(response) -> ElementTree.Element:
    assert response.status_code == 200, response.text
    return ElementTree.fromstring(response.text)


async def make_device_ready(client, enrolled):
    return await client.post(
        "/devices/ready?ready=true&media_ready=true", headers=enrolled.auth
    )


async def authorize(client, enrolled, task_id, destination=DESTINATION):
    return await client.post(
        f"/tasks/{task_id}/authorize-outbound",
        json={"destination_number": destination},
        headers=enrolled.auth,
    )


async def provider_callback(plivo, grant_token, enrolled, *, call_uuid=None, **overrides):
    """The callback Plivo makes when the phone's INVITE reaches it."""
    params = {
        "CallUUID": call_uuid or uuid.uuid4().hex,
        # Plivo labels an authenticated SIP-endpoint call as inbound to the
        # attached application; the grant, not this label, is what makes it
        # outbound.
        "Direction": "inbound",
        "From": f"sip:{enrolled.sip_username}@phone.plivo.com",
        "To": f"sips:{DESTINATION.removeprefix('+')}@phone.plivo.com",
        HEADER_NAME: grant_token,
    }
    params.update(overrides)
    return await plivo.post("/answer", params)


class TestGrantIssuance:
    """A grant is an authorization to dial. It is refused unless everything holds."""

    async def test_a_ready_device_gets_a_grant_that_activates_the_task(
        self, client, enrolled, make_task, session_maker
    ):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled)

        response = await authorize(client, enrolled, task_id)

        assert response.status_code == 200, response.text
        grant = response.json()
        assert grant["header_name"] == HEADER_NAME
        # The URI the phone will INVITE: TLS, on the provisioned domain, with
        # the destination as the user part.
        assert grant["sip_uri"] == (
            f"sips:{DESTINATION.removeprefix('+')}@phone.plivo.com:5061;transport=tls"
        )
        assert grant["expires_in_seconds"] == 120
        # Hex, because Plivo drops punctuation from custom SIP header values.
        assert set(grant["token"]) <= set("0123456789abcdef")

        async with session_maker() as session:
            task = await session.get(TaskDefinitionDB, task_id)
            assert task.status == "active", "issuing a grant commits the task"
            assert task.started_at is not None
            events = (
                await session.execute(
                    select(AuditLogDB.event_type).where(
                        AuditLogDB.user_id == enrolled.user_id
                    )
                )
            ).scalars().all()
        assert "outbound_route_issued" in events

    async def test_a_grant_is_refused_when_no_device_is_ready(
        self, client, enrolled, make_task
    ):
        task_id = await make_task(enrolled)

        response = await authorize(client, enrolled, task_id)

        assert response.status_code == 409
        assert response.json()["detail"] == "The Android agent is not ready"

    async def test_a_device_that_stopped_checking_in_cannot_authorize_a_call(
        self, client, enrolled, make_task, age_device
    ):
        await make_device_ready(client, enrolled)
        # The phone has been off for an hour; the row still says it is ready.
        await age_device(enrolled, seconds=3_600)
        task_id = await make_task(enrolled)

        response = await authorize(client, enrolled, task_id)

        assert response.status_code == 409
        assert response.json()["detail"] == "The Android agent is not ready"

    async def test_a_destination_that_does_not_match_the_task_is_refused(
        self, client, enrolled, make_task
    ):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled, destination=DESTINATION)

        response = await authorize(client, enrolled, task_id, destination="+14155550999")

        assert response.status_code == 409
        assert "does not match the task" in response.json()["detail"]

    async def test_a_finished_task_cannot_be_dialled_again(
        self, client, enrolled, make_task
    ):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled, status="completed")

        response = await authorize(client, enrolled, task_id)

        assert response.status_code == 409
        assert "current state" in response.json()["detail"]

    async def test_another_users_task_is_not_visible(
        self, client, enrolled, make_task, session_maker
    ):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled)

        from app.db.models import DeviceRegistrationDB, UserAccountDB

        stranger_id = uuid.uuid4()
        stranger_token = f"device_{uuid.uuid4().hex}"
        async with session_maker() as session:
            session.add(
                UserAccountDB(
                    id=stranger_id,
                    email="stranger-outbound@example.test",
                    phone_number="+15005550000",
                )
            )
            session.add(
                DeviceRegistrationDB(
                    user_id=stranger_id,
                    device_token=stranger_token,
                    sip_endpoint="sip:stranger@phone.plivo.com",
                    ready=True,
                )
            )
            await session.commit()

        response = await client.post(
            f"/tasks/{task_id}/authorize-outbound",
            json={"destination_number": DESTINATION},
            headers={"X-Device-Token": stranger_token},
        )

        assert response.status_code == 409
        assert response.json()["detail"] == "Task not found"

    async def test_every_refusal_is_audited_with_its_reason(
        self, client, enrolled, make_task, session_maker
    ):
        task_id = await make_task(enrolled)

        await authorize(client, enrolled, task_id)

        async with session_maker() as session:
            row = (
                await session.execute(
                    select(AuditLogDB).where(
                        AuditLogDB.event_type == "outbound_route_refused"
                    )
                )
            ).scalar_one()
        # A phone that cannot dial says only "the call could not be authorized"
        # on screen; the reason has to survive somewhere it can be read later.
        assert row.event_data["reason"] == "The Android agent is not ready"
        assert row.event_data["task_id"] == str(task_id)
        assert row.event_data["status"] == 409
        # And it must not carry the grant material.
        assert "token" not in row.event_data


class TestGrantRedemption:
    """The provider callback: the gateway recognising its own authorization."""

    @pytest.fixture
    async def issued(self, client, enrolled, make_task):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled)
        response = await authorize(client, enrolled, task_id)
        assert response.status_code == 200, response.text
        return response.json()

    async def test_a_valid_grant_connects_the_sip_leg_to_pstn(
        self, plivo, enrolled, issued, session_maker
    ):
        call_uuid = uuid.uuid4().hex

        root = xml_of(
            await provider_callback(plivo, issued["token"], enrolled, call_uuid=call_uuid)
        )

        dial = root.find("Dial")
        assert dial.find("Number").text == DESTINATION
        # The user's own virtual number is the caller ID — never the callee's,
        # and never the raw SIP endpoint.
        assert dial.get("callerId") == enrolled.virtual_number

        async with session_maker() as session:
            grant = (
                await session.execute(select(OutboundRouteGrantDB))
            ).scalar_one()
            events = (
                await session.execute(
                    select(AuditLogDB.event_type).where(
                        AuditLogDB.event_type == "outbound_route_consumed"
                    )
                )
            ).scalars().all()
        assert grant.consumed_call_uuid == call_uuid
        assert grant.consumed_at is not None
        assert events == ["outbound_route_consumed"]

    async def test_a_grant_cannot_be_spent_on_a_second_call(
        self, plivo, enrolled, issued
    ):
        first = await provider_callback(plivo, issued["token"], enrolled)
        assert first.status_code == 200

        second = await provider_callback(plivo, issued["token"], enrolled)

        assert second.status_code == 403
        assert "already been consumed" in second.json()["detail"]

    async def test_a_retried_callback_for_the_same_call_still_connects(
        self, plivo, enrolled, issued
    ):
        """Providers retry. A retry is not a second call."""
        call_uuid = uuid.uuid4().hex

        first = await provider_callback(plivo, issued["token"], enrolled, call_uuid=call_uuid)
        retry = await provider_callback(plivo, issued["token"], enrolled, call_uuid=call_uuid)

        assert first.status_code == 200
        assert retry.status_code == 200
        assert retry.text == first.text

    async def test_the_grant_is_bound_to_the_destination_it_was_issued_for(
        self, plivo, enrolled, issued
    ):
        response = await provider_callback(
            plivo,
            issued["token"],
            enrolled,
            To="sips:14155559999@phone.plivo.com",
        )

        assert response.status_code == 403
        assert "destination does not match" in response.json()["detail"]

    async def test_the_grant_is_bound_to_the_endpoint_it_was_issued_for(
        self, plivo, enrolled, issued
    ):
        response = await provider_callback(
            plivo,
            issued["token"],
            enrolled,
            From="sip:someone_elses_endpoint@phone.plivo.com",
        )

        assert response.status_code == 403
        assert "endpoint does not match" in response.json()["detail"]

    async def test_an_expired_grant_is_refused(
        self, plivo, enrolled, issued, session_maker
    ):
        async with session_maker() as session:
            await session.execute(
                text("UPDATE outbound_route_grants SET expires_at = :ts"),
                {"ts": datetime.now(timezone.utc) - timedelta(seconds=1)},
            )
            await session.commit()

        response = await provider_callback(plivo, issued["token"], enrolled)

        assert response.status_code == 403
        assert "expired" in response.json()["detail"]

    async def test_a_grant_for_a_task_that_has_been_stopped_is_refused(
        self, client, plivo, enrolled, issued, session_maker
    ):
        async with session_maker() as session:
            task = await session.get(TaskDefinitionDB, uuid.UUID(issued["task_id"]))
            task.status = "stopped"
            await session.commit()

        response = await provider_callback(plivo, issued["token"], enrolled)

        assert response.status_code == 403
        assert "no longer active" in response.json()["detail"]

    async def test_a_forged_token_is_refused(self, plivo, enrolled, issued):
        # Same shape, different signature: hex-encoded JWT with a flipped byte.
        forged = issued["token"][:-2] + ("aa" if issued["token"][-2:] != "aa" else "bb")

        response = await provider_callback(plivo, forged, enrolled)

        assert response.status_code == 403

    async def test_a_call_with_no_grant_at_all_is_refused(self, plivo, enrolled):
        response = await plivo.post(
            "/answer",
            {
                "CallUUID": uuid.uuid4().hex,
                "Direction": "outbound",
                "From": f"sip:{enrolled.sip_username}@phone.plivo.com",
                "To": f"sips:{DESTINATION.removeprefix('+')}@phone.plivo.com",
            },
        )

        assert response.status_code == 403
        assert response.json()["detail"] == "Missing outbound route grant"

    async def test_an_unsigned_callback_cannot_redeem_a_grant(
        self, plivo, enrolled, issued
    ):
        response = await plivo.post(
            "/answer",
            {
                "CallUUID": uuid.uuid4().hex,
                "Direction": "inbound",
                "From": f"sip:{enrolled.sip_username}@phone.plivo.com",
                "To": f"sips:{DESTINATION.removeprefix('+')}@phone.plivo.com",
                HEADER_NAME: issued["token"],
            },
            sign=False,
        )

        assert response.status_code == 403


class TestReauthorization:
    """Re-dialling a task before it has been used, and after."""

    async def test_a_second_grant_supersedes_an_unused_first_one(
        self, client, plivo, enrolled, make_task
    ):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled)

        first = (await authorize(client, enrolled, task_id)).json()
        # The call never went through — the user taps dial again.
        second = (await authorize(client, enrolled, task_id)).json()

        assert first["token"] != second["token"]
        # Only the newest grant is redeemable; the abandoned one is dead.
        assert (await provider_callback(plivo, first["token"], enrolled)).status_code == 403
        assert (await provider_callback(plivo, second["token"], enrolled)).status_code == 200

    async def test_a_task_whose_call_already_happened_cannot_be_redialled(
        self, client, plivo, enrolled, make_task
    ):
        await make_device_ready(client, enrolled)
        task_id = await make_task(enrolled)
        issued = (await authorize(client, enrolled, task_id)).json()
        assert (await provider_callback(plivo, issued["token"], enrolled)).status_code == 200

        response = await authorize(client, enrolled, task_id)

        assert response.status_code == 409
        assert "already used its outbound call grant" in response.json()["detail"]
