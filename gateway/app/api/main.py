import logging
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Literal, Optional
from uuid import UUID, uuid4

import httpx
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    Request,
    UploadFile,
)
from fastapi.responses import FileResponse, PlainTextResponse, Response
from pydantic import BaseModel, field_validator
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.middleware import SlowAPIMiddleware
from slowapi.util import get_remote_address
from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from tenacity import (
    before_sleep_log,
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
)

from ..config import settings
from ..db.models import Base, ScreeningSessionDB, SipCredentialDB, VoiceProfileDB
from ..db.schema_sync import sync_additive_columns
from ..models.schemas import (
    DeviceRegistration,
    TaskDefinition,
    TaskType,
    UserAccount,
)
from ..services import AuditService, AuthService, RoutingService, TaskService
from ..services.routing import InboundDecision
from .webauthn import (
    RegistrationStartRequest,
    RegistrationStartResponse,
    RegistrationFinishRequest,
    LoginStartRequest,
    LoginStartResponse,
    LoginFinishRequest,
    registration_start,
    registration_finish,
    login_start,
    login_finish,
)
from ..services.plivo_signature import verify_v3
from ..services.route_grants import (
    OutboundRouteService,
    RouteGrantError,
    normalize_e164,
)

logger = logging.getLogger("triplex.gateway")
PROMPT_DIRECTORY = Path(__file__).resolve().parent.parent / "assets" / "prompts"

engine = create_async_engine(
    settings.database_url,
    echo=False,
    pool_pre_ping=True,
    pool_recycle=settings.database_pool_recycle_seconds,
)
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


@retry(
    retry=retry_if_exception_type((OSError, SQLAlchemyError)),
    wait=wait_exponential(
        multiplier=settings.database_backoff_min_seconds,
        min=settings.database_backoff_min_seconds,
        max=settings.database_backoff_max_seconds,
    ),
    stop=stop_after_attempt(settings.database_startup_attempts),
    before_sleep=before_sleep_log(logger, logging.WARNING),
    reraise=True,
)
async def _initialize_database() -> None:
    """Create missing tables, then add missing columns to the ones that exist.

    The second half matters as much as the first. `create_all` skips a table it
    finds, so a column added to a model never reaches a database that already
    has that table — and every ORM query then names a column the database does
    not have. See `app.db.schema_sync` for why this is narrow and what should
    replace it.
    """
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        result = await conn.run_sync(
            lambda sync_conn: sync_additive_columns(sync_conn, Base.metadata)
        )
    for change in result.applied:
        logger.warning("schema updated: added %s.%s", change.table, change.column)
    for change in result.unsafe:
        logger.error(
            "schema is behind the models: %s.%s must be added by hand",
            change.table,
            change.column,
        )


@asynccontextmanager
async def lifespan(app: FastAPI):
    await _initialize_database()
    yield
    await engine.dispose()


app = FastAPI(title="Triplex Control Gateway", version="0.1.0", lifespan=lifespan)
limiter = Limiter(
    key_func=get_remote_address,
    default_limits=[settings.default_rate_limit],
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)
app.add_middleware(SlowAPIMiddleware)


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
    domain: str
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
    task_type: TaskType
    destination_number: str
    task_params: dict

    @field_validator("task_type", mode="before")
    @classmethod
    def normalize_task_type(cls, value: str) -> str:
        return value.lower() if isinstance(value, str) else value

    @field_validator("destination_number")
    @classmethod
    def validate_destination(cls, value: str) -> str:
        return normalize_e164(value)


class AuthorizeOutboundRequest(BaseModel):
    destination_number: str


class OutboundRouteGrantResponse(BaseModel):
    task_id: UUID
    sip_uri: str
    header_name: str
    token: str
    expires_at: datetime
    expires_in_seconds: int


class ScreeningDecisionRequest(BaseModel):
    decision: Literal["accept", "decline", "agent"]
    automation_id: Optional[Literal["book_zoom", "explain_delay"]] = None


class ScreeningSessionResponse(BaseModel):
    call_uuid: str
    caller_number: str
    called_number: str
    status: str
    transcript: str
    confidence: Optional[str] = None
    decision: Optional[str] = None
    created_at: datetime
    updated_at: datetime


@app.get("/health")
@limiter.exempt
async def health():
    return {"status": "ok"}


@app.get("/api/auth/telemetry-key")
async def get_telemetry_key(
    user_id: UUID = Depends(get_current_user_id),
):
    """Return API key for telemetry after authentication."""
    return {"api_key": "Gnlw4HbXdmEHbj12db1BDEOUgBh4s9T3+J3Y+L9Z1Tw="}


@app.post("/api/auth/register/start")
async def auth_register_start(
    request: Request,
    data: RegistrationStartRequest,
    db: AsyncSession = Depends(get_db),
):
    return await registration_start(request, data, db)


@app.post("/api/auth/register/finish")
async def auth_register_finish(
    request: Request,
    data: RegistrationFinishRequest,
    db: AsyncSession = Depends(get_db),
):
    return await registration_finish(request, data, db)


@app.post("/api/auth/login/start")
async def auth_login_start(
    request: Request,
    data: LoginStartRequest,
    db: AsyncSession = Depends(get_db),
):
    return await login_start(request, data, db)


@app.post("/api/auth/login/finish")
async def auth_login_finish(
    request: Request,
    data: LoginFinishRequest,
    db: AsyncSession = Depends(get_db),
):
    return await login_finish(request, data, db)


@app.get("/prompts/{prompt_name}.wav", include_in_schema=False)
@limiter.exempt
async def prompt_audio(
    prompt_name: Literal[
        "screening-intro",
        "screening-hold",
        "screening-decline",
        "book-zoom",
        "explain-delay",
        "automation-complete",
    ],
):
    return FileResponse(
        PROMPT_DIRECTORY / f"{prompt_name}.wav",
        media_type="audio/wav",
    )


@app.get("/ready")
@limiter.exempt
async def ready(db: AsyncSession = Depends(get_db)):
    try:
        await db.execute(text("SELECT 1"))
    except SQLAlchemyError as error:
        raise HTTPException(status_code=503, detail="Database unavailable") from error
    return {"status": "ready"}


@app.post("/auth/register", response_model=EnrollmentResponse)
@limiter.limit(settings.registration_rate_limit)
async def register_user(
    request: Request,
    payload: RegisterUserRequest,
    db: AsyncSession = Depends(get_db),
):
    auth_service = AuthService(db)
    existing = await auth_service.get_user_by_email(payload.email)
    if existing:
        raise HTTPException(status_code=400, detail="Email already registered")

    user = await auth_service.create_user(payload.email, payload.phone_number)
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
    device_token: str = Header(..., alias="X-Device-Token"),
    db: AsyncSession = Depends(get_db),
):
    """Attach this phone's SIP endpoint to the token it is already using.

    The token is not rotated here. It used to be, and the app never learned
    the new one — so the endpoint went onto a row nobody would ever mark ready,
    and readiness went onto a row with no endpoint. Registration updates the
    caller's own row.
    """
    if not request.sip_endpoint.strip():
        raise HTTPException(status_code=422, detail="sip_endpoint must not be empty")
    auth_service = AuthService(db)
    device = await auth_service.register_device(
        user_id, device_token, request.sip_endpoint.strip(), request.push_token
    )
    return device


@app.post("/devices/ready")
async def set_device_ready(
    ready: bool,
    media_ready: bool = Query(
        default=False,
        description=(
            "The device can carry the caller's audio, not merely accept the "
            "INVITE. Setting this is what moves an inbound call from provider "
            "screening to a direct bridge, so it defaults to false: a device "
            "that does not say it can take media does not get handed any."
        ),
    ),
    user_id: UUID = Depends(get_current_user_id),
    device_token: str = Header(..., alias="X-Device-Token"),
    db: AsyncSession = Depends(get_db),
):
    auth_service = AuthService(db)
    success = await auth_service.set_device_ready(
        device_token, ready, media_ready=media_ready
    )
    if not success:
        raise HTTPException(status_code=404, detail="Device not found")
    return {"ready": ready, "media_ready": ready and media_ready}


@app.get("/devices/status")
async def get_device_status(
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    route = await _routing_service(db).get_device_route(user_id)
    return {
        "ready": route is not None,
        "sip_endpoint": route.sip_endpoint if route else None,
        # Reported separately so the app can say, on screen, which of the two
        # products the next inbound call will get: a bridged agent call or a
        # screened one.
        "media_ready": bool(route and route.media_ready),
        "last_heartbeat": route.last_heartbeat if route else None,
    }


@app.post("/admin/sip-credentials", response_model=SipCredentialsResponse)
async def seed_sip_credentials(
    request: SeedSipCredentialsRequest,
    admin_key: Optional[str] = Header(None, alias="X-Admin-Key"),
    db: AsyncSession = Depends(get_db),
):
    configured_admin_key = settings.admin_api_key
    if configured_admin_key is None:
        raise HTTPException(status_code=503, detail="Admin API disabled")
    if admin_key != configured_admin_key.get_secret_value():
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


VOICE_SERVICE_URL = str(settings.voice_service_url).rstrip("/")
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
        raise HTTPException(
            status_code=503, detail=f"voice service unreachable: {exc}"
        ) from exc
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
        raise HTTPException(
            status_code=503, detail=f"voice service unreachable: {exc}"
        ) from exc
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


# Uvicorn configures only its own loggers, so call routing decisions would
# otherwise never reach the container log.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger.setLevel(logging.INFO)

# Initialize telemetry client for Azure Log Analytics
try:
    import sys
    sys.path.insert(0, '/Users/mac/triplex-analytics/python/triplex-telemetry/src')
    from triplex_telemetry import TelemetryClient, TelemetryConfig, setup_logging
    
    telemetry_config = TelemetryConfig(
        ingest_url="https://func-triplex-ingest-production.azurewebsites.net",
        api_key="Gnlw4HbXdmEHbj12db1BDEOUgBh4s9T3+J3Y+L9Z1Tw=",
        source="gateway"
    )
    telemetry_client = TelemetryClient.initialize(telemetry_config)
    setup_logging(telemetry_client, level=logging.INFO)
    logger.info("Telemetry client initialized successfully")
except Exception as e:
    logger.warning(f"Failed to initialize telemetry client: {e}")



def _normalize_number(raw: str) -> str:
    """E.164 without the plus; Plivo sends bare digits, humans type '+'."""
    digits = "".join(character for character in raw if character.isdigit())
    return digits


def _form_value(form_data, name: str) -> str:
    """Read Plivo form keys case-insensitively without logging grant values."""
    expected = name.casefold()
    for key, value in form_data.items():
        if str(key).casefold() == expected:
            return str(value)
    return ""


def _screening_response(session: ScreeningSessionDB) -> ScreeningSessionResponse:
    return ScreeningSessionResponse(
        call_uuid=session.call_uuid,
        caller_number=session.caller_number,
        called_number=session.called_number,
        status=session.status,
        transcript=session.transcript,
        confidence=session.confidence,
        decision=session.decision,
        created_at=session.created_at,
        updated_at=session.updated_at,
    )


async def _transfer_screening_call(call_uuid: str, route_path: str) -> None:
    configured_auth_id = settings.plivo_auth_id
    if configured_auth_id is None:
        raise HTTPException(status_code=503, detail="Plivo call control is not configured")
    auth_id = configured_auth_id.get_secret_value()
    route_url = f"{str(settings.public_base_url).rstrip('/')}{route_path}"
    api_url = f"https://api.plivo.com/v1/Account/{auth_id}/Call/{call_uuid}/"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            response = await client.post(
                api_url,
                auth=(auth_id, settings.plivo_auth_token.get_secret_value()),
                data={
                    "legs": "aleg",
                    "aleg_url": route_url,
                    "aleg_method": "POST",
                },
            )
    except httpx.HTTPError as error:
        raise HTTPException(status_code=503, detail="Plivo call control unavailable") from error
    if response.status_code >= 400:
        logger.error(
            "screening transfer failed uuid=%s route=%s status=%s",
            call_uuid,
            route_path,
            response.status_code,
        )
        raise HTTPException(status_code=502, detail="Plivo rejected the call decision")


def _outbound_route_service(db: AsyncSession) -> OutboundRouteService:
    return OutboundRouteService(
        db=db,
        signing_key=settings.outbound_route_signing_key.get_secret_value(),
        ttl_seconds=settings.outbound_route_ttl_seconds,
        sip_domain=settings.plivo_sip_domain,
        device_heartbeat_ttl_seconds=settings.device_heartbeat_ttl_seconds,
    )


def _routing_service(db: AsyncSession) -> RoutingService:
    return RoutingService(
        db, heartbeat_ttl_seconds=settings.device_heartbeat_ttl_seconds
    )


def _require_plivo_signature(
    request: Request, path: str, params: dict[str, str] | None = None
) -> None:
    """Reject webhook calls that Plivo did not sign.

    Fails closed: with no auth token configured the endpoint refuses to act,
    rather than trusting whatever reaches a public URL.
    """
    valid = verify_v3(
        auth_token=settings.plivo_auth_token.get_secret_value(),
        url=f"{str(settings.public_base_url).rstrip('/')}{path}",
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
@limiter.limit(settings.webhook_rate_limit)
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
        request, str(request.url.path), {k: str(v) for k, v in form_data.items()}
    )
    direction = str(form_data.get("Direction", "inbound")).casefold()
    token = _form_value(form_data, OutboundRouteService.HEADER_NAME)
    # Plivo labels authenticated SIP-endpoint calls as inbound to the attached
    # application. A valid one-use grant, not that provider direction label,
    # is the authoritative outbound discriminator.
    if token:

        endpoint_candidates = [
            _form_value(form_data, "From"),
            _form_value(form_data, "SIP-H-From"),
            _form_value(form_data, "SIP-H-P-Asserted-Identity"),
        ]
        try:
            authorization = await _outbound_route_service(db).consume(
                token=token,
                provider_call_uuid=_form_value(form_data, "CallUUID"),
                destination_value=_form_value(form_data, "To"),
                endpoint_candidates=endpoint_candidates,
            )
        except RouteGrantError as error:
            logger.warning(
                "outbound call rejected uuid=%s reason=%s",
                _form_value(form_data, "CallUUID"),
                str(error),
            )
            raise HTTPException(status_code=403, detail=str(error)) from error

        await AuditService(db).log(
            authorization.user_id,
            "outbound_route_consumed",
            {
                "task_id": str(authorization.task_id),
                "call_uuid": authorization.provider_call_uuid,
                "destination": authorization.destination_number,
                "transport": "DIRECT_SIP",
            },
        )
        logger.info(
            "outbound call authorized uuid=%s task=%s to=%s",
            authorization.provider_call_uuid,
            authorization.task_id,
            authorization.destination_number,
        )
        xml_response = RoutingService(db).generate_outbound_dial_xml(
            authorization.destination_number,
            authorization.caller_id,
        )
        return Response(content=xml_response, media_type="application/xml")
    if direction == "outbound":
        raise HTTPException(status_code=403, detail="Missing outbound route grant")

    called_number = _normalize_number(str(form_data.get("To", "")))
    caller_id = str(form_data.get("From", ""))
    call_uuid = str(form_data.get("CallUUID", ""))

    routing_service = _routing_service(db)
    routing = await routing_service.decide_inbound(called_number)
    decision, user_id, route = routing.decision, routing.user_id, routing.route

    # A provider callback with no call UUID cannot be tracked through a
    # screening session, so it is not screened — it is answered honestly.
    if decision is InboundDecision.SCREEN_CALLER and not call_uuid:
        decision = InboundDecision.UNAVAILABLE

    if decision is InboundDecision.BRIDGE_TO_DEVICE and route is not None:
        # The device says it can carry the caller's audio: hand the call over
        # and get out of the way. Nothing in this service is in the media path
        # from here, which is the whole point of the handoff.
        xml_response = routing_service.generate_bridge_to_device_xml(
            route.sip_endpoint, caller_id
        )
        if user_id is not None:
            await AuditService(db).log(
                user_id,
                "inbound_bridged_to_device",
                {
                    "call_uuid": call_uuid,
                    "sip_endpoint": route.sip_endpoint,
                    "called_number": called_number,
                },
            )
    elif decision is InboundDecision.SCREEN_CALLER and route is not None and user_id is not None:
        session = await db.get(ScreeningSessionDB, call_uuid)
        if session is None:
            session = ScreeningSessionDB(
                call_uuid=call_uuid,
                user_id=user_id,
                caller_number=caller_id,
                called_number=called_number,
                sip_endpoint=route.sip_endpoint,
                status="asking",
                transcript="",
                expires_at=datetime.now(timezone.utc) + timedelta(minutes=5),
            )
            db.add(session)
        else:
            session.sip_endpoint = route.sip_endpoint
            session.expires_at = datetime.now(timezone.utc) + timedelta(minutes=5)
        await db.commit()
        xml_response = routing_service.generate_screening_prompt_xml(
            call_uuid, str(settings.public_base_url)
        )
    elif decision is InboundDecision.UNASSIGNED:
        # Not one of our numbers. Nothing to say to the caller, and nothing to
        # be billed for answering.
        xml_response = routing_service.generate_reject_xml()
    else:
        xml_response = routing_service.generate_unavailable_xml(
            settings.unavailable_message
        )
        decision = InboundDecision.UNAVAILABLE

    logger.info(
        "inbound call uuid=%s to=%s from=%s decision=%s media_ready=%s",
        call_uuid,
        called_number,
        caller_id,
        decision.value,
        bool(route and route.media_ready),
    )
    return Response(content=xml_response, media_type="application/xml")


async def _verified_screening_form(request: Request) -> dict[str, str]:
    form_data = await request.form()
    params = {str(key): str(value) for key, value in form_data.items()}
    _require_plivo_signature(request, str(request.url.path), params)
    return params


@app.post("/screening/{call_uuid}/interim")
async def screening_interim(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    form_data = await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    stable = _form_value(form_data, "StableSpeech").strip()
    unstable = _form_value(form_data, "UnstableSpeech").strip()
    transcript = " ".join(part for part in (stable, unstable) if part).strip()
    if transcript:
        session.transcript = transcript[:4000]
        session.status = "asking"
        await db.commit()
    return {"status": "updated"}


@app.post("/screening/{call_uuid}/result", response_class=PlainTextResponse)
async def screening_result(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    form_data = await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    speech = _form_value(form_data, "Speech").strip()
    if speech:
        session.transcript = speech[:4000]
    elif not session.transcript:
        session.transcript = "The caller did not provide a reason."
    session.confidence = _form_value(form_data, "SpeechConfidenceScore")[:24] or None
    session.status = "ready"
    await db.commit()
    xml = RoutingService(db).generate_screening_wait_xml(
        call_uuid, str(settings.public_base_url)
    )
    return Response(content=xml, media_type="application/xml")


@app.post("/screening/{call_uuid}/hold", response_class=PlainTextResponse)
async def screening_hold(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    if session.decision is None:
        session.status = "holding"
        session.expires_at = datetime.now(timezone.utc) + timedelta(minutes=5)
        await db.commit()
    xml = RoutingService(db).generate_screening_hold_xml(
        call_uuid, str(settings.public_base_url)
    )
    return Response(content=xml, media_type="application/xml")


@app.post("/screening/{call_uuid}/connect", response_class=PlainTextResponse)
async def screening_connect(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    session.status = "connected"
    await db.commit()
    xml = RoutingService(db).generate_screening_connect_xml(
        session.sip_endpoint, session.caller_number
    )
    return Response(content=xml, media_type="application/xml")


@app.post("/screening/{call_uuid}/decline", response_class=PlainTextResponse)
async def screening_decline(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    await _verified_screening_form(request)
    return Response(
        content=RoutingService(db).generate_screening_decline_xml(
            str(settings.public_base_url)
        ),
        media_type="application/xml",
    )


@app.post("/screening/{call_uuid}/agent", response_class=PlainTextResponse)
async def screening_agent(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    session.status = "agent"
    await db.commit()
    return Response(
        content=RoutingService(db).generate_screening_automation_xml(
            call_uuid, str(settings.public_base_url), "book_zoom"
        ),
        media_type="application/xml",
    )


@app.post(
    "/screening/{call_uuid}/automation/{automation_id}",
    response_class=PlainTextResponse,
)
async def screening_automation(
    call_uuid: str,
    automation_id: Literal["book_zoom", "explain_delay"],
    request: Request,
    db: AsyncSession = Depends(get_db),
):
    await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    session.status = "agent"
    session.decision = automation_id
    await db.commit()
    return Response(
        content=RoutingService(db).generate_screening_automation_xml(
            call_uuid, str(settings.public_base_url), automation_id
        ),
        media_type="application/xml",
    )


@app.post("/screening/{call_uuid}/message-interim")
async def screening_message_interim(
    call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)
):
    form_data = await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    stable = _form_value(form_data, "StableSpeech").strip()
    unstable = _form_value(form_data, "UnstableSpeech").strip()
    speech = " ".join(part for part in (stable, unstable) if part).strip()
    if speech:
        first_reply = session.transcript.split("\nAdditional message:", 1)[0].rstrip()
        session.transcript = f"{first_reply}\nAdditional message: {speech}"[:4000]
        session.status = "agent"
        await db.commit()
    return {"status": "updated"}


@app.post("/screening/{call_uuid}/message", response_class=PlainTextResponse)
async def screening_message(call_uuid: str, request: Request, db: AsyncSession = Depends(get_db)):
    form_data = await _verified_screening_form(request)
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None:
        raise HTTPException(status_code=404, detail="Screening session not found")
    speech = _form_value(form_data, "Speech").strip()
    if speech:
        first_reply = session.transcript.split("\nAdditional message:", 1)[0].rstrip()
        session.transcript = f"{first_reply}\nAdditional message: {speech}"[:4000]
    session.status = "completed"
    await db.commit()
    closing_message = (
        "Thank you. I have the meeting details and will send them for confirmation. Goodbye."
        if session.decision == "book_zoom"
        else "Thank you. I will pass that along. Goodbye."
    )
    return Response(
        content=f'''<?xml version="1.0" encoding="UTF-8"?>
<Response><Speak language="en-US" voice="Polly.Joanna">{closing_message}</Speak><Hangup/></Response>''',
        media_type="application/xml",
    )


@app.get("/screening/active", response_model=Optional[ScreeningSessionResponse])
async def active_screening_session(
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(ScreeningSessionDB)
        .where(
            ScreeningSessionDB.user_id == user_id,
            ScreeningSessionDB.status.in_(
                ["asking", "ready", "holding", "agent", "connecting", "connected"]
            ),
            ScreeningSessionDB.expires_at > datetime.now(timezone.utc),
        )
        .order_by(ScreeningSessionDB.created_at.desc())
        .limit(1)
    )
    session = result.scalar_one_or_none()
    return _screening_response(session) if session is not None else None


@app.post(
    "/screening/{call_uuid}/decision", response_model=ScreeningSessionResponse
)
async def decide_screening_session(
    call_uuid: str,
    payload: ScreeningDecisionRequest,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    session = await db.get(ScreeningSessionDB, call_uuid)
    if session is None or session.user_id != user_id:
        raise HTTPException(status_code=404, detail="Screening session not found")
    if session.status not in {"asking", "ready", "holding"}:
        raise HTTPException(status_code=409, detail="Call has already been decided")

    automation_id = payload.automation_id or "book_zoom"
    target = {
        "accept": "connect",
        "decline": "decline",
        "agent": f"automation/{automation_id}",
    }[payload.decision]
    await _transfer_screening_call(call_uuid, f"/screening/{call_uuid}/{target}")
    session.decision = automation_id if payload.decision == "agent" else payload.decision
    session.decided_at = datetime.now(timezone.utc)
    session.status = {
        "accept": "connecting",
        "decline": "declined",
        "agent": "agent",
    }[payload.decision]
    await db.commit()
    await db.refresh(session)
    return _screening_response(session)


@app.post("/hangup")
async def plivo_hangup(request: Request, db: AsyncSession = Depends(get_db)):
    form_data = await request.form()
    _require_plivo_signature(
        request, str(request.url.path), {k: str(v) for k, v in form_data.items()}
    )
    call_uuid = str(form_data.get("CallUUID", ""))
    session = await db.get(ScreeningSessionDB, call_uuid) if call_uuid else None
    if session is not None and session.status not in {"declined", "completed"}:
        session.status = "completed"
        await db.commit()
    logger.info(
        "call ended uuid=%s duration=%s status=%s",
        form_data.get("CallUUID", ""),
        form_data.get("Duration", "0"),
        form_data.get("CallStatus", ""),
    )
    return {"status": "logged"}


# Legacy paths kept so a misconfigured application still reaches the handler.
@app.post("/plivo/answer", response_class=PlainTextResponse)
@limiter.limit(settings.webhook_rate_limit)
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


@app.post(
    "/tasks/{task_id}/authorize-outbound",
    response_model=OutboundRouteGrantResponse,
)
@limiter.limit(settings.route_rate_limit)
async def authorize_outbound(
    request: Request,
    task_id: UUID,
    payload: AuthorizeOutboundRequest,
    user_id: UUID = Depends(get_current_user_id),
    db: AsyncSession = Depends(get_db),
):
    """Authorize one direct Android SIP call for one typed task.

    The response is consumed by PJSIP on Android. Plivo calls `/answer` with
    the grant header before connecting PSTN; no media or SIP signaling crosses
    this HTTPS endpoint.
    """
    try:
        route_service = _outbound_route_service(db)
        grant = await route_service.issue(
            user_id=user_id,
            task_id=task_id,
            requested_destination=payload.destination_number,
        )
    except RouteGrantError as error:
        status = 503 if "not configured" in str(error) else 409
        # Refusals are audited alongside grants. A phone that cannot dial says
        # only "the call could not be authorized" on screen, so without this
        # the reason — stale device, wrong destination, grant already spent —
        # exists nowhere anyone can read it after the fact.
        await AuditService(db).log(
            user_id,
            "outbound_route_refused",
            {
                "task_id": str(task_id),
                "destination": payload.destination_number,
                "reason": str(error),
                "status": status,
            },
        )
        logger.warning(
            "outbound authorization refused task=%s status=%s reason=%s",
            task_id,
            status,
            error,
        )
        raise HTTPException(status_code=status, detail=str(error)) from error

    await AuditService(db).log(
        user_id,
        "outbound_route_issued",
        {
            "task_id": str(grant.task_id),
            "destination": grant.destination_number_for_audit,
            "expires_at": grant.expires_at.isoformat(),
            "ttl_seconds": route_service.ttl_seconds,
        },
    )
    logger.info(
        "outbound route issued task=%s expires_at=%s",
        grant.task_id,
        grant.expires_at.isoformat(),
    )

    return OutboundRouteGrantResponse(
        task_id=grant.task_id,
        sip_uri=grant.sip_uri,
        header_name=grant.header_name,
        token=grant.token,
        expires_at=grant.expires_at,
        expires_in_seconds=route_service.ttl_seconds,
    )


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
