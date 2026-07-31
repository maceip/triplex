"""Single-host production runtime for the gateway and SQS call worker."""

from __future__ import annotations

import asyncio
import logging
import os

import uvicorn

from .transport.call_processor import CallProcessor, _config_from_environment
from .transport.meeting_gateway import create_app


async def run() -> None:
    """Run both services and fail the host if either service exits."""
    admin_token = os.environ.get("MEETING_GATEWAY_ADMIN_TOKEN", "")
    if not admin_token:
        raise RuntimeError("MEETING_GATEWAY_ADMIN_TOKEN is required")
    gateway = uvicorn.Server(
        uvicorn.Config(
            create_app(admin_token=admin_token),
            host="127.0.0.1",
            port=int(os.environ.get("MEETING_GATEWAY_PORT", "8765")),
            log_level=os.environ.get("LOG_LEVEL", "info").lower(),
            access_log=False,
            workers=1,
        )
    )
    processor = CallProcessor(_config_from_environment())
    tasks = {
        asyncio.create_task(gateway.serve(), name="triplex-meeting-gateway"),
        asyncio.create_task(processor.run_forever(), name="triplex-call-worker"),
    }
    done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    for task in pending:
        task.cancel()
    await asyncio.gather(*pending, return_exceptions=True)
    for task in done:
        if exception := task.exception():
            raise exception
    raise RuntimeError("Triplex runtime exited unexpectedly")


def main() -> None:
    logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))
    asyncio.run(run())


if __name__ == "__main__":
    main()
