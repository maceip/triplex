#!/bin/bash
#
# Execute live PSTN verification sequence
#

set -e

echo "================================================================"
echo "TRIPLEX PSTN VERIFICATION SEQUENCE"
echo "================================================================"

# Configuration (can be overridden by environment)
PLIVO_NUMBER="${PLIVO_NUMBER:-}"
TEST_DESTINATION="${TEST_DESTINATION:-}"
SIP_ENDPOINT="${SIP_ENDPOINT:-}"
GATEWAY_URL="${GATEWAY_URL:-http://localhost:8000}"
CALL_DURATION="${CALL_DURATION:-30}"

if [ -z "$PLIVO_AUTH_ID" ] || [ -z "$PLIVO_AUTH_TOKEN" ]; then
    echo "ERROR: PLIVO_AUTH_ID and PLIVO_AUTH_TOKEN must be set"
    exit 1
fi

if [ -z "$PLIVO_NUMBER" ] || [ -z "$TEST_DESTINATION" ]; then
    echo "ERROR: PLIVO_NUMBER and TEST_DESTINATION must be set"
    exit 1
fi

EVIDENCE_DIR="testlab/pstn-evidence"
mkdir -p "$EVIDENCE_DIR"

echo ""
echo "[Step 1] Generate test audio..."
python testlab/scripts/generate_test_audio.py \
    --output "$EVIDENCE_DIR/test_tone.pcm" \
    --sample-rate 8000 \
    --duration 5.0

echo ""
echo "[Step 2] Start gateway (if not running)..."
if ! curl -sf "$GATEWAY_URL/health" > /dev/null 2>&1; then
    echo "WARNING: Gateway not reachable at $GATEWAY_URL"
    echo "         Ensure gateway is running or set GATEWAY_URL"
fi

echo ""
echo "[Step 3] Execute outbound PSTN call..."
echo "  From: $PLIVO_NUMBER"
echo "  To: $TEST_DESTINATION"
echo "  Duration: ${CALL_DURATION}s"

python testlab/scripts/live_pstn_test.py \
    --outbound \
    --from-number "$PLIVO_NUMBER" \
    --to-number "$TEST_DESTINATION" \
    --sip-endpoint "${SIP_ENDPOINT:-triplex-endpoint}" \
    --call-duration "$CALL_DURATION" \
    --inject-audio \
    --gateway "$GATEWAY_URL"

echo ""
echo "[Step 4] Validate PSTN evidence..."
python testlab/transport/compare.py \
    --pstn testlab/transport/pstn-validation.json \
    --production-gate

echo ""
echo "[Step 5] Document PSTN evidence..."
python testlab/scripts/document_evidence.py

echo ""
echo "================================================================"
echo "PSTN VERIFICATION COMPLETE"
echo "================================================================"

echo ""
echo "Evidence artifacts:"
ls -lh "$EVIDENCE_DIR"

echo ""
echo "Transport validation:"
if [ -f "testlab/transport/pstn-validation.json" ]; then
    cat testlab/transport/pstn-validation.json | python -m json.tool | head -30
fi

echo ""
echo "Documentation:"
 ls testlab/pstn-documentation/ 2>/dev/null || echo "  Missing (run document_evidence.py)"
