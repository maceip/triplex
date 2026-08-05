"""Production Lambda handler for Chime SDK SIP Media Application.

This is the main entry point for the voice agent.

Deploy:
    cd src/triplex/transport
    zip -r handler.zip sip_media_app/
    aws lambda update-function-code --function-name triplex-sip-handler --zip-file fileb://handler.zip

Environment variables required:
    - MEDIA_PIPELINE_KVS_STREAM: Kinesis Video Stream name for audio capture
    - AUDIO_BUCKET: S3 bucket for audio files
    - MEETING_REGION: AWS region for Chime meetings (default: us-east-1)
"""

from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass
from typing import Any

# Import action builders - these will be in the Lambda layer
from chime_server import (
    ChimeEvent,
    ChimeEventType,
    build_response,
    action_answer_call,
    action_hangup,
    action_join_chime_meeting,
)

try:
    import boto3

    HAS_BOTO3 = True
except ImportError:
    HAS_BOTO3 = False


@dataclass
class CallSession:
    """Active call session state."""

    call_id: str
    from_number: str
    to_number: str
    meeting_id: str | None = None
    attendee_id: str | None = None
    pipeline_arn: str | None = None
    created_at: float = 0.0


# Global state (per Lambda container)
_active_sessions: dict[str, CallSession] = {}

# AWS clients (initialized lazily)
_chime_client = None
_chime_sdk_client = None
_kinesisvideo_client = None
_s3_client = None


def get_chime_client():
    """Get or create Chime SDK client."""
    global _chime_client
    if _chime_client is None:
        _chime_client = boto3.client("chime", region_name="us-east-1")
    return _chime_client


def get_chime_sdk_meetings_client():
    """Get Chime SDK meetings client."""
    global _chime_sdk_client
    region = os.environ.get("MEETING_REGION", "us-east-1")
    if _chime_sdk_client is None:
        _chime_sdk_client = boto3.client(
            "chime-sdk-meetings",
            region_name=region,
        )
    return _chime_sdk_client


def get_kinesisvideo_client():
    """Get Kinesis Video Streams client."""
    global _kinesisvideo_client
    if _kinesisvideo_client is None:
        _kinesisvideo_client = boto3.client("kinesisvideo", region_name="us-east-1")
    return _kinesisvideo_client


def get_s3_client():
    """Get S3 client."""
    global _s3_client
    if _s3_client is None:
        _s3_client = boto3.client("s3")
    return _s3_client


def create_meeting_for_call(call_id: str) -> dict:
    """Create a Chime SDK meeting for the call.

    Returns meeting info with join token.
    """
    client = get_chime_sdk_meetings_client()

    response = client.create_meeting(
        ClientRequestToken=call_id,
        MediaRegion=os.environ.get("MEETING_REGION", "us-east-1"),
        ExternalMeetingId=f"voice-agent-{call_id[:12]}",
    )

    return response["Meeting"]


def create_attendee(meeting_id: str, call_id: str) -> dict:
    """Create an attendee for the caller.

    Returns attendee info with join token.
    """
    client = get_chime_sdk_meetings_client()

    response = client.create_attendee(
        MeetingId=meeting_id,
        ExternalUserId=f"caller-{call_id[:12]}",
    )

    return response["Attendee"]


def create_media_pipeline(meeting_id: str, stream_name: str) -> str:
    """Create Media Stream Pipeline to capture audio to KVS.

    Returns pipeline ARN.
    """
    client = get_kinesisvideo_client()

    # Create media stream pipeline
    # This captures audio from the meeting and writes to KVS
    response = client.create_media_stream_pipeline(
        MediaPipelineConfiguration={
            "Sources": [
                {
                    "SourceType": "ChimeSdkMeeting",
                    "SourceConfiguration": {
                        "ChimeSdkMeetingConfiguration": {
                            "MeetingId": meeting_id,
                            "AttendeeConfiguration": {
                                "AttendeeMode": "AllAttendees",
                            },
                        }
                    },
                }
            ],
            "Sinks": [
                {
                    "SinkType": "KinesisVideoStream",
                    "SinkConfiguration": {
                        "KinesisVideoStreamConfiguration": {
                            "StreamName": stream_name,
                            "Region": os.environ.get("MEETING_REGION", "us-east-1"),
                        }
                    },
                }
            ],
        }
    )

    return response["MediaPipeline"]["MediaPipelineArn"]


def delete_media_pipeline(pipeline_arn: str) -> None:
    """Delete Media Stream Pipeline."""
    client = get_kinesisvideo_client()
    try:
        client.delete_media_pipeline(MediaPipelineArn=pipeline_arn)
    except Exception:
        pass  # Best effort cleanup


def end_meeting(meeting_id: str) -> None:
    """End the Chime meeting."""
    client = get_chime_sdk_meetings_client()
    try:
        client.delete_meeting(MeetingId=meeting_id)
    except Exception:
        pass  # Best effort cleanup


def lambda_handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Main Lambda handler for Chime SDK SIP Media Application.

    Events:
        - NEW_INBOUND_CALL: Create meeting, bridge call, start pipeline
        - HANGUP: Cleanup resources
        - CALL_ANSWERED: (optional) logging
    """
    chime_event = ChimeEvent.from_lambda_event(event)
    call_details = chime_event.call_details

    print(f"Received event: {chime_event.event_type.value}")
    print(f"Call ID: {call_details.call_id}")
    print(f"From: {call_details.from_number}")

    if chime_event.event_type == ChimeEventType.NEW_INBOUND_CALL:
        return handle_new_call(chime_event)

    elif chime_event.event_type == ChimeEventType.HANGUP:
        return handle_hangup(chime_event)

    # Default: no action
    return build_response([])


def handle_new_call(event: ChimeEvent) -> dict[str, Any]:
    """Handle NEW_INBOUND_CALL event.

    Creates meeting, bridges call, starts media pipeline.
    """
    call_details = event.call_details
    call_id = call_details.call_id

    try:
        # 1. Create Chime SDK meeting
        meeting = create_meeting_for_call(call_id)
        meeting_id = meeting["MeetingId"]

        # 2. Create attendee for caller
        attendee = create_attendee(meeting_id, call_id)
        attendee_id = attendee["AttendeeId"]
        join_token = attendee["JoinToken"]

        # 3. Create Media Stream Pipeline for audio capture
        kvs_stream = os.environ.get("MEDIA_PIPELINE_KVS_STREAM", "triplex-audio-stream")
        pipeline_arn = create_media_pipeline(meeting_id, kvs_stream)

        # 4. Store session state
        session = CallSession(
            call_id=call_id,
            from_number=call_details.from_number,
            to_number=call_details.to_number,
            meeting_id=meeting_id,
            attendee_id=attendee_id,
            pipeline_arn=pipeline_arn,
            created_at=time.time(),
        )
        _active_sessions[call_id] = session

        print(f"Created meeting: {meeting_id}")
        print(f"Started pipeline: {pipeline_arn}")

        # 5. Return JoinChimeMeeting action
        # This bridges the PSTN call into the meeting
        return build_response([
            action_join_chime_meeting(join_token),
        ])

    except Exception as e:
        print(f"Error handling new call: {e}")
        # Best effort cleanup
        if "meeting_id" in dir():
            end_meeting(meeting_id)

        # Return error greeting via Polly TTS as fallback
        return build_response([
            {
                "Type": "SpeakText",
                "Parameters": {
                    "CallId": call_id,
                    "Text": "I'm sorry, we're experiencing technical difficulties. Please try again later.",
                    "Engine": "neural",
                },
            },
            action_hangup(call_id, "Error"),
        ])


def handle_hangup(event: ChimeEvent) -> dict[str, Any]:
    """Handle HANGUP event.

    Cleanup meeting and media pipeline.
    """
    call_details = event.call_details
    call_id = call_details.call_id

    session = _active_sessions.pop(call_id, None)

    if session:
        # Cleanup resources
        if session.pipeline_arn:
            delete_media_pipeline(session.pipeline_arn)

        if session.meeting_id:
            end_meeting(session.meeting_id)

        print(f"Cleaned up session for call {call_id}")

    return build_response([])


# For local testing
if __name__ == "__main__":
    # Test event
    test_event = {
        "InvocationEventType": "NEW_INBOUND_CALL",
        "CallDetails": {
            "CallId": "test-call-id-123",
            "CallDirection": "Inbound",
            "Participants": [
                {
                    "ParticipantId": "participant-123",
                    "From": "+15551234567",
                    "To": "+15559876543",
                }
            ],
        },
    }

    result = lambda_handler(test_event, None)
    print(json.dumps(result, indent=2))
