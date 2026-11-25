"""Tests for Step 2: Synchronized watch-party rooms over WebSockets."""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_create_room_and_summary():
    res = client.post("/api/rooms")
    assert res.status_code == 200
    room_id = res.json()["room_id"]
    assert len(room_id) == 6

    summary = client.get(f"/api/rooms/{room_id}")
    assert summary.status_code == 200
    assert summary.json()["room_id"] == room_id
    assert summary.json()["member_count"] == 0


def test_websocket_room_sync_media_and_chat():
    room_id = client.post("/api/rooms").json()["room_id"]

    with (
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=Alice") as ws1,
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=Bob") as ws2,
    ):
        state1 = ws1.receive_json()
        assert state1["type"] == "room_state"
        assert state1["host_id"] == state1["client_id"]

        # Alice receives notification that Bob joined
        joined_evt = ws1.receive_json()
        assert joined_evt["type"] == "members_updated"
        assert len(joined_evt["members"]) == 2

        state2 = ws2.receive_json()
        assert state2["type"] == "room_state"
        assert len(state2["members"]) == 2

        # Alice loads a seamlessly paired video+audio stream
        ws1.send_json(
            {
                "type": "load_media",
                "media": {
                    "title": "Unified Movie",
                    "source_url": "https://example.com/watch?v=1",
                    "playback": {
                        "mode": "paired",
                        "video_url": "https://cdn.example.com/1080v.mp4",
                        "audio_url": "https://cdn.example.com/audio.m4a",
                        "is_hls": False,
                        "is_dash": False,
                        "label": "1080p (Unified Video + Audio)",
                    },
                },
            }
        )
        m1 = ws1.receive_json()
        m2 = ws2.receive_json()
        assert m1["type"] == "media_changed"
        assert m2["type"] == "media_changed"
        assert m2["media"]["playback"]["mode"] == "paired"

        # Alice presses play at 42.5s -> Bob receives sync event
        ws1.send_json({"type": "sync", "action": "play", "is_playing": True, "position": 42.5, "playback_rate": 1.0})
        sync_evt = ws2.receive_json()
        assert sync_evt["type"] == "sync"
        assert sync_evt["is_playing"] is True
        assert sync_evt["position"] == 42.5
        assert sync_evt["by"] == "Alice"

        # Bob sends a chat message -> both receive it
        ws2.send_json({"type": "chat", "text": "Ciao Alice!"})
        c1 = ws1.receive_json()
        c2 = ws2.receive_json()
        assert c1["type"] == "chat"
        assert c1["message"]["text"] == "Ciao Alice!"
        assert c2["message"]["nickname"] == "Bob"
