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

### Authentication
- `POST /auth/register` - Register user account

### Device Management
- `POST /devices/register` - Register device (requires device token)
- `POST /devices/ready` - Set device ready status
- `GET /devices/status` - Get device connection status

### Plivo Webhooks
- `POST /plivo/answer` - Generate routing XML for inbound calls
- `POST /plivo/hangup` - Log call hangup

### Task Management
- `POST /tasks` - Create new task
- `GET /tasks` - List tasks (optional status filter)
- `GET /tasks/{task_id}` - Get task details
- `POST /tasks/{task_id}/start` - Start task execution
- `POST /tasks/{task_id}/stop` - Stop task execution

## Database Migrations

```bash
# Generate migration
alembic revision --autogenerate -m "description"

# Apply migrations
alembic upgrade head
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| DATABASE_URL | Required | PostgreSQL connection string |

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
