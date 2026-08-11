# Passkey Enrollment Guide

## What Was Implemented

A complete WebAuthn passkey system allowing passwordless authentication with Face ID, Touch ID, Windows Hello, or security keys.

## Files Added/Modified

**Backend:**
- `gateway/app/api/webauthn.py` - WebAuthn registration and authentication logic
- `gateway/app/db/models.py` - Added PasskeyCredentialDB and WebAuthnChallengeDB models
- `gateway/app/config.py` - Added ENROLLMENT_ENABLED flag
- `gateway/pyproject.toml` - Added webauthn library

**Frontend:**
- `gateway/app/static/enroll.html` - Passkey enrollment and login UI

**Endpoints:**
- `POST /api/auth/register/start` - Begin registration (returns challenge)
- `POST /api/auth/register/finish` - Complete registration with passkey
- `POST /api/auth/login/start` - Begin login (returns challenge)
- `POST /api/auth/login/finish` - Complete login with passkey signature

## How to Use

### 1. Start Gateway
```bash
cd ~/triplex/gateway
uvicorn app.api.main:app --reload --port 8000
```

### 2. Open Enrollment Page
```
http://localhost:8000/static/enroll.html
```

### 3. Register
1. Enter email address
2. Click "Create Passkey"
3. Browser prompts for biometric (Face ID, Touch ID, Windows Hello) or security key
4. Passkey created and stored on device
5. Enrollment complete!

### 4. Login Later
1. Enter same email
2. Click "Sign In with Passkey"
3. Browser asks for biometric verification
4. Authenticated!

## Close Enrollment for Production

Set environment variable:
```bash
ENROLLMENT_ENABLED=false
```

Or in `.env` file:
```
ENROLLMENT_ENABLED=false
```

Users will see "Enrollment is closed" message when trying to register.

## WebAuthn Configuration

Current settings use platform authenticator (built-in biometrics):
- Face ID (iOS/Mac)
- Touch ID (Mac)
- Windows Hello (Windows)
- Android Biometrics

To support cross-platform (security keys like YubiKey), modify `webauthn.py`:
```python
authenticator_selection=AuthenticatorSelectionCriteria(
    authenticator_attachment=AuthenticatorAttachment.CROSS_PLATFORM,
    ...
)
```

## Security Features

- Single-use challenges (60-second expiration)
- Sign count verification (clone detection)
- User verification required
- Credentials stored server-side, private keys never leave device
- HTTPS required (WebAuthn standard)

## Supported Browsers

- Safari (iOS, macOS)
- Chrome (Android, Windows, macOS)
- Edge (Windows)
- Firefox (limited support)

## Example Flow

```
Browser                          Gateway                 Database
  |                                |                        |
  |--- POST /register/start ------>|                        |
  |   {email: "user@example.com"}  |                        |
  |                                |--- Create challenge --->|
  |<-- {challenge_id, options} ----|                        |
  |                                |                        |
  |--- Create passkey (Face ID) ---|                        |
  |                                |                        |
  |--- POST /register/finish ----->|                        |
  |   {credential, challenge_id}   |                        |
  |                                |--- Verify credential ->|
  |                                |--- Store credential --->|
  |<-- {status: ok, user_id} -------|                        |
```

## Next Steps

1. Test enrollment locally at http://localhost:8000/static/enroll.html
2. Create 1-2 test accounts
3. Set ENROLLMENT_ENABLED=false for production
4. Deploy gateway with HTTPS (required for WebAuthn)
ffer passkey login as authentication method
