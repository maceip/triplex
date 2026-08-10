"""Fixtures for the end-to-end call tests.

These run the gateway as it actually runs: the real ASGI app, the real
SQLAlchemy models against a real PostgreSQL database, and provider webhooks
signed with Plivo's real V3 algorithm. Nothing here is a mock — the point is
that a passing run is evidence the call flow works, not evidence that the test
doubles agree with each other.

The database URL comes from ``TEST_DATABASE_URL`` (falling back to
``DATABASE_URL``). The schema is created once and every test starts from empty
tables, so the tests can run in any order and a failure leaves a database you
can look at.
"""

from __future__ import annotations

import os


TEST_SETTINGS = {
    "DATABASE_URL": "postgresql+asyncpg://test:test@localhost:5432/test",
    "PLIVO_AUTH_TOKEN": "test-plivo-auth-token",
    "PUBLIC_BASE_URL": "https://gateway.test.example",
    "PLIVO_SIP_DOMAIN": "phone.plivo.com",
    "OUTBOUND_ROUTE_SIGNING_KEY": "test-signing-key-that-is-longer-than-thirty-two-bytes",
    "OUTBOUND_ROUTE_TTL_SECONDS": "120",
    "VOICE_SERVICE_URL": "http://voice.test.internal:8801",
    "UNAVAILABLE_MESSAGE": "The local agent is unavailable.",
    "DEFAULT_RATE_LIMIT": "300/minute",
    "REGISTRATION_RATE_LIMIT": "10/minute",
    "WEBHOOK_RATE_LIMIT": "120/minute",
    "ROUTE_RATE_LIMIT": "30/minute",
    "DATABASE_STARTUP_ATTEMPTS": "3",
    "DATABASE_BACKOFF_MIN_SECONDS": "0.1",
    "DATABASE_BACKOFF_MAX_SECONDS": "1",
    "DATABASE_POOL_RECYCLE_SECONDS": "300",
}

for name, value in TEST_SETTINGS.items():
    os.environ.setdefault(name, value)

# Imported after the environment above: the settings object is built at import
# time, so anything that reaches it must already be set.
import base64  # noqa: E402
import hashlib  # noqa: E402
import hmac  # noqa: E402
import uuid  # noqa: E402
from datetime import datetime, timedelta, timezone  # noqa: E402

import pytest  # noqa: E402
import pytest_asyncio  # noqa: E402
from httpx import ASGITransport, AsyncClient  # noqa: E402
from sqlalchemy import text  # noqa: E402
from sqlalchemy.ext.asyncio import (  # noqa: E402
    async_sessionmaker,
    create_async_engine,
)

from app.api.main import app, get_db  # noqa: E402
from app.db.models import (  # noqa: E402
    Base,
    DeviceRegistrationDB,
    SipCredentialDB,
    TaskDefinitionDB,
    UserAccountDB,
    VirtualNumberDB,
)

PUBLIC_BASE_URL = "https://gateway.test.example"
PLIVO_AUTH_TOKEN = "test-plivo-auth-token"
PLIVO_SIP_DOMAIN = "phone.plivo.com"


def _database_url() -> str:
    """The database the end-to-end tests run against.

    Requires ``TEST_DATABASE_URL`` to be set explicitly rather than reusing
    ``DATABASE_URL``: these fixtures truncate every table between tests, and a
    test suite that will happily do that to whatever URL happens to be in the
    environment is one misconfigured shell away from being a production
    incident.
    """
    url = os.environ.get("TEST_DATABASE_URL", "")
    if "postgresql" not in url:
        pytest.skip(
            "The end-to-end call tests need a real PostgreSQL database "
            "(JSONB and native UUID columns). Set TEST_DATABASE_URL."
        )
    return url


def plivo_v3_signature(
    url: str, nonce: str, params: dict[str, str], method: str = "POST"
) -> str:
    """Sign a webhook the way Plivo signs it.

    Implemented here rather than imported from the SDK on purpose: the gateway
    *verifies* with the SDK, so a test that signed with the same code would
    prove only that one function is self-consistent. Signing independently is
    what makes a passing verification mean something.

    The signed string for POST is::

        url + "?" + "".join(key + value for key in sorted(params)) + "." + nonce

    with the ``"?"`` omitted when there are no parameters, and for GET it is
    ``url + "?" + "&".join(key=value ...) + "." + nonce``. The concatenation
    has no separators between pairs, which is the part a reimplementation
    always gets wrong.
    """
    if method.upper() == "GET":
        query = "&".join(f"{key}={value}" for key, value in sorted(params.items()))
        base = f"{url}?{query}" if query else url
    elif params:
        joined = "".join(f"{key}{value}" for key, value in sorted(params.items()))
        base = f"{url}?{joined}"
    else:
        base = url
    digest = hmac.new(
        PLIVO_AUTH_TOKEN.encode("utf-8"),
        f"{base}.{nonce}".encode("utf-8"),
        hashlib.sha256,
    ).digest()
    return base64.b64encode(digest).decode("utf-8")


class PlivoWebhookClient:
    """Posts provider callbacks the way Plivo would, signature and all."""

    def __init__(self, client: AsyncClient):
        self.client = client

    async def post(self, path: str, params: dict[str, str], *, sign: bool = True):
        headers = {}
        if sign:
            nonce = uuid.uuid4().hex
            headers = {
                "X-Plivo-Signature-V3-Nonce": nonce,
                "X-Plivo-Signature-V3": plivo_v3_signature(
                    f"{PUBLIC_BASE_URL}{path}", nonce, params
                ),
            }
        return await self.client.post(path, data=params, headers=headers)


#: The schema is created once per process. Tracked here rather than in a
#: session-scoped fixture because an asyncpg connection belongs to the event
#: loop that opened it, and pytest-asyncio gives each test its own loop — a
#: session-scoped engine hands function-scoped tests a connection from a loop
#: that is no longer running. One engine per test, one schema per process.
_SCHEMA_READY = False


@pytest_asyncio.fixture
async def engine():
    global _SCHEMA_READY
    eng = create_async_engine(_database_url(), echo=False)
    async with eng.begin() as conn:
        if not _SCHEMA_READY:
            await conn.run_sync(Base.metadata.drop_all)
            await conn.run_sync(Base.metadata.create_all)
            _SCHEMA_READY = True
        else:
            # TRUNCATE rather than drop/create: it is far faster, and it keeps
            # the schema the tests assert against fixed for the whole run.
            tables = ", ".join(
                f'"{table.name}"' for table in reversed(Base.metadata.sorted_tables)
            )
            await conn.execute(text(f"TRUNCATE {tables} RESTART IDENTITY CASCADE"))
    yield eng
    await eng.dispose()


@pytest_asyncio.fixture
async def session_maker(engine):
    """Sessions bound to the real database, against empty tables."""
    return async_sessionmaker(engine, expire_on_commit=False)


@pytest_asyncio.fixture
async def client(session_maker):
    """The real app, wired to the test database."""

    async def override_get_db():
        async with session_maker() as session:
            yield session

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url=PUBLIC_BASE_URL) as http:
        yield http
    app.dependency_overrides.clear()


@pytest_asyncio.fixture
async def plivo(client):
    return PlivoWebhookClient(client)


class Enrolled:
    """One provisioned user, as a real account would be set up."""

    def __init__(
        self,
        user_id: uuid.UUID,
        device_token: str,
        virtual_number: str,
        sip_username: str,
        sip_endpoint: str,
    ):
        self.user_id = user_id
        self.device_token = device_token
        self.virtual_number = virtual_number
        self.sip_username = sip_username
        self.sip_endpoint = sip_endpoint

    @property
    def auth(self) -> dict[str, str]:
        return {"X-Device-Token": self.device_token}


@pytest_asyncio.fixture
async def enrolled(session_maker) -> Enrolled:
    """A user with a number, SIP credentials, and a registered device row.

    Written straight to the database rather than through the admin endpoints,
    because provisioning is not what these tests are about — the call is.
    """
    user_id = uuid.uuid4()
    device_token = f"device_{uuid.uuid4().hex}"
    sip_username = "triplex_e2e_endpoint"
    sip_endpoint = f"sip:{sip_username}@{PLIVO_SIP_DOMAIN}"
    virtual_number = "+14155550100"

    async with session_maker() as session:
        session.add(
            UserAccountDB(
                id=user_id, email=f"{user_id}@example.test", phone_number="+14155550199"
            )
        )
        # Flushed before the rows that reference it: SIP credentials and
        # virtual numbers carry foreign keys to the account but no ORM
        # relationship, so the unit of work has nothing to order them by.
        await session.flush()
        session.add(
            VirtualNumberDB(
                number=virtual_number,
                number_type="permanent",
                assigned_user_id=user_id,
                provider="plivo",
                inbound_enabled=True,
                outbound_enabled=True,
            )
        )
        session.add(
            SipCredentialDB(
                user_id=user_id,
                provider="plivo",
                username=sip_username,
                password="not-a-real-password",
                domain=PLIVO_SIP_DOMAIN,
            )
        )
        session.add(
            DeviceRegistrationDB(
                user_id=user_id,
                device_token=device_token,
                sip_endpoint=sip_endpoint,
                ready=False,
                media_ready=False,
                last_heartbeat=datetime.now(timezone.utc),
            )
        )
        await session.commit()

    return Enrolled(user_id, device_token, virtual_number, sip_username, sip_endpoint)


@pytest_asyncio.fixture
def make_task(session_maker):
    """Creates a task row directly, for tests that start mid-flow."""

    async def _make(
        enrolled: Enrolled,
        destination: str = "+14155550188",
        task_type: str = "item_return",
        status: str = "pending",
    ) -> uuid.UUID:
        task_id = uuid.uuid4()
        async with session_maker() as session:
            session.add(
                TaskDefinitionDB(
                    id=task_id,
                    user_id=enrolled.user_id,
                    task_type=task_type,
                    destination_number=destination,
                    task_params={"product": "monitor", "desired_outcome": "refund"},
                    status=status,
                )
            )
            await session.commit()
        return task_id

    return _make


@pytest_asyncio.fixture
def age_device(session_maker):
    """Backdates a device's heartbeat, the way an hour of being switched off would."""

    async def _age(enrolled: Enrolled, seconds: int) -> None:
        async with session_maker() as session:
            await session.execute(
                text(
                    "UPDATE device_registrations SET last_heartbeat = :ts "
                    "WHERE device_token = :token"
                ),
                {
                    "ts": datetime.now(timezone.utc) - timedelta(seconds=seconds),
                    "token": enrolled.device_token,
                },
            )
            await session.commit()

    return _age
