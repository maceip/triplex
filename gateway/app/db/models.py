from datetime import datetime, timezone
from typing import Literal, Optional
from uuid import UUID, uuid4

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, Text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.dialects.postgresql import UUID as PGUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class UserAccountDB(Base):
    __tablename__ = "user_accounts"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    email: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    phone_number: Mapped[str] = mapped_column(String(20), nullable=False)
    consent_given: Mapped[bool] = mapped_column(Boolean, default=False)
    consent_ts: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )

    voice_profiles: Mapped[list["VoiceProfileDB"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    devices: Mapped[list["DeviceRegistrationDB"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    tasks: Mapped[list["TaskDefinitionDB"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class VoiceProfileDB(Base):
    __tablename__ = "voice_profiles"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        nullable=False,
    )
    version: Mapped[int] = mapped_column(Integer, default=1)
    encrypted_blob_ref: Mapped[str] = mapped_column(Text, nullable=False)
    encryption_key_id: Mapped[str] = mapped_column(String(64), nullable=False)
    synthesis_ready: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    # Consent evidence for the cloned voice. The spoken consent statement is
    # itself the reference sample, so the recording is the consent record.
    consent_statement: Mapped[Optional[str]] = mapped_column(Text)
    consent_recorded_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True)
    )
    reference_sha256: Mapped[Optional[str]] = mapped_column(String(64))
    reference_seconds: Mapped[Optional[int]] = mapped_column(Integer)
    # Where synthesis runs for this profile, recorded per placement rules.
    placement: Mapped[str] = mapped_column(String(32), default="REMOTE_TTS")
    revoked_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))

    user: Mapped["UserAccountDB"] = relationship(back_populates="voice_profiles")


class VirtualNumberDB(Base):
    __tablename__ = "virtual_numbers"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    number: Mapped[str] = mapped_column(
        String(20), unique=True, nullable=False, index=True
    )
    number_type: Mapped[Literal["permanent", "pooled"]] = mapped_column(
        String(20), default="permanent"
    )
    assigned_user_id: Mapped[Optional[UUID]] = mapped_column(
        PGUUID(as_uuid=True), ForeignKey("user_accounts.id", ondelete="SET NULL")
    )
    provider: Mapped[Literal["plivo", "chime"]] = mapped_column(
        String(20), default="plivo"
    )
    inbound_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    outbound_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )


class DeviceRegistrationDB(Base):
    __tablename__ = "device_registrations"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        nullable=False,
    )
    device_token: Mapped[str] = mapped_column(
        String(255), unique=True, nullable=False, index=True
    )
    sip_endpoint: Mapped[str] = mapped_column(String(255), nullable=False)
    push_token: Mapped[Optional[str]] = mapped_column(String(255))
    ready: Mapped[bool] = mapped_column(Boolean, default=False)
    last_heartbeat: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )

    user: Mapped["UserAccountDB"] = relationship(back_populates="devices")


class SipCredentialDB(Base):
    """Provider SIP endpoint credentials, admin-seeded per user.

    The device fetches these once registered; the gateway is the only place
    provider credentials live long-term (placement rules: numbers/credentials
    belong to the cloud gateway).
    """

    __tablename__ = "sip_credentials"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        unique=True,
        nullable=False,
    )
    provider: Mapped[str] = mapped_column(String(20), default="plivo")
    username: Mapped[str] = mapped_column(String(255), nullable=False)
    password: Mapped[str] = mapped_column(String(255), nullable=False)
    domain: Mapped[str] = mapped_column(String(255), nullable=False)
    realm: Mapped[Optional[str]] = mapped_column(String(255))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )


class TaskDefinitionDB(Base):
    __tablename__ = "task_definitions"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        nullable=False,
    )
    task_type: Mapped[str] = mapped_column(String(50), nullable=False, index=True)
    destination_number: Mapped[str] = mapped_column(String(20), nullable=False)
    task_params: Mapped[dict] = mapped_column(JSONB, default=dict)
    status: Mapped[Literal["pending", "active", "completed", "failed", "stopped"]] = (
        mapped_column(String(20), default="pending", index=True)
    )
    outcome: Mapped[Optional[str]] = mapped_column(Text)
    outcome_evidence: Mapped[Optional[dict]] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    started_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))
    completed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))

    user: Mapped["UserAccountDB"] = relationship(back_populates="tasks")


class OutboundRouteGrantDB(Base):
    """One-use authorization for a device-originated Plivo SIP call.

    The Android endpoint owns SIP signaling and media. This row only binds a
    short-lived grant to one typed task, destination, endpoint, and Plivo call
    UUID so the endpoint application cannot become an unrestricted dialer.
    """

    __tablename__ = "outbound_route_grants"

    task_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("task_definitions.id", ondelete="CASCADE"),
        primary_key=True,
    )
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    token_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), unique=True, nullable=False, default=uuid4
    )
    destination_number: Mapped[str] = mapped_column(String(20), nullable=False)
    caller_id: Mapped[str] = mapped_column(String(20), nullable=False)
    endpoint_username: Mapped[str] = mapped_column(String(255), nullable=False)
    sip_domain: Mapped[str] = mapped_column(String(255), nullable=False)
    expires_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    consumed_call_uuid: Mapped[Optional[str]] = mapped_column(String(64), unique=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    consumed_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))


class ScreeningSessionDB(Base):
    """Short-lived provider call held while Triplex asks why the caller called."""

    __tablename__ = "screening_sessions"

    call_uuid: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    caller_number: Mapped[str] = mapped_column(String(32), nullable=False)
    called_number: Mapped[str] = mapped_column(String(32), nullable=False)
    sip_endpoint: Mapped[str] = mapped_column(String(255), nullable=False)
    status: Mapped[str] = mapped_column(String(24), default="asking", index=True)
    transcript: Mapped[str] = mapped_column(Text, default="")
    confidence: Mapped[Optional[str]] = mapped_column(String(24))
    decision: Mapped[Optional[str]] = mapped_column(String(16))
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    decided_at: Mapped[Optional[datetime]] = mapped_column(DateTime(timezone=True))


class AuditLogDB(Base):
    __tablename__ = "audit_logs"

    id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True), primary_key=True, default=uuid4
    )
    user_id: Mapped[UUID] = mapped_column(
        PGUUID(as_uuid=True),
        ForeignKey("user_accounts.id", ondelete="SET NULL"),
        nullable=True,
    )
    event_type: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    event_data: Mapped[dict] = mapped_column(JSONB, default=dict)
    placement: Mapped[Optional[str]] = mapped_column(String(50))
    latency_ms: Mapped[Optional[int]] = mapped_column(Integer)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc), index=True
    )
