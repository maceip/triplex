#!/bin/bash
set -e

echo "🚀 Deploying passkey-enabled gateway to production..."

# Add WebAuthn env vars to production .env if not present
ssh root@78.141.219.102 << 'REMOTE_EOF'
cd /root/triplex/gateway

# Check if .env exists
if [ ! -f .env ]; then
    echo "ERROR: .env not found on server"
    exit 1
fi

# Add WebAuthn config
grep -q "WEBAUTHN_RP_ID" .env || cat >> .env << 'ENV_EOF'

# WebAuthn (Passkey) Configuration
WEBAUTHN_RP_ID=secure.build
WEBAUTHN_ORIGIN=https://bridge.secure.build
ENROLLMENT_ENABLED=true
ENV_EOF

echo "✅ Updated .env with WebAuthn config"

# Pull latest code
git pull

# Rebuild and restart
docker-compose -f docker-compose.prod.yml build
docker-compose -f docker-compose.prod.yml up -d

echo "✅ Gateway restarted with passkey support"
echo "🌐 https://bridge.secure.build/static/enroll.html"

REMOTE_EOF

echo ""
echo "Deployment complete!"
echo "Enrollment page: https://bridge.secure.build/static/enroll.html"
