from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

import jwt
import pytest

from app.services.route_grants import (
    OutboundRouteService,
    RouteGrantError,
    build_plivo_sip_uri,
    destination_from_plivo,
    endpoint_identity_matches,
    normalize_e164,
)
from app.services.routing import RoutingService

SIGNING_KEY = "test-only-route-signing-key-that-is-at-least-32-bytes"


class ScalarResult:
    def __init__(self, value):
        self.value = value

    def scalar_one_or_none(self):
        return self.value


def fake_db(*results):
    db = SimpleNamespace(
        execute=AsyncMock(side_effect=[ScalarResult(value) for value in results]),
        add=Mock(),
        commit=AsyncMock(),
    )
    return db


def test_e164_and_secure_plivo_route_are_strict():
    assert normalize_e164("+1 (415) 555-0123") == "+14155550123"
    assert destination_from_plivo("sips:14155550123@phone.plivo.com") == "+14155550123"
    assert (
        build_plivo_sip_uri("+14155550123", "phone.plivo.com", "phone.plivo.com")
        == "sips:14155550123@phone.plivo.com:5061;transport=tls"
    )

    for invalid in (
        "911",
        "1415;user=phone",
        "sip:14155550123@evil.test",
        "+012345678",
    ):
        with pytest.raises(RouteGrantError):
            normalize_e164(invalid)
    with pytest.raises(RouteGrantError):
        build_plivo_sip_uri("+14155550123", "attacker.example", "phone.plivo.com")


def test_endpoint_identity_matches_provider_callback_forms():
    assert endpoint_identity_matches(
        "triplex123",
        ["<sips:triplex123@phone.plivo.com>;tag=provider"],
    )
    assert endpoint_identity_matches("triplex123", ["triplex123"])
    assert not endpoint_identity_matches("triplex123", ["other123"])


def test_outbound_xml_is_number_only_and_escaped():
    xml = RoutingService(Mock()).generate_outbound_dial_xml(
        "+14155550123", "+14155550999"
    )
    assert "<Number>+14155550123</Number>" in xml
    assert 'callerId="+14155550999"' in xml
    assert "<Sip>" not in xml


@pytest.mark.asyncio
async def test_issue_binds_and_starts_one_typed_task():
    user_id = uuid4()
    task_id = uuid4()
    task = SimpleNamespace(
        id=task_id,
        user_id=user_id,
        task_type="appointment_modify",
        destination_number="+14155550123",
        status="pending",
        started_at=None,
    )
    credential = SimpleNamespace(
        provider="plivo",
        username="triplex123",
        domain="phone.plivo.com",
    )
    number = SimpleNamespace(number="+14155550999")
    db = fake_db(task, credential, uuid4(), number, None)
    service = OutboundRouteService(
        db,
        SIGNING_KEY,
        ttl_seconds=120,
        sip_domain="phone.plivo.com",
    )

    grant = await service.issue(user_id, task_id, "+1 (415) 555-0123")

    assert grant.task_id == task_id
    assert grant.sip_uri.startswith("sips:14155550123@phone.plivo.com:5061")
    assert grant.header_name == "X-PH-TriplexGrant"
    assert grant.token.isalnum()
    assert set(grant.token) <= set("0123456789abcdef")
    assert task.status == "active"
    assert task.started_at is not None
    assert db.add.call_count == 1
    db.commit.assert_awaited_once()


@pytest.mark.asyncio
async def test_consume_is_idempotent_for_same_call_and_rejects_replay():
    user_id = uuid4()
    task_id = uuid4()
    task = SimpleNamespace(
        id=task_id,
        user_id=user_id,
        task_type="reservation_update",
        destination_number="+14155550123",
        status="pending",
        started_at=None,
    )
    credential = SimpleNamespace(
        provider="plivo",
        username="triplex123",
        domain="phone.plivo.com",
    )
    number = SimpleNamespace(number="+14155550999")
    issue_db = fake_db(task, credential, uuid4(), number, None)
    service = OutboundRouteService(
        issue_db,
        SIGNING_KEY,
        ttl_seconds=120,
        sip_domain="phone.plivo.com",
    )
    issued = await service.issue(user_id, task_id, "+14155550123")
    grant_row = issue_db.add.call_args.args[0]

    active_task = SimpleNamespace(id=task_id, user_id=user_id, status="active")
    consume_db = fake_db(grant_row, active_task)
    consumer = OutboundRouteService(
        consume_db,
        SIGNING_KEY,
        ttl_seconds=120,
        sip_domain="phone.plivo.com",
    )
    call_uuid = str(uuid4())
    authorized = await consumer.consume(
        token=issued.token,
        provider_call_uuid=call_uuid,
        destination_value="sips:14155550123@phone.plivo.com",
        endpoint_candidates=["<sip:triplex123@phone.plivo.com>;tag=abc"],
    )
    assert authorized.destination_number == "+14155550123"
    assert grant_row.consumed_call_uuid == call_uuid
    consume_db.commit.assert_awaited_once()

    idempotent_db = fake_db(grant_row, active_task)
    idempotent = OutboundRouteService(
        idempotent_db,
        SIGNING_KEY,
        ttl_seconds=120,
        sip_domain="phone.plivo.com",
    )
    await idempotent.consume(
        token=issued.token,
        provider_call_uuid=call_uuid,
        destination_value="+14155550123",
        endpoint_candidates=["triplex123"],
    )
    idempotent_db.commit.assert_not_awaited()

    replay_db = fake_db(grant_row, active_task)
    replay = OutboundRouteService(
        replay_db,
        SIGNING_KEY,
        ttl_seconds=120,
        sip_domain="phone.plivo.com",
    )
    with pytest.raises(RouteGrantError, match="already been consumed"):
        await replay.consume(
            token=issued.token,
            provider_call_uuid=str(uuid4()),
            destination_value="+14155550123",
            endpoint_candidates=["triplex123"],
        )


@pytest.mark.asyncio
async def test_expired_or_tampered_grant_fails_closed():
    db = fake_db()
    service = OutboundRouteService(
        db,
        SIGNING_KEY,
        ttl_seconds=120,
        sip_domain="phone.plivo.com",
    )
    with pytest.raises(RouteGrantError, match="invalid or expired"):
        await service.consume(
            token="not-a-jwt",
            provider_call_uuid=str(uuid4()),
            destination_value="+14155550123",
            endpoint_candidates=["triplex123"],
        )

    now = datetime.now(timezone.utc)
    expired = jwt.encode(
        {
            "iss": "triplex-gateway",
            "aud": "triplex-plivo-outbound",
            "sub": str(uuid4()),
            "jti": str(uuid4()),
            "task_id": str(uuid4()),
            "destination": "+14155550123",
            "endpoint": "triplex123",
            "iat": now - timedelta(minutes=2),
            "exp": now - timedelta(seconds=1),
        },
        SIGNING_KEY,
        algorithm="HS256",
    ).encode("ascii").hex()
    with pytest.raises(RouteGrantError, match="invalid or expired"):
        await service.consume(
            token=expired,
            provider_call_uuid=str(uuid4()),
            destination_value="+14155550123",
            endpoint_candidates=["triplex123"],
        )
