"""Plivo webhook signature validation.

The answer/hangup URLs are public, so anything that can reach them can try to
drive call routing. Plivo signs each request with the account auth token; we
verify before acting on it.

Validation delegates to Plivo's own SDK rather than a local reimplementation.
The V3 scheme is more intricate than it looks — the signed string is
`url + "?" + sorted key/value pairs + "." + nonce` for POST — and a
hand-rolled version silently rejected every genuine callback during the first
live test. Security-critical protocol code follows the maintained upstream.
"""

from __future__ import annotations

import logging

from plivo.utils.signature_v3 import validate_v3_signature

logger = logging.getLogger("triplex.gateway.plivo")


def verify_v3(
    auth_token: str,
    url: str,
    nonce: str | None,
    signature_header: str | None,
    method: str = "POST",
    params: dict[str, str] | None = None,
) -> bool:
    """True when `signature_header` is a valid Plivo V3 signature for `url`."""
    if not auth_token or not nonce or not signature_header:
        return False
    try:
        return bool(
            validate_v3_signature(
                method=method,
                uri=url,
                nonce=nonce,
                auth_token=auth_token,
                v3_signature=signature_header,
                params=dict(params or {}),
            )
        )
    except Exception:  # noqa: BLE001 - a malformed signature must not 500
        logger.warning("signature validation raised; treating as invalid")
        return False
