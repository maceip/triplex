"""Validated deployment configuration for the public control gateway."""

from __future__ import annotations

import re

from pydantic import AnyHttpUrl, Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

_HOSTNAME = re.compile(
    r"^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+"
    r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
)


class GatewaySettings(BaseSettings):
    """All operational values are supplied by the deployment environment."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    database_url: str = Field(min_length=1)
    admin_api_key: SecretStr | None = None
    plivo_auth_id: SecretStr | None = None
    plivo_auth_token: SecretStr = Field(min_length=1)
    public_base_url: AnyHttpUrl
    plivo_sip_domain: str
    outbound_route_signing_key: SecretStr = Field(min_length=32)
    outbound_route_ttl_seconds: int = Field(ge=30, le=300)
    voice_service_url: AnyHttpUrl
    unavailable_message: str = Field(min_length=1, max_length=500)

    default_rate_limit: str = Field(min_length=3)
    registration_rate_limit: str = Field(min_length=3)
    webhook_rate_limit: str = Field(min_length=3)
    route_rate_limit: str = Field(min_length=3)

    #: How long a device's readiness claim is believed after its last check-in.
    #: Readiness is a statement about now: a phone that has been off since
    #: Tuesday still has ``ready = true`` in the row it wrote before it went
    #: away, and both inbound routing and outbound grants would otherwise trust
    #: it. Bounded to an hour because anything longer is not a heartbeat.
    device_heartbeat_ttl_seconds: int = Field(default=300, ge=30, le=3600)

    database_startup_attempts: int = Field(ge=1, le=12)
    database_backoff_min_seconds: float = Field(gt=0, le=30)
    database_backoff_max_seconds: float = Field(gt=0, le=120)
    database_pool_recycle_seconds: int = Field(ge=30, le=3600)

    enrollment_enabled: bool = Field(default=True)
    webauthn_rp_id: str | None = None
    webauthn_origin: str | None = None

    @field_validator("plivo_sip_domain")
    @classmethod
    def validate_sip_domain(cls, value: str) -> str:
        normalized = value.lower().rstrip(".")
        if not _HOSTNAME.fullmatch(normalized):
            raise ValueError("PLIVO_SIP_DOMAIN must be a valid hostname")
        return normalized

    @field_validator("database_backoff_max_seconds")
    @classmethod
    def validate_backoff_range(cls, value: float, info) -> float:
        minimum = info.data.get("database_backoff_min_seconds")
        if minimum is not None and value < minimum:
            raise ValueError(
                "DATABASE_BACKOFF_MAX_SECONDS must be greater than or equal to the minimum"
            )
        return value


settings = GatewaySettings()
