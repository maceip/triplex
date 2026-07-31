"""Authentication and bidirectional PCM relay tests for the Chime gateway."""

from __future__ import annotations

from starlette.testclient import TestClient

from triplex.transport.meeting_gateway import create_app


def test_gateway_requires_admin_and_relays_pcm_and_acknowledgements() -> None:
    client = TestClient(create_app(admin_token="admin-token"))
    credentials = {
        "meeting": {"MeetingId": "meeting-1"},
        "attendee": {"AttendeeId": "attendee-1", "JoinToken": "join-token"},
        "call_id": "call-1",
    }
    assert client.post("/sessions", json=credentials).status_code == 401
    response = client.post(
        "/sessions",
        headers={"Authorization": "Bearer admin-token"},
        json=credentials,
    )
    assert response.status_code == 200
    session = response.json()

    browser_path = session["join_path"].removesuffix("/join")
    credential_response = client.get(
        f"{browser_path}/credentials",
        headers={"Authorization": f"Bearer {session['browser_token']}"},
    )
    assert credential_response.json() == credentials

    session_id = session["session_id"]
    with client.websocket_connect(
        f"/ws/browser/{session_id}",
        subprotocols=["triplex", session["browser_token"]],
    ) as browser:
        browser.send_text('{"type":"ready"}')
        with client.websocket_connect(
            f"/ws/producer/{session_id}",
            subprotocols=["triplex", session["producer_token"]],
        ) as producer:
            metadata = '{"type":"audio","generation_id":"g1"}'
            producer.send_text(metadata)
            assert browser.receive_text() == metadata
            producer.send_bytes(b"\x01\x00")
            assert browser.receive_bytes() == b"\x01\x00"
            acknowledgement = (
                '{"type":"played","generation_id":"g1","played_samples":1}'
            )
            browser.send_text(acknowledgement)
            assert producer.receive_text() == acknowledgement
