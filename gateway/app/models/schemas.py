from datetime import datetime, timezone
from typing import Annotated, Literal, Optional
from uuid import UUID, uuid4

from pydantic import BaseModel, Field


class UserAccount(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    email: str
    phone_number: str
    consent_given: bool = False
    consent_ts: Optional[datetime] = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class VoiceProfile(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    user_id: UUID
    version: int = 1
    encrypted_blob_ref: str
    encryption_key_id: str
    synthesis_ready: bool = False
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class VirtualNumber(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    number: str
    number_type: Literal["permanent", "pooled"]
    assigned_user_id: Optional[UUID] = None
    provider: Literal["plivo", "chime"] = "plivo"
    inbound_enabled: bool = True
    outbound_enabled: bool = True
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


TaskType = Literal["item_return", "appointment_modify", "reservation_update"]
TaskStatus = Literal["pending", "active", "completed", "failed", "stopped"]


class TaskDefinition(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    user_id: UUID
    task_type: TaskType
    destination_number: str
    task_params: dict
    status: TaskStatus = "pending"
    outcome: Optional[str] = None
    outcome_evidence: Optional[dict] = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None


class DeviceRegistration(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    user_id: UUID
    device_token: str
    sip_endpoint: str
    push_token: Optional[str] = None
    ready: bool = False
    last_heartbeat: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


FallbackPolicy = Literal[
    "cloud_agent",
    "message_take",
    "transfer",
    "unavailable_response",
    "reject"
]


class InboundPolicy(BaseModel):
    allowed_caller_patterns: list[str] = Field(default_factory=list)
    fallback_policy: FallbackPolicy = "reject"
    transfer_number: Optional[str] = None
    message_limit_seconds: int = 60


class AuditLog(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    user_id: UUID
    event_type: str
    event_data: dict
    placement: Optional[Literal["LOCAL", "REMOTE_ASR", "REMOTE_REASONING", "REMOTE_TTS", "REMOTE_AGENT"]] = None
    latency_ms: Optional[int] = None
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


AuthTokens = Annotated[str, Field(pattern=r"^Bearer .+")]
