from base64 import urlsafe_b64encode
import os
from datetime import datetime, timedelta, timezone
from typing import Optional
from uuid import UUID

from fastapi import Depends, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from webauthn import (
    generate_registration_options,
    verify_registration_response,
    generate_authentication_options,
    verify_authentication_response,
    options_to_json,
)
from webauthn.helpers.structs import (
    AuthenticatorSelectionCriteria,
    AuthenticatorAttachment,
    UserVerificationRequirement,
    PublicKeyCredentialDescriptor,
)
from webauthn.helpers.cose import COSEAlgorithmIdentifier

from ..config import settings
from ..db.models import (
    UserAccountDB,
    PasskeyCredentialDB,
    WebAuthnChallengeDB,
)


class RegistrationStartRequest(BaseModel):
    email: str


class RegistrationStartResponse(BaseModel):
    challenge_id: str
    options: str


class RegistrationFinishRequest(BaseModel):
    challenge_id: str
    credential: dict
    email: str


class LoginStartRequest(BaseModel):
    email: str


class LoginStartResponse(BaseModel):
    challenge_id: str
    options: str


class LoginFinishRequest(BaseModel):
    challenge_id: str
    credential: dict


def _generate_challenge() -> str:
    return urlsafe_b64encode(os.urandom(32)).decode('utf-8').rstrip('=')


def _get_rp_id() -> str:
    return settings.webauthn_rp_id or settings.public_base_url.host


def _get_origin() -> str:
    return settings.webauthn_origin or str(settings.public_base_url).rstrip('/')


async def registration_start(
    request: Request,
    data: RegistrationStartRequest,
    db: AsyncSession,
):
    if not settings.enrollment_enabled:
        raise HTTPException(status_code=403, detail="Enrollment is closed")

    challenge = _generate_challenge()
    challenge_id = str(UUID(int=int.from_bytes(os.urandom(16), 'big')))

    user_id = UUID(int=int.from_bytes(os.urandom(16), 'big'))

    options = generate_registration_options(
        rp_id=_get_rp_id(),
        rp_name="Triplex Gateway",
        user_id=user_id.bytes,
        user_name=data.email,
        user_display_name=data.email,
        authenticator_selection=AuthenticatorSelectionCriteria(
            authenticator_attachment=AuthenticatorAttachment.PLATFORM,
            user_verification=UserVerificationRequirement.REQUIRED,
        ),
        supported_pub_key_algs=[
            COSEAlgorithmIdentifier.ECDSA_SHA_256,
            COSEAlgorithmIdentifier.RSASSA_PKCS1_v1_5_SHA_256,
        ],
    )

    challenge_record = WebAuthnChallengeDB(
        id=challenge_id,
        challenge=challenge,
        email=data.email,
        challenge_type="registration",
        expires_at=datetime.now(timezone.utc) + timedelta(seconds=60),
    )
    db.add(challenge_record)
    await db.commit()

    options_json = options_to_json(options)
    
    return RegistrationStartResponse(
        challenge_id=challenge_id,
        options=options_json,
    )


async def registration_finish(
    request: Request,
    data: RegistrationFinishRequest,
    db: AsyncSession,
):
    if not settings.enrollment_enabled:
        raise HTTPException(status_code=403, detail="Enrollment is closed")

    result = await db.execute(
        select(WebAuthnChallengeDB).where(
            WebAuthnChallengeDB.id == data.challenge_id,
            WebAuthnChallengeDB.challenge_type == "registration",
            WebAuthnChallengeDB.email == data.email,
            WebAuthnChallengeDB.expires_at > datetime.now(timezone.utc),
        )
    )
    challenge_record = result.scalar_one_or_none()

    if not challenge_record:
        raise HTTPException(status_code=400, detail="Invalid or expired challenge")

    try:
        verification = verify_registration_response(
            credential=data.credential,
            expected_challenge=challenge_record.challenge.encode('utf-8'),
            expected_origin=_get_origin(),
            expected_rp_id=_get_rp_id(),
            require_user_verification=True,
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Verification failed: {str(e)}")

    result = await db.execute(
        select(UserAccountDB).where(UserAccountDB.email == data.email)
    )
    user = result.scalar_one_or_none()

    if not user:
        user = UserAccountDB(
            email=data.email,
            phone_number="pending",
            consent_given=True,
            consent_ts=datetime.now(timezone.utc),
        )
        db.add(user)
        await db.flush()

    credential_record = PasskeyCredentialDB(
        user_id=user.id,
        credential_id=urlsafe_b64encode(verification.credential_id).decode('utf-8').rstrip('='),
        public_key=urlsafe_b64encode(verification.credential_public_key).decode('utf-8').rstrip('='),
        sign_count=verification.sign_count,
        transports="[]",
        device_type="single_device",
        backed_up=False,
    )
    db.add(credential_record)

    await db.delete(challenge_record)
    await db.commit()

    return {"status": "ok", "user_id": str(user.id)}


async def login_start(
    request: Request,
    data: LoginStartRequest,
    db: AsyncSession,
):
    result = await db.execute(
        select(UserAccountDB).where(UserAccountDB.email == data.email)
    )
    user = result.scalar_one_or_none()

    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    result = await db.execute(
        select(PasskeyCredentialDB).where(PasskeyCredentialDB.user_id == user.id)
    )
    credentials = result.scalars().all()

    if not credentials:
        raise HTTPException(status_code=404, detail="No passkey registered")

    allow_credentials = [
        PublicKeyCredentialDescriptor(
            id=urlsafe_b64encode(cred.credential_id.encode()).decode('utf-8').rstrip('=').encode()
        )
        for cred in credentials
    ]

    challenge = _generate_challenge()
    challenge_id = str(UUID(int=int.from_bytes(os.urandom(16), 'big')))

    options = generate_authentication_options(
        rp_id=_get_rp_id(),
        allow_credentials=allow_credentials,
        user_verification=UserVerificationRequirement.REQUIRED,
    )

    challenge_record = WebAuthnChallengeDB(
        id=challenge_id,
        challenge=challenge,
        user_id=user.id,
        challenge_type="authentication",
        expires_at=datetime.now(timezone.utc) + timedelta(seconds=60),
    )
    db.add(challenge_record)
    await db.commit()

    options_json = options_to_json(options)

    return LoginStartResponse(
        challenge_id=challenge_id,
        options=options_json,
    )


async def login_finish(
    request: Request,
    data: LoginFinishRequest,
    db: AsyncSession,
):
    result = await db.execute(
        select(WebAuthnChallengeDB).where(
            WebAuthnChallengeDB.id == data.challenge_id,
            WebAuthnChallengeDB.challenge_type == "authentication",
            WebAuthnChallengeDB.expires_at > datetime.now(timezone.utc),
        )
    )
    challenge_record = result.scalar_one_or_none()

    if not challenge_record:
        raise HTTPException(status_code=400, detail="Invalid or expired challenge")

    result = await db.execute(
        select(PasskeyCredentialDB).where(
            PasskeyCredentialDB.user_id == challenge_record.user_id
        )
    )
    credentials = result.scalars().all()

    credential_id_b64 = data.credential.get('id', '')
    
    stored_credential = None
    for cred in credentials:
        if cred.credential_id == credential_id_b64:
            stored_credential = cred
            break

    if not stored_credential:
        raise HTTPException(status_code=400, detail="Credential not found")

    try:
        verification = verify_authentication_response(
            credential=data.credential,
            expected_challenge=challenge_record.challenge.encode('utf-8'),
            expected_origin=_get_origin(),
            expected_rp_id=_get_rp_id(),
            credential_public_key=stored_credential.public_key.encode('utf-8'),
            credential_current_sign_count=stored_credential.sign_count,
            require_user_verification=True,
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Verification failed: {str(e)}")

    stored_credential.sign_count = verification.new_sign_count
    stored_credential.last_used_at = datetime.now(timezone.utc)

    await db.delete(challenge_record)
    await db.commit()

    return {
        "status": "ok",
        "user_id": str(challenge_record.user_id),
    }
