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
    assert summary.json()["control_mode"] == "everyone"


def test_websocket_room_sync_media_and_chat():
    room_id = client.post("/api/rooms").json()["room_id"]

    with (
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=Alice") as ws1,
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=Bob") as ws2,
    ):
        state1 = ws1.receive_json()
        assert state1["type"] == "room_state"
        assert state1["host_id"] == state1["client_id"]
        assert state1["role"] == "admin"
        assert state1["control_mode"] == "everyone"

        # Alice receives notification that Bob joined
        joined_evt = ws1.receive_json()
        assert joined_evt["type"] == "members_updated"
        assert len(joined_evt["members"]) == 2
        assert joined_evt["members"][0]["role"] == "admin"
        assert joined_evt["members"][1]["role"] == "guest"

        state2 = ws2.receive_json()
        assert state2["type"] == "room_state"
        assert state2["role"] == "guest"
        assert len(state2["members"]) == 2

        # Alice loads a seamlessly paired video+audio stream with Referer header hint
        ws1.send_json(
            {
                "type": "load_media",
                "media": {
                    "title": "Unified Movie",
                    "source_url": "https://example.com/watch?v=1",
                    "stream_hint": {
                        "mode": "paired",
                        "referer_url": "https://embed.example.com/e/1",
                        "video": {"kind": "VIDEO_ONLY", "host": "cdn.example.com", "height": 1080, "container": "mp4"},
                        "audio": {"kind": "AUDIO_ONLY", "host": "cdn.example.com", "language": "it", "container": "m3u8"},
                        "click_trail": [{"tag": "button", "selector": "#play-btn", "text": "Guarda", "is_top": True, "rel_x": 0.5, "rel_y": 0.5}],
                    },
                    "playback": {
                        "mode": "paired",
                        "video_url": "https://cdn.example.com/1080v.mp4",
                        "audio_url": "https://cdn.example.com/audio.m3u8",
                        "is_hls": False,
                        "is_dash": False,
                        "label": "1080p (Unified Video + Audio)",
                        "headers": {
                            "Referer": "https://example.com/watch?v=1",
                            "Cookie": "secret=should_be_stripped",
                        },
                    },
                },
            }
        )
        m1 = ws1.receive_json()
        m2 = ws2.receive_json()
        assert m1["type"] == "media_changed"
        assert m2["type"] == "media_changed"
        assert m2["media"]["playback"]["mode"] == "paired"
        # Referer is preserved for Electron/ExoPlayer peers while sensitive Cookie is stripped!
        assert m2["media"]["playback"]["headers"] == {"Referer": "https://example.com/watch?v=1"}
        assert m2["media"]["stream_hint"]["video"]["height"] == 1080
        assert m2["media"]["stream_hint"]["audio"]["language"] == "it"
        assert m2["media"]["stream_hint"]["click_trail"][0]["selector"] == "#play-btn"

        # Alice presses play at 42.5s -> Bob receives sync event
        ws1.send_json({"type": "sync", "action": "play", "is_playing": True, "position": 42.5, "playback_rate": 1.0})
        sync_evt = ws2.receive_json()
        assert sync_evt["type"] == "sync"
        assert sync_evt["is_playing"] is True
        assert sync_evt["position"] == 42.5
        assert sync_evt["by"] == "Alice"

        # Bob sends a chat message -> both receive it with guest role
        ws2.send_json({"type": "chat", "text": "Ciao Alice!"})
        c1 = ws1.receive_json()
        c2 = ws2.receive_json()
        assert c1["type"] == "chat"
        assert c1["message"]["text"] == "Ciao Alice!"
        assert c1["message"]["role"] == "guest"
        assert c2["message"]["nickname"] == "Bob"


def test_host_only_permissions_and_control_mode_toggle():
    room_id = client.post("/api/rooms").json()["room_id"]

    with (
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=HostAlice") as ws_host,
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=GuestBob") as ws_guest,
    ):
        ws_host.receive_json()  # room_state
        ws_host.receive_json()  # members_updated
        ws_guest.receive_json()  # room_state

        # By default ("everyone"), GuestBob can control playback
        ws_guest.send_json({"type": "sync", "action": "seek", "is_playing": True, "position": 88.0})
        sync_to_host = ws_host.receive_json()
        assert sync_to_host["type"] == "sync"
        assert sync_to_host["position"] == 88.0
        assert sync_to_host["by"] == "GuestBob"

        # HostAlice revokes guest controls -> switches to "host_only"
        ws_host.send_json({"type": "set_control_mode", "control_mode": "host_only"})
        cm1 = ws_host.receive_json()
        cm2 = ws_guest.receive_json()
        assert cm1["type"] == "control_mode_changed"
        assert cm2["control_mode"] == "host_only"

        # Now GuestBob's sync attempt is rejected with sync_denied
        ws_guest.send_json({"type": "sync", "action": "play", "is_playing": True, "position": 10.0})
        denied = ws_guest.receive_json()
        assert denied["type"] == "sync_denied"


def test_pause_for_all_buffering_barrier_and_auto_resume():
    room_id = client.post("/api/rooms").json()["room_id"]

    with (
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=Alice") as ws1,
        client.websocket_connect(f"/ws/rooms/{room_id}?nickname=Bob") as ws2,
    ):
        ws1.receive_json()
        ws1.receive_json()
        ws2.receive_json()

        # Alice loads media with auto_play=True
        ws1.send_json(
            {
                "type": "load_media",
                "auto_play": True,
                "media": {
                    "title": "Action Film",
                    "source_url": "https://example.com/film",
                    "playback": {
                        "mode": "paired",
                        "video_url": "https://cdn.example.com/v.m3u8",
                        "audio_url": "https://cdn.example.com/a_it.m3u8",
                        "is_hls": True,
                    },
                },
            }
        )
        ws1.receive_json()
        ws2.receive_json()

        # Bob starts buffering -> room pauses for ALL viewers
        ws2.send_json({"type": "buffer_state", "buffering": True, "position": 12.4})
        b1 = ws1.receive_json()
        b2 = ws2.receive_json()
        assert b1["type"] == "buffer_barrier"
        assert b1["active"] is True
        assert "Bob" in b1["waiting_for"]
        assert b2["is_playing"] is False

        # Bob finishes buffering -> room automatically resumes synchronized playback for ALL viewers
        ws2.send_json({"type": "buffer_state", "buffering": False, "position": 12.4})
        r1 = ws1.receive_json()
        r2 = ws2.receive_json()
        assert r1["type"] == "buffer_barrier"
        assert r1["active"] is False
        assert r1["is_playing"] is True
        assert r2["is_playing"] is True
