"""SIP Lambda action schema, pipeline API, and cleanup regression tests."""

from __future__ import annotations

from typing import Any

from triplex.transport.sip_media_app import handler


def _event(event_type: str, attributes: dict[str, str] | None = None) -> dict:
    return {
        "InvocationEventType": event_type,
        "CallDetails": {
            "TransactionId": "transaction-1",
            "AwsRegion": "us-east-1",
            "TransactionAttributes": attributes or {},
            "Participants": [
                {
                    "CallId": "call-1",
                    "ParticipantId": "participant-1",
                    "Direction": "Inbound",
                    "From": "+15550000001",
                    "To": "+15550000002",
                }
            ],
        },
    }


def test_new_call_returns_real_join_action_and_persistent_cleanup_state(
    monkeypatch: Any,
) -> None:
    monkeypatch.setattr(
        handler,
        "create_meeting_for_call",
        lambda _call_id: {"MeetingId": "meeting-1", "MeetingArn": "meeting-arn"},
    )
    monkeypatch.setattr(
        handler,
        "create_caller_attendee",
        lambda _meeting_id, _call_id: {
            "AttendeeId": "caller-attendee",
            "JoinToken": "join-token",
        },
    )
    monkeypatch.setattr(
        handler,
        "create_media_pipeline",
        lambda _meeting_arn, _call_id: {"MediaPipelineId": "pipeline-1"},
    )

    response = handler.lambda_handler(_event("NEW_INBOUND_CALL"), None)

    assert response["Actions"] == [
        {
            "Type": "JoinChimeMeeting",
            "Parameters": {
                "JoinToken": "join-token",
                "CallId": "call-1",
                "ParticipantTag": "LEG-A",
                "MeetingId": "meeting-1",
            },
        }
    ]
    assert response["TransactionAttributes"]["triplex_media_pipeline_id"] == "pipeline-1"
    assert response["TransactionAttributes"]["triplex_meeting_id"] == "meeting-1"


def test_media_stream_pipeline_uses_chime_pool_sink(monkeypatch: Any) -> None:
    class FakeMediaPipelines:
        def __init__(self) -> None:
            self.request: dict[str, Any] = {}

        def create_media_stream_pipeline(self, **kwargs: Any) -> dict:
            self.request = kwargs
            return {"MediaStreamPipeline": {"MediaPipelineId": "pipeline-1"}}

    client = FakeMediaPipelines()
    monkeypatch.setattr(handler, "_media_pipelines_client", client)
    monkeypatch.setenv(
        "KVS_POOL_ARN",
        "arn:aws:chime:us-east-1:123456789012:media-pipeline-kinesis-video-stream-pool/triplex",
    )

    pipeline = handler.create_media_pipeline("meeting-arn", "call-1")

    assert pipeline["MediaPipelineId"] == "pipeline-1"
    assert client.request["Sources"] == [
        {"SourceType": "ChimeSdkMeeting", "SourceArn": "meeting-arn"}
    ]
    assert client.request["Sinks"][0]["SinkType"] == "KinesisVideoStreamPool"
    assert client.request["Sinks"][0]["MediaStreamType"] == "IndividualAudio"


def test_failed_join_releases_pipeline_and_meeting(monkeypatch: Any) -> None:
    deleted: list[tuple[str | None, str | None]] = []
    monkeypatch.setattr(
        handler,
        "_cleanup_partial_session",
        lambda pipeline_id, meeting_id: deleted.append((pipeline_id, meeting_id)),
    )
    response = handler.lambda_handler(
        _event(
            "ACTION_FAILED",
            {
                "triplex_media_pipeline_id": "pipeline-1",
                "triplex_meeting_id": "meeting-1",
            },
        ),
        None,
    )

    assert deleted == [("pipeline-1", "meeting-1")]
    assert response["Actions"] == [
        {"Type": "Hangup", "Parameters": {"CallId": "call-1", "Reason": "Error"}}
    ]
    assert response["TransactionAttributes"] == {}
