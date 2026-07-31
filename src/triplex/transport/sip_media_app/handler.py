"""AWS Lambda entry point for the Triplex SIP media application.

The handler creates a Chime SDK meeting, joins the PSTN leg, and starts an
``IndividualAudio`` media stream pipeline backed by a pre-provisioned Kinesis
Video Streams pool.  Call-scoped resource identifiers are stored in SIP
``TransactionAttributes`` so cleanup remains correct across Lambda cold starts.
"""

from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass
from typing import Any

try:
    from triplex.transport.chime_server import (
        ChimeEvent,
        ChimeEventType,
        action_hangup,
        action_join_chime_meeting,
        build_response,
    )
except ImportError:  # Lambda zip with chime_server.py beside handler.py
    from chime_server import (  # type: ignore[import-not-found,no-redef]
        ChimeEvent,
        ChimeEventType,
        action_hangup,
        action_join_chime_meeting,
        build_response,
    )

try:
    import boto3

    HAS_BOTO3 = True
except ImportError:  # pragma: no cover - Lambda image always installs boto3
    HAS_BOTO3 = False


LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(os.environ.get("LOG_LEVEL", "INFO").upper())


@dataclass(frozen=True)
class CallSession:
    """AWS resources owned by one PSTN transaction."""

    call_id: str
    meeting_id: str
    meeting_arn: str
    caller_attendee_id: str
    media_pipeline_id: str
    created_at: float

    def transaction_attributes(self) -> dict[str, str]:
        return {
            "triplex_call_id": self.call_id,
            "triplex_meeting_id": self.meeting_id,
            "triplex_meeting_arn": self.meeting_arn,
            "triplex_caller_attendee_id": self.caller_attendee_id,
            "triplex_media_pipeline_id": self.media_pipeline_id,
            "triplex_created_at": str(self.created_at),
        }


_meetings_client: Any | None = None
_media_pipelines_client: Any | None = None


def _require_boto3() -> None:
    if not HAS_BOTO3:
        raise RuntimeError("boto3 is required by the deployed SIP handler")


def get_meetings_client() -> Any:
    global _meetings_client
    _require_boto3()
    if _meetings_client is None:
        _meetings_client = boto3.client(
            "chime-sdk-meetings", region_name=_meeting_region()
        )
    return _meetings_client


def get_media_pipelines_client() -> Any:
    global _media_pipelines_client
    _require_boto3()
    if _media_pipelines_client is None:
        _media_pipelines_client = boto3.client(
            "chime-sdk-media-pipelines", region_name=_meeting_region()
        )
    return _media_pipelines_client


def create_meeting_for_call(call_id: str) -> dict[str, Any]:
    response = get_meetings_client().create_meeting(
        ClientRequestToken=call_id,
        MediaRegion=_meeting_region(),
        ExternalMeetingId=f"triplex-{call_id}"[:64],
    )
    return dict(response["Meeting"])


def create_caller_attendee(meeting_id: str, call_id: str) -> dict[str, Any]:
    response = get_meetings_client().create_attendee(
        MeetingId=meeting_id,
        ExternalUserId=f"caller-{call_id}"[:64],
    )
    return dict(response["Attendee"])


def create_media_pipeline(meeting_arn: str, call_id: str) -> dict[str, Any]:
    pool_arn = os.environ.get("KVS_POOL_ARN")
    if not pool_arn:
        raise RuntimeError("KVS_POOL_ARN must identify an ACTIVE Chime KVS pool")

    response = get_media_pipelines_client().create_media_stream_pipeline(
        Sources=[{"SourceType": "ChimeSdkMeeting", "SourceArn": meeting_arn}],
        Sinks=[
            {
                "SinkArn": pool_arn,
                "SinkType": "KinesisVideoStreamPool",
                "ReservedStreamCapacity": int(
                    os.environ.get("KVS_RESERVED_STREAM_CAPACITY", "2")
                ),
                "MediaStreamType": "IndividualAudio",
            }
        ],
        ClientRequestToken=call_id,
        Tags=[{"Key": "triplex:call-id", "Value": call_id}],
    )
    return dict(response["MediaStreamPipeline"])


def delete_media_pipeline(media_pipeline_id: str) -> None:
    get_media_pipelines_client().delete_media_pipeline(
        MediaPipelineId=media_pipeline_id
    )


def end_meeting(meeting_id: str) -> None:
    get_meetings_client().delete_meeting(MeetingId=meeting_id)


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Handle Chime SDK PSTN audio invocation events."""
    del context
    chime_event = ChimeEvent.from_lambda_event(event)
    LOGGER.info(
        "SIP event=%s transaction=%s call=%s",
        chime_event.event_type.value,
        chime_event.call_details.transaction_id,
        chime_event.call_details.call_id,
    )

    if chime_event.event_type == ChimeEventType.NEW_INBOUND_CALL:
        return handle_new_call(chime_event)
    if chime_event.event_type == ChimeEventType.ACTION_FAILED:
        return handle_action_failed(chime_event)
    if chime_event.event_type == ChimeEventType.HANGUP:
        return handle_hangup(chime_event)
    return build_response([])


def handle_new_call(event: ChimeEvent) -> dict[str, Any]:
    call_id = event.call_details.call_id
    if not call_id:
        raise ValueError("NEW_INBOUND_CALL did not contain a LEG-A CallId")

    meeting_id: str | None = None
    pipeline_id: str | None = None
    try:
        meeting = create_meeting_for_call(call_id)
        meeting_id = str(meeting["MeetingId"])
        meeting_arn = str(meeting["MeetingArn"])
        attendee = create_caller_attendee(meeting_id, call_id)
        pipeline = create_media_pipeline(meeting_arn, call_id)
        pipeline_id = str(pipeline["MediaPipelineId"])

        session = CallSession(
            call_id=call_id,
            meeting_id=meeting_id,
            meeting_arn=meeting_arn,
            caller_attendee_id=str(attendee["AttendeeId"]),
            media_pipeline_id=pipeline_id,
            created_at=time.time(),
        )
        LOGGER.info(
            "Created meeting=%s media_pipeline=%s call=%s",
            meeting_id,
            pipeline_id,
            call_id,
        )
        return build_response(
            [
                action_join_chime_meeting(
                    str(attendee["JoinToken"]), call_id, meeting_id
                )
            ],
            transaction_attributes=session.transaction_attributes(),
        )
    except Exception:
        LOGGER.exception("Failed to initialize Chime media for call=%s", call_id)
        _cleanup_partial_session(pipeline_id, meeting_id)
        return build_response(
            [
                {
                    "Type": "SpeakText",
                    "Parameters": {
                        "CallId": call_id,
                        "Text": (
                            "The automated assistant could not start. "
                            "Please try again later."
                        ),
                        "Engine": "neural",
                    },
                },
                action_hangup(call_id, "Error"),
            ]
        )


def handle_hangup(event: ChimeEvent) -> dict[str, Any]:
    attributes = event.call_details.transaction_attributes or {}
    pipeline_id = attributes.get("triplex_media_pipeline_id")
    meeting_id = attributes.get("triplex_meeting_id")
    _cleanup_partial_session(pipeline_id, meeting_id)
    return build_response([], transaction_attributes={})


def handle_action_failed(event: ChimeEvent) -> dict[str, Any]:
    """Fail closed and release resources when the meeting join is rejected."""
    attributes = event.call_details.transaction_attributes or {}
    _cleanup_partial_session(
        attributes.get("triplex_media_pipeline_id"),
        attributes.get("triplex_meeting_id"),
    )
    call_id = event.call_details.call_id
    actions = [action_hangup(call_id, "Error")] if call_id else []
    return build_response(actions, transaction_attributes={})


def _cleanup_partial_session(
    media_pipeline_id: str | None, meeting_id: str | None
) -> None:
    if media_pipeline_id:
        try:
            delete_media_pipeline(media_pipeline_id)
        except Exception:
            LOGGER.exception(
                "Failed to delete media pipeline=%s during cleanup", media_pipeline_id
            )
    if meeting_id:
        try:
            end_meeting(meeting_id)
        except Exception:
            LOGGER.exception("Failed to delete meeting=%s during cleanup", meeting_id)


def _meeting_region() -> str:
    return os.environ.get("MEETING_REGION", "us-east-1")
