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
