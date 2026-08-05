"""AWS Chime SDK integration for PSTN calls with real-time audio.

Architecture:
```
┌─────────────────────────────────────────────────────────────────────┐
│  PSTN Call                                                          │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  SIP Media Application (Lambda)                                     │
│  Event: NEW_INBOUND_CALL                                            │
│  Action: JoinChimeMeeting (bridge to meeting)                       │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Chime SDK Meeting                                                  │
│  - PSTN caller is now a meeting attendee                            │
│  - Real-time audio available!                                       │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Media Stream Pipeline → Kinesis Video Streams                      │
│  - Captures audio in real-time                                      │
│  - Streams to KVS for processing                                    │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Our Pipeline (Lambda or EC2)                                       │
│  - Read from KVS                                                    │
│  - VAD → LLM → TTS                                                  │
│  - Write audio back to meeting                                      │
└─────────────────────────────────────────────────────────────────────┘
```

Key Actions:
- JoinChimeMeeting: Bridge PSTN call to meeting (enables real-time audio)
- PlayAudio: Play S3 audio file
- SpeakText: TTS via Amazon Polly
- Hangup: End call

References:
- https://docs.aws.amazon.com/chime-sdk/latest/dg/use-cases.html
- https://docs.aws.amazon.com/chime-sdk/latest/dg/create-media-stream-pipeline.html
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from typing import Any

# Lambda event types from Chime SDK
class ChimeEventType(str, Enum):
    NEW_INBOUND_CALL = "NEW_INBOUND_CALL"
    CALL_ANSWERED = "CALL_ANSWERED"
    CALL_UPDATED = "CALL_UPDATED"
    HANGUP = "HANGUP"
    DELETED_CALL = "DELETED_CALL"


@dataclass
class ChimeCallDetails:
    """Call details from Chime event."""

    call_id: str
    participant_id: str
    from_number: str
    to_number: str
    call_direction: str  # "Inbound" or "Outbound"


@dataclass
class ChimeEvent:
    """Parsed Chime SDK Lambda event."""

    event_type: ChimeEventType
    call_details: ChimeCallDetails
    raw_event: dict[str, Any]

    @classmethod
    def from_lambda_event(cls, event: dict[str, Any]) -> "ChimeEvent":
        """Parse Lambda event from Chime SDK."""
        event_type = ChimeEventType(event.get("InvocationEventType", ""))

        call_details_raw = event.get("CallDetails", {})
        participants = call_details_raw.get("Participants", [{}])

        call_details = ChimeCallDetails(
            call_id=call_details_raw.get("CallId", ""),
            participant_id=participants[0].get("ParticipantId", "") if participants else "",
            from_number=participants[0].get("From", "") if participants else "",
            to_number=participants[0].get("To", "") if participants else "",
            call_direction=call_details_raw.get("CallDirection", "Inbound"),
        )

        return cls(
            event_type=event_type,
            call_details=call_details,
            raw_event=event,
        )


# Action builders for Lambda response
def action_answer_call(call_id: str) -> dict[str, Any]:
    """Build AnswerCall action."""
    return {
        "Type": "AnswerCall",
        "Parameters": {
            "CallId": call_id,
            "SipResponseCode": 200,
        },
    }


def action_play_audio(call_id: str, s3_uri: str) -> dict[str, Any]:
    """Build PlayAudio action.

    Args:
        call_id: The call ID
        s3_uri: S3 URI for the audio file (e.g., "s3://bucket/audio.wav")
    """
    return {
        "Type": "PlayAudio",
        "Parameters": {
            "CallId": call_id,
            "AudioSource": {
                "Type": "S3",
                "S3Uri": s3_uri,
            },
        },
    }


def action_play_audio_from_bucket(
    call_id: str, bucket: str, key: str
) -> dict[str, Any]:
    """Build PlayAudio action with bucket/key."""
    return action_play_audio(call_id, f"s3://{bucket}/{key}")


def action_hangup(call_id: str, reason: str = "Normal") -> dict[str, Any]:
    """Build Hangup action."""
    return {
        "Type": "Hangup",
        "Parameters": {
            "CallId": call_id,
            "Reason": reason,
        },
    }


def action_call_and_bridge(
    call_id: str, destination_number: str
) -> dict[str, Any]:
    """Build CallAndBridge action (transfer call)."""
    return {
        "Type": "CallAndBridge",
        "Parameters": {
            "CallId": call_id,
            "Endpoint": {
                "Type": "PSTN",
                "Uri": destination_number,
            },
        },
    }


def action_speak_text(call_id: str, text: str, engine: str = "neural") -> dict[str, Any]:
    """Build SpeakText action (text-to-speech).

    Note: Chime SDK has built-in TTS via Polly.
    """
    return {
        "Type": "SpeakText",
        "Parameters": {
            "CallId": call_id,
            "Text": text,
            "Engine": engine,  # "standard" or "neural"
        },
    }


def action_start_call_recording(
    call_id: str, s3_uri: str
) -> dict[str, Any]:
    """Start recording call to S3."""
    return {
        "Type": "StartCallRecording",
        "Parameters": {
            "CallId": call_id,
            "Destination": {
                "Type": "S3",
                "S3Uri": s3_uri,
            },
        },
    }


def action_stop_call_recording(call_id: str) -> dict[str, Any]:
    """Stop call recording."""
    return {
        "Type": "StopCallRecording",
        "Parameters": {
            "CallId": call_id,
        },
    }


def build_response(actions: list[dict[str, Any]]) -> dict[str, Any]:
    """Build Lambda response for Chime SDK."""
    return {
        "SchemaVersion": "1.0",
        "Actions": actions,
    }


# Lambda handler template
def lambda_handler_template(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Template Lambda handler for Chime SDK calls.

    This handler bridges PSTN calls into a Chime meeting where we can
    access real-time audio via Media Stream Pipelines.

    Flow:
    1. NEW_INBOUND_CALL → Create meeting, JoinChimeMeeting, Start Media Pipeline
    2. Real-time audio flows to Kinesis Video Streams
    3. Our processor reads from KVS, runs VAD/LLM/TTS
    4. Audio is played back in meeting (heard by PSTN caller)
    5. HANGUP → Stop pipeline, cleanup
    """
    chime_event = ChimeEvent.from_lambda_event(event)

    if chime_event.event_type == ChimeEventType.NEW_INBOUND_CALL:
        # New incoming call - bridge to meeting for real-time audio

        # Step 1: Create a Chime meeting (would call Chime SDK API)
        # meeting = chime_client.create_meeting(...)

        # Step 2: Create attendee for the caller
        # attendee = chime_client.create_attendee(meeting.MeetingId, ...)

        # Step 3: Join the meeting (bridges PSTN to meeting)
        # This is where we get real-time audio access!
        return build_response([
            action_join_chime_meeting("meeting-attendee-join-token-here"),
        ])

    elif chime_event.event_type == ChimeEventType.HANGUP:
        # Call ended - trigger cleanup
        # - Stop Media Stream Pipeline
        # - End meeting
        # - Clean up resources
        return build_response([])

    # Default: no action
    return build_response([])


def action_join_chime_meeting(join_token: str) -> dict[str, Any]:
    """Join a Chime meeting - bridges PSTN call to meeting.

    This is THE KEY ACTION for real-time audio access.
    Once joined, the PSTN caller is a meeting attendee and their
    audio can be captured via Media Stream Pipelines.
    """
    return {
        "Type": "JoinChimeMeeting",
        "Parameters": {
            "JoinToken": join_token,
        },
    }


# Real-time audio processing flow:
#
# 1. SIP Media App bridges PSTN call to Chime Meeting (JoinChimeMeeting)
# 2. Media Stream Pipeline captures audio → Kinesis Video Streams
# 3. Our pipeline reads from KVS:
#    - KVS consumer (Lambda or EC2)
#    - VAD to detect speech
#    - LLM for reasoning
#    - Kokoro-82M for TTS
# 4. Response audio injected back into meeting
#
# See: https://docs.aws.amazon.com/chime-sdk/latest/dg/create-media-stream-pipeline.html
