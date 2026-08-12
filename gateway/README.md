# Triplex Control Gateway

Phone-first cloud control API for local agent execution.

## Architecture

Per `UNIFICATION_PLAN.md`, the gateway handles:
- Device registration and authentication
- Plivo webhook routing XML generation
- Task/policy distribution
- Minimal audit logging

The gateway is **not** a media relay. Media flows directly from provider to Android.

## Quick Start

### Prerequisites
- Python 3.12+
- PostgreSQL 16+
- Docker and Docker Compose (optional)

### Local Development

```bash
# Create virtual environment
python -m venv .venv
source .venv/bin/activate  # or `.\.venv\Scripts\activate` on Windows

# Install dependencies
pip install -r requirements.txt

# Set database URL
export DATABASE_URL="postgresql+asyncpg://triplex:triplex@localhost:5432/triplex"

# Run with uvicorn
uvicorn app.api.main:app --reload --host 0.0.0.0 --port 8000
```

### Docker Deployment

```bash
docker-compose up -d
```

Gateway will be available at `http://localhost:8000`

## API Endpoints

### Health
- `GET /health` - Health check
- `GET /ready` - Database-backed readiness check

### Authentication
- `POST /auth/register` - Register user account

### Entitlements and line allocation
- `POST /entitlements/claim` - Verify Play/stub entitlement and allocate DID + SIP endpoint
- `GET /devices/line` - Assigned Triplex DID and SIP credentials
- `POST /admin/inventory/numbers` - Seed unassigned Plivo DIDs (`X-Admin-Key`)

### Device Management
- `POST /devices/register` - Register device (requires device token)
- `POST /devices/ready` - Set device ready status
- `GET /devices/status` - Get device connection status
- `GET /devices/sip-credentials` - Fetch SIP credentials (402 without entitlement when unprovisioned)

### Plivo Webhooks
- `POST /answer` - Signature-validated inbound and outbound endpoint routing
- `POST /plivo/answer` - Legacy alias for `/answer`
- `POST /plivo/hangup` - Log call hangup

### Task Management
- `POST /tasks` - Create new task
- `GET /tasks` - List tasks (optional status filter)
- `GET /tasks/{task_id}` - Get task details
- `POST /tasks/{task_id}/start` - Start task execution
- `POST /tasks/{task_id}/authorize-outbound` - One-use direct-SIP route grant
- `POST /tasks/{task_id}/stop` - Stop task execution

## Database Migrations

```bash
# Generate migration
alembic revision --autogenerate -m "description"

# Apply migrations
alembic upgrade head
```

## Production configuration

Production has no implicit operational defaults. `pydantic-settings` validates
the environment at process startup, and Compose uses `${NAME:?required}` so a
missing value fails before a container is replaced. The untracked `.env` must
define:

- database: `DATABASE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`;
- public/provider: `PUBLIC_BASE_URL`, `PLIVO_AUTH_TOKEN`, `PLIVO_SIP_DOMAIN`;
- route authorization: `OUTBOUND_ROUTE_SIGNING_KEY`,
  `OUTBOUND_ROUTE_TTL_SECONDS`;
- services/policy: `VOICE_SERVICE_URL`, `UNAVAILABLE_MESSAGE`, optional
  `ADMIN_API_KEY`;
- resilience: `DEFAULT_RATE_LIMIT`, `REGISTRATION_RATE_LIMIT`,
  `WEBHOOK_RATE_LIMIT`, `ROUTE_RATE_LIMIT`, `DATABASE_STARTUP_ATTEMPTS`,
  `DATABASE_BACKOFF_MIN_SECONDS`, `DATABASE_BACKOFF_MAX_SECONDS`, and
  `DATABASE_POOL_RECYCLE_SECONDS`;
- container binding/health: `GATEWAY_BIND_ADDRESS`, `GATEWAY_HOST_PORT`,
  `GATEWAY_HEALTH_INTERVAL`, `GATEWAY_HEALTH_TIMEOUT`,
  `GATEWAY_HEALTH_RETRIES`, `GATEWAY_HEALTH_START_PERIOD`,
  `POSTGRES_HEALTH_INTERVAL`, `POSTGRES_HEALTH_TIMEOUT`, and
  `POSTGRES_HEALTH_RETRIES`.

## Placement Visibility

All audit logs include placement field with values:
- `LOCAL` - Executed on device
- `REMOTE_ASR` - Cloud ASR fallback
- `REMOTE_REASONING` - Cloud reasoning fallback
- `REMOTE_TTS` - Cloud TTS fallback
- `REMOTE_AGENT` - Full cloud agent fallback

Latency in milliseconds recorded for performance analysis.

## Security

- All device tokens stored hashed
- Voice profiles encrypted at rest with device-specific keys
- HTTPS enforced in production
- Clear-text traffic only for local development
