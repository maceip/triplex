import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Optional
from uuid import UUID, uuid4

import httpx

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile
from fastapi.responses import PlainTextResponse, Response
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from sqlalchemy import select

from ..db.models import Base, SipCredentialDB, VoiceProfileDB
from ..models.schemas import (
    AuditLog,
    DeviceRegistration,
    InboundPolicy,
    TaskDefinition,
    UserAccount,
    VoiceProfile,
)
from ..services import AuthService, RoutingService, TaskService, AuditService
from ..services.plivo_signature import verify_v3

DATABASE_URL = os.environ.get(
    "DATABASE_URL", "postgresql+asyncpg://triplex:triplex@localhost:5432/triplex"
)

engine = create_async_engine(DATABASE_URL, echo=False)
async_session_maker = async_sessionmaker(engine, expire_on_commit=False)


async def get_db():
    async with async_session_maker() as session:
        yield session


async def get_current_user_id(
    authorization: Optional[str] = Header(None, alias="Authorization"),
    device_token: Optional[str] = Header(None, alias="X-Device-Token"),
    db: AsyncSession = Depends(get_db),
) -> UUID:
    if device_token:
        auth_service = AuthService(db)
        user_id = await auth_service.validate_device_token(device_token)
        if user_id:
            return user_id
        raise HTTPException(status_code=401, detail="Invalid device token")
    
    raise HTTPException(status_code=401, detail="Missing authentication")


@asynccontextmanager
async def lifespan(app: FastAPI):
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield
    await engine.dispose()


app = FastAPI(title="Triplex Control Gateway", version="0.1.0", lifespan=lifespan)


class RegisterUserRequest(BaseModel):
    email: str
    phone_number: str


class EnrollmentResponse(BaseModel):
    user: UserAccount
    device_token: str


class SeedSipCredentialsRequest(BaseModel):
    email: str
    username: str
    password: str
    domain: str = "phone.plivo.com"
    realm: Optional[str] = None


class SipCredentialsResponse(BaseModel):
    provider: str
    username: str
    password: str
    domain: str
    realm: Optional[str] = None


class RegisterDeviceRequest(BaseModel):
    sip_endpoint: str
    push_token: Optional[str] = None


class CreateTaskRequest(BaseModel):
    task_type: str
    destination_number: str
    task_params: dict


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/auth/register", response_model=EnrollmentResponse)
async def register_user(
    request: RegisterUserRequest,
    db: AsyncSession = Depends(get_db),
):
    auth_service = AuthService(db)
    existing = await auth_service.get_user_by_email(request.email)
    if existing:
        raise HTTPException(status_code=400, detail="Email already registered")

    user = await auth_service.create_user(request.email, request.phone_number)
    # Mint the device token server-side so subsequent X-Device-Token calls
    # validate against a persisted registration.
    device_token = await auth_service.generate_device_token(user.id)
    await auth_service.register_device(
        user.id, device_token, sip_endpoint="", push_token=None
    )
    return EnrollmentResponse(
        user=UserAccount.model_validate(user, from_attributes=True),
        device_token=device_token,
    )


@app.post("/devices/register", response_model=DeviceRegistration)
async def register_device(
    request: RegisterDeviceRequest,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    auth_service = AuthService(db)
    device_token = await auth_service.generate_device_token(user_id)
    device = await auth_service.register_device(
        user_id, device_token, request.sip_endpoint, request.push_token
    )
    return device


@app.post("/devices/ready")
async def set_device_ready(
    ready: bool,
    user_id: UUID = Depends(get_current_user_id),
    device_token: str = Header(..., alias="X-Device-Token"),
    db: AsyncSession = Depends(get_db),
):
    auth_service = AuthService(db)
    success = await auth_service.set_device_ready(device_token, ready)
    if not success:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"ready": ready}


@app.get("/devices/status")
async def get_device_status(
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    routing_service = RoutingService(db)
    endpoint = await routing_service.get_device_endpoint(user_id)
    return {"ready": endpoint is not None, "sip_endpoint": endpoint}


@app.post("/admin/sip-credentials", response_model=SipCredentialsResponse)
async def seed_sip_credentials(
    request: SeedSipCredentialsRequest,
    admin_key: Optional[str] = Header(None, alias="X-Admin-Key"),
    db: AsyncSession = Depends(get_db),
):
    expected = os.environ.get("ADMIN_API_KEY")
    if not expected:
        raise HTTPException(status_code=503, detail="Admin API disabled")
    if admin_key != expected:
        raise HTTPException(status_code=403, detail="Invalid admin key")

    auth_service = AuthService(db)
    user = await auth_service.get_user_by_email(request.email)
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    result = await db.execute(
        select(SipCredentialDB).where(SipCredentialDB.user_id == user.id)
    )
    credential = result.scalar_one_or_none()
    if credential is None:
        credential = SipCredentialDB(user_id=user.id)
        db.add(credential)
    credential.username = request.username
    credential.password = request.password
    credential.domain = request.domain
    credential.realm = request.realm
    await db.commit()
    await db.refresh(credential)
    return SipCredentialsResponse(
        provider=credential.provider,
        username=credential.username,
        password=credential.password,
        domain=credential.domain,
        realm=credential.realm,
    )


@app.get("/devices/sip-credentials", response_model=SipCredentialsResponse)
async def get_sip_credentials(
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(SipCredentialDB).where(SipCredentialDB.user_id == user_id)
    )
    credential = result.scalar_one_or_none()
    if credential is None:
        raise HTTPException(status_code=404, detail="No SIP credentials provisioned")
    return SipCredentialsResponse(
        provider=credential.provider,
        username=credential.username,
        password=credential.password,
        domain=credential.domain,
        realm=credential.realm,
    )


VOICE_SERVICE_URL = os.environ.get("VOICE_SERVICE_URL", "http://host.docker.internal:8801")
# Synthesis placement for cloned voices. On-device (LOCAL) is preferred and
# the Android TtsModel seam is ready, but no maintained on-device zero-shot
# cloning runtime exists yet, so the offload is explicit and recorded.
VOICE_PLACEMENT = "REMOTE_TTS"
VOICE_PLACEMENT_REASON = (
    "no maintained on-device zero-shot cloning TTS for Android yet; "
    "synthesis offloaded to the voice service and recorded per placement rules"
)
MIN_REFERENCE_SECONDS = 3
MAX_REFERENCE_SECONDS = 30


class VoiceProfileStatus(BaseModel):
    profile_id: Optional[str] = None
    synthesis_ready: bool = False
    placement: str = VOICE_PLACEMENT
    placement_reason: str = VOICE_PLACEMENT_REASON
    reference_seconds: Optional[int] = None
    consent_recorded_at: Optional[datetime] = None


class VoicePreviewRequest(BaseModel):
    text: str


@app.post("/voice/profile", response_model=VoiceProfileStatus)
async def upload_voice_profile(
    consent_statement: str = Form(...),
    reference_seconds: int = Form(...),
    reference: UploadFile = File(...),
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Accept a consented reference recording and prepare a cloned voice.

    The uploaded recording is the user speaking the consent statement, so it
    is simultaneously the consent record and the cloning reference.
    """
    if not (MIN_REFERENCE_SECONDS <= reference_seconds <= MAX_REFERENCE_SECONDS):
        raise HTTPException(
            status_code=422,
            detail=f"reference must be {MIN_REFERENCE_SECONDS}-{MAX_REFERENCE_SECONDS} seconds",
        )
    if not consent_statement.strip():
        raise HTTPException(status_code=422, detail="consent_statement is required")

    payload = await reference.read()
    if not payload:
        raise HTTPException(status_code=422, detail="empty reference upload")

    result = await db.execute(
        select(VoiceProfileDB).where(
            VoiceProfileDB.user_id == user_id, VoiceProfileDB.revoked_at.is_(None)
        )
    )
    existing = result.scalar_one_or_none()
    profile_id = existing.id if existing is not None else uuid4()
    recorded_at = datetime.now(timezone.utc)

    try:
        async with httpx.AsyncClient(timeout=180.0) as client:
            response = await client.post(
                f"{VOICE_SERVICE_URL}/profiles/{profile_id}",
                data={
                    "speaker_id": str(user_id),
                    "reference_text": consent_statement,
                    "consent_id": f"spoken-consent-{profile_id}",
                    "consent_verified_at": recorded_at.isoformat(),
                },
                files={"reference": ("reference.wav", payload, "audio/wav")},
            )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=503, detail=f"voice service unreachable: {exc}") from exc
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=response.text)
    prepared = response.json()

    if existing is None:
        profile = VoiceProfileDB(
            id=profile_id,
            user_id=user_id,
            encrypted_blob_ref=f"voice-service://{profile_id}",
            encryption_key_id="voice-service-managed",
        )
        db.add(profile)
    else:
        profile = existing
    profile.synthesis_ready = bool(prepared.get("synthesis_ready"))
    profile.consent_statement = consent_statement
    profile.consent_recorded_at = recorded_at
    profile.reference_sha256 = prepared.get("reference_sha256")
    profile.reference_seconds = reference_seconds
    profile.placement = VOICE_PLACEMENT
    profile.revoked_at = None
    await db.commit()

    return VoiceProfileStatus(
        profile_id=str(profile_id),
        synthesis_ready=profile.synthesis_ready,
        reference_seconds=reference_seconds,
        consent_recorded_at=recorded_at,
    )


@app.get("/voice/profile", response_model=VoiceProfileStatus)
async def voice_profile_status(
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(VoiceProfileDB).where(
            VoiceProfileDB.user_id == user_id, VoiceProfileDB.revoked_at.is_(None)
        )
    )
    profile = result.scalar_one_or_none()
    if profile is None:
        return VoiceProfileStatus()
    return VoiceProfileStatus(
        profile_id=str(profile.id),
        synthesis_ready=profile.synthesis_ready,
        placement=profile.placement,
        reference_seconds=profile.reference_seconds,
        consent_recorded_at=profile.consent_recorded_at,
    )


@app.post("/voice/preview")
async def voice_preview(
    request: VoicePreviewRequest,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Synthesize a line in the user's cloned voice and return WAV bytes."""
    result = await db.execute(
        select(VoiceProfileDB).where(
            VoiceProfileDB.user_id == user_id, VoiceProfileDB.revoked_at.is_(None)
        )
    )
    profile = result.scalar_one_or_none()
    if profile is None or not profile.synthesis_ready:
        raise HTTPException(status_code=404, detail="No prepared voice profile")

    try:
        async with httpx.AsyncClient(timeout=180.0) as client:
            response = await client.post(
                f"{VOICE_SERVICE_URL}/profiles/{profile.id}/synthesize",
                json={"text": request.text},
            )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=503, detail=f"voice service unreachable: {exc}") from exc
    if response.status_code >= 400:
        raise HTTPException(status_code=response.status_code, detail=response.text)

    return Response(
        content=response.content,
        media_type="audio/wav",
        headers={"X-Placement": VOICE_PLACEMENT},
    )


@app.delete("/voice/profile", response_model=VoiceProfileStatus)
async def revoke_voice_profile(
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Revoke consent: drop the prepared voice and its reference material."""
    result = await db.execute(
        select(VoiceProfileDB).where(
            VoiceProfileDB.user_id == user_id, VoiceProfileDB.revoked_at.is_(None)
        )
    )
    profile = result.scalar_one_or_none()
    if profile is None:
        return VoiceProfileStatus()

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            await client.delete(f"{VOICE_SERVICE_URL}/profiles/{profile.id}")
    except httpx.HTTPError:
        # Local revocation still proceeds; the service copy is orphaned and
        # unreachable without a profile row.
        pass

    profile.revoked_at = datetime.now(timezone.utc)
    profile.synthesis_ready = False
    await db.commit()
    return VoiceProfileStatus()


PLIVO_AUTH_TOKEN = os.environ.get("PLIVO_AUTH_TOKEN", "")
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "").rstrip("/")
UNAVAILABLE_MESSAGE = os.environ.get(
    "UNAVAILABLE_MESSAGE",
    "Thanks for calling Triplex. The assistant is not available to take this "
    "call right now. Please try again later.",
)

logger = logging.getLogger("triplex.gateway")
# Uvicorn configures only its own loggers, so call routing decisions would
# otherwise never reach the container log.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger.setLevel(logging.INFO)


def _normalize_number(raw: str) -> str:
    """E.164 without the plus; Plivo sends bare digits, humans type '+'."""
    digits = "".join(character for character in raw if character.isdigit())
    return digits


def _require_plivo_signature(
    request: Request, path: str, params: dict[str, str] | None = None
) -> None:
    """Reject webhook calls that Plivo did not sign.

    Fails closed: with no auth token configured the endpoint refuses to act,
    rather than trusting whatever reaches a public URL.
    """
    if not PLIVO_AUTH_TOKEN or not PUBLIC_BASE_URL:
        raise HTTPException(
            status_code=503,
            detail="Webhook validation is not configured",
        )
    valid = verify_v3(
        auth_token=PLIVO_AUTH_TOKEN,
        url=f"{PUBLIC_BASE_URL}{path}",
        nonce=request.headers.get("X-Plivo-Signature-V3-Nonce"),
        signature_header=request.headers.get("X-Plivo-Signature-V3"),
        method=request.method,
        params=params,
    )
    if not valid:
        # Log the signing material (never the token) so a rejection can be
        # diagnosed without weakening the check.
        logger.warning(
            "signature rejected path=%s plivo_headers=%s",
            path,
            {
                name: value
                for name, value in request.headers.items()
                if name.lower().startswith("x-plivo")
            },
        )
        raise HTTPException(status_code=403, detail="Invalid Plivo signature")


@app.post("/answer", response_class=PlainTextResponse)
async def plivo_answer(
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    """Inbound call routing for the assigned Plivo number.

    Routes to the caller's registered Android endpoint when one is ready;
    otherwise answers with a truthful unavailable message. Media never
    transits this service.
    """
    form_data = await request.form()
    # V3 signs the POST parameters, so the body is read before validating.
    _require_plivo_signature(
        request, "/answer", {k: str(v) for k, v in form_data.items()}
    )
    called_number = _normalize_number(str(form_data.get("To", "")))
    caller_id = str(form_data.get("From", ""))
    call_uuid = str(form_data.get("CallUUID", ""))

    routing_service = RoutingService(db)
    xml_response, decision = await routing_service.generate_routing_xml(
        called_number, caller_id
    )
    if decision != "route_to_device":
        xml_response = routing_service.generate_unavailable_xml(UNAVAILABLE_MESSAGE)

    logger.info(
        "inbound call uuid=%s to=%s from=%s decision=%s",
        call_uuid, called_number, caller_id, decision,
    )
    return xml_response


@app.post("/hangup")
async def plivo_hangup(request: Request, db: AsyncSession = Depends(get_db)):
    form_data = await request.form()
    _require_plivo_signature(
        request, "/hangup", {k: str(v) for k, v in form_data.items()}
    )
    logger.info(
        "call ended uuid=%s duration=%s status=%s",
        form_data.get("CallUUID", ""),
        form_data.get("Duration", "0"),
        form_data.get("CallStatus", ""),
    )
    return {"status": "logged"}


# Legacy paths kept so a misconfigured application still reaches the handler.
@app.post("/plivo/answer", response_class=PlainTextResponse)
async def plivo_answer_legacy(request: Request, db: AsyncSession = Depends(get_db)):
    return await plivo_answer(request, db)


@app.post("/plivo/hangup")
async def plivo_hangup_legacy(request: Request, db: AsyncSession = Depends(get_db)):
    return await plivo_hangup(request, db)


@app.post("/tasks", response_model=TaskDefinition)
async def create_task(
    request: CreateTaskRequest,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    task_service = TaskService(db)
    task = await task_service.create_task(
        user_id, request.task_type, request.destination_number, request.task_params
    )
    return task


@app.get("/tasks", response_model=list[TaskDefinition])
async def list_tasks(
    status: Optional[str] = None,
    limit: int = Query(default=50, le=100),
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    task_service = TaskService(db)
    tasks = await task_service.list_user_tasks(user_id, status, limit)
    return tasks


@app.get("/tasks/{task_id}", response_model=TaskDefinition)
async def get_task(
    task_id: UUID,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    task_service = TaskService(db)
    task = await task_service.get_task(task_id)
    if not task or task.user_id != user_id:
        raise HTTPException(status_code=404, detail="Task not found")
    return task


@app.post("/tasks/{task_id}/start", response_model=TaskDefinition)
async def start_task(
    task_id: UUID,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    task_service = TaskService(db)
    task = await task_service.get_task(task_id)
    if not task or task.user_id != user_id:
        raise HTTPException(status_code=404, detail="Task not found")
    
    task = await task_service.start_task(task_id)
    if not task:
        raise HTTPException(status_code=400, detail="Task cannot be started")
    return task


@app.post("/tasks/{task_id}/stop", response_model=TaskDefinition)
async def stop_task(
    task_id: UUID,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    task_service = TaskService(db)
    task = await task_service.get_task(task_id)
    if not task or task.user_id != user_id:
        raise HTTPException(status_code=404, detail="Task not found")
    
    task = await task_service.stop_task(task_id)
    if not task:
        raise HTTPException(status_code=400, detail="Task cannot be stopped")
    return task
