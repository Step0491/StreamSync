"""Step 2: Synchronized watch-party rooms (Host/Collaborative controls, RTT sync & Buffering Barrier)."""

from __future__ import annotations

import secrets
import string
import time
from dataclasses import dataclass, field
from typing import Any

from fastapi import WebSocket

ROOM_ALPHABET = string.ascii_uppercase + string.digits
MAX_CHAT_HISTORY = 50
MAX_CHAT_LEN = 500
BUFFER_BARRIER_TIMEOUT_SEC = 15.0


def _generate_room_code(length: int = 6) -> str:
    return "".join(secrets.choice(ROOM_ALPHABET) for _ in range(length))


@dataclass
class RoomMember:
    client_id: str
    nickname: str
    websocket: WebSocket


@dataclass
class RoomState:
    room_id: str
    host_id: str | None = None
    control_mode: str = "everyone"  # "everyone" (unlocked by default) | "host_only" (revocable by Host)
    media: dict[str, Any] | None = None
    is_playing: bool = False
    desired_playing: bool = False
    position: float = 0.0
    playback_rate: float = 1.0
    sync_seq: int = 0
    updated_at: float = field(default_factory=time.time)
    members: dict[str, RoomMember] = field(default_factory=dict)
    buffering_members: dict[str, float] = field(default_factory=dict)
    chat_history: list[dict[str, Any]] = field(default_factory=list)

    def prune_stale_buffering(self, now: float | None = None) -> bool:
        ts = now if now is not None else time.time()
        expired = [
            cid
            for cid, started in self.buffering_members.items()
            if cid not in self.members or (ts - started) > BUFFER_BARRIER_TIMEOUT_SEC
        ]
        for cid in expired:
            self.buffering_members.pop(cid, None)
        return len(expired) > 0

    def active_buffering_names(self) -> list[str]:
        return [
            self.members[cid].nickname
            for cid in self.buffering_members
            if cid in self.members
        ]

    def current_position(self, now: float | None = None) -> float:
        if not self.is_playing:
            return max(0.0, self.position)
        ts = now if now is not None else time.time()
        elapsed = max(0.0, ts - self.updated_at)
        return max(0.0, self.position + elapsed * self.playback_rate)

    def can_control(self, client_id: str) -> bool:
        if self.control_mode == "everyone":
            return client_id in self.members
        return client_id == self.host_id

    def role_for(self, client_id: str) -> str:
        return "admin" if client_id == self.host_id else "guest"

    def member_list(self) -> list[dict[str, Any]]:
        return [
            {
                "client_id": m.client_id,
                "nickname": m.nickname,
                "is_host": m.client_id == self.host_id,
                "role": self.role_for(m.client_id),
                "is_buffering": m.client_id in self.buffering_members,
            }
            for m in self.members.values()
        ]

    def snapshot(self, viewer_id: str) -> dict[str, Any]:
        now = time.time()
        self.prune_stale_buffering(now)
        waiting = self.active_buffering_names()
        return {
            "type": "room_state",
            "room_id": self.room_id,
            "client_id": viewer_id,
            "host_id": self.host_id,
            "role": self.role_for(viewer_id),
            "control_mode": self.control_mode,
            "media": self.media,
            "is_playing": self.is_playing,
            "desired_playing": self.desired_playing,
            "buffering_active": bool(waiting and self.desired_playing),
            "waiting_for": waiting,
            "position": round(self.current_position(now), 3),
            "playback_rate": self.playback_rate,
            "sync_seq": self.sync_seq,
            "server_ts": now,
            "members": self.member_list(),
            "chat_history": self.chat_history,
        }


class RoomManager:
    """In-memory coordinator for synchronized watch rooms."""

    def __init__(self) -> None:
        self.rooms: dict[str, RoomState] = {}

    def create_room(self) -> str:
        for _ in range(10):
            code = _generate_room_code()
            if code not in self.rooms:
                self.rooms[code] = RoomState(room_id=code)
                return code
        raise RuntimeError("Could not allocate unique room code.")

    def get_room_summary(self, room_id: str) -> dict[str, Any] | None:
        room = self.rooms.get(room_id.upper())
        if not room:
            return None
        return {
            "room_id": room.room_id,
            "member_count": len(room.members),
            "has_media": room.media is not None,
            "title": room.media.get("title") if room.media else None,
            "is_playing": room.is_playing,
            "control_mode": room.control_mode,
        }

    async def set_media_http(self, room_id: str, media: dict[str, Any], auto_play: bool = True, sender_name: str = "Admin") -> dict[str, Any]:
        code = room_id.strip().upper()
        room = self.rooms.get(code)
        if room is None:
            room = RoomState(room_id=code)
            self.rooms[code] = room

        raw_pb = media.get("playback") if isinstance(media.get("playback"), dict) else {}
        sanitized_headers: dict[str, str] = {}
        if isinstance(raw_pb.get("headers"), dict):
            for hk, hv in raw_pb["headers"].items():
                if isinstance(hk, str) and isinstance(hv, str):
                    if hk.lower() in ("referer", "origin", "user-agent") and hv.strip():
                        sanitized_headers[hk] = hv.strip()[:512]

        playback_clean: dict[str, Any] = {
            "mode": "paired" if raw_pb.get("mode") == "paired" and raw_pb.get("audio_url") else "single",
            "video_url": str(raw_pb.get("video_url") or ""),
            "audio_url": str(raw_pb.get("audio_url") or "") or None,
            "is_hls": bool(raw_pb.get("is_hls", False)),
            "is_dash": bool(raw_pb.get("is_dash", False)),
            "label": str(raw_pb.get("label") or "")[:120],
        }
        if sanitized_headers:
            playback_clean["headers"] = sanitized_headers

        now = time.time()
        room.media = {
            "title": str(media.get("title") or "Untitled Stream")[:160],
            "source_url": str(media.get("source_url") or ""),
            "playback": playback_clean,
        }
        room.buffering_members.clear()
        room.is_playing = auto_play
        room.desired_playing = auto_play
        room.position = 0.0
        room.playback_rate = 1.0
        room.updated_at = now
        room.sync_seq += 1

        await self.broadcast(
            room,
            {
                "type": "media_changed",
                "media": room.media,
                "is_playing": room.is_playing,
                "position": room.position,
                "playback_rate": room.playback_rate,
                "sync_seq": room.sync_seq,
                "by": sender_name[:24],
                "sender_id": "http",
                "server_ts": room.updated_at,
            },
        )
        return {"ok": True, "room_id": room.room_id, "sync_seq": room.sync_seq}

    async def _send_safe(self, ws: WebSocket, payload: dict[str, Any]) -> None:
        try:
            await ws.send_json(payload)
        except Exception:
            pass

    async def broadcast(
        self,
        room: RoomState,
        payload: dict[str, Any],
        exclude_id: str | None = None,
    ) -> None:
        for cid, member in list(room.members.items()):
            if exclude_id and cid == exclude_id:
                continue
            await self._send_safe(member.websocket, payload)

    async def join(self, room_id: str, websocket: WebSocket, nickname: str) -> tuple[RoomState, str]:
        code = room_id.strip().upper()
        room = self.rooms.get(code)
        if room is None:
            room = RoomState(room_id=code)
            self.rooms[code] = room

        await websocket.accept()
        client_id = secrets.token_hex(6)
        is_creator = room.host_id is None
        raw_nick = (nickname or "").strip()
        if raw_nick.lower() == "guest" and is_creator:
            raw_nick = ""
        clean_name = raw_nick[:24] or ("Admin" if is_creator else f"Guest-{client_id[:4]}")

        member = RoomMember(client_id=client_id, nickname=clean_name, websocket=websocket)
        room.members[client_id] = member
        if is_creator:
            room.host_id = client_id

        await self._send_safe(websocket, room.snapshot(client_id))
        role_label = "Admin" if room.role_for(client_id) == "admin" else "Guest"
        await self.broadcast(
            room,
            {
                "type": "members_updated",
                "host_id": room.host_id,
                "control_mode": room.control_mode,
                "members": room.member_list(),
                "system_message": f"{clean_name} ({role_label}) joined the room.",
            },
            exclude_id=client_id,
        )
        return room, client_id

    async def leave(self, room_id: str, client_id: str) -> None:
        code = room_id.strip().upper()
        room = self.rooms.get(code)
        if not room:
            return

        was_admin = room.host_id == client_id
        removed = room.members.pop(client_id, None)
        was_buffering = room.buffering_members.pop(client_id, None) is not None

        if not room.members:
            self.rooms.pop(code, None)
            return

        if was_admin:
            room.host_id = next(iter(room.members))

        if removed:
            old_role = "Admin" if was_admin else "Guest"
            await self.broadcast(
                room,
                {
                    "type": "members_updated",
                    "host_id": room.host_id,
                    "control_mode": room.control_mode,
                    "members": room.member_list(),
                    "system_message": f"{removed.nickname} ({old_role}) left the room.",
                },
            )

        # If the departed client was the last one holding a buffering barrier, resume room playback
        if was_buffering:
            await self._evaluate_buffer_barrier_release(room)

    async def _evaluate_buffer_barrier_release(self, room: RoomState) -> None:
        now = time.time()
        room.prune_stale_buffering(now)
        waiting = room.active_buffering_names()
        if room.desired_playing and not waiting and not room.is_playing:
            room.is_playing = True
            room.updated_at = now
            room.sync_seq += 1
            await self.broadcast(
                room,
                {
                    "type": "buffer_barrier",
                    "active": False,
                    "waiting_for": [],
                    "is_playing": True,
                    "position": round(room.position, 3),
                    "playback_rate": room.playback_rate,
                    "sync_seq": room.sync_seq,
                    "server_ts": now,
                },
            )
        elif room.desired_playing and waiting:
            await self.broadcast(
                room,
                {
                    "type": "buffer_barrier",
                    "active": True,
                    "waiting_for": waiting,
                    "is_playing": False,
                    "position": round(room.position, 3),
                    "playback_rate": room.playback_rate,
                    "sync_seq": room.sync_seq,
                    "server_ts": now,
                },
            )

    async def handle_message(self, room_id: str, client_id: str, data: dict[str, Any]) -> None:
        room = self.rooms.get(room_id.strip().upper())
        if not room or client_id not in room.members:
            return

        sender = room.members[client_id]
        msg_type = data.get("type")
        now = time.time()

        if room.prune_stale_buffering(now) and room.desired_playing and not room.is_playing:
            await self._evaluate_buffer_barrier_release(room)

        if msg_type == "ping":
            await self._send_safe(
                sender.websocket,
                {
                    "type": "pong",
                    "client_ts": data.get("client_ts"),
                    "wall_ts": data.get("wall_ts"),
                    "server_ts": now,
                },
            )
            return

        if msg_type == "set_control_mode":
            if client_id != room.host_id:
                await self._send_safe(
                    sender.websocket,
                    {
                        "type": "sync_denied",
                        "reason": "Only the room Admin can change control permissions.",
                        "control_mode": room.control_mode,
                        "is_playing": room.is_playing,
                        "position": round(room.current_position(now), 3),
                        "playback_rate": room.playback_rate,
                        "sync_seq": room.sync_seq,
                        "server_ts": now,
                    },
                )
                return
            requested = str(data.get("control_mode") or "").strip().lower()
            if requested in ("host_only", "everyone"):
                room.control_mode = requested
                await self.broadcast(
                    room,
                    {
                        "type": "control_mode_changed",
                        "control_mode": room.control_mode,
                        "by": sender.nickname,
                        "system_message": (
                            f"{sender.nickname} (Admin) set playback controls to "
                            f"{'Admin Only' if room.control_mode == 'host_only' else 'Everyone'}."
                        ),
                    },
                )
            return

        if msg_type == "load_media":
            if not room.can_control(client_id):
                await self._send_safe(
                    sender.websocket,
                    {
                        "type": "sync_denied",
                        "reason": "Admin-only playback control is active.",
                        "control_mode": room.control_mode,
                        "is_playing": room.is_playing,
                        "position": round(room.current_position(now), 3),
                        "playback_rate": room.playback_rate,
                        "sync_seq": room.sync_seq,
                        "server_ts": now,
                    },
                )
                return

            media = data.get("media")
            if not isinstance(media, dict) or not isinstance(media.get("playback"), dict):
                return

            raw_pb = media["playback"]
            sanitized_headers: dict[str, str] = {}
            if isinstance(raw_pb.get("headers"), dict):
                for hk, hv in raw_pb["headers"].items():
                    if isinstance(hk, str) and isinstance(hv, str):
                        if hk.lower() in ("referer", "origin", "user-agent") and hv.strip():
                            sanitized_headers[hk] = hv.strip()[:512]

            playback_clean: dict[str, Any] = {
                "mode": "paired" if raw_pb.get("mode") == "paired" and raw_pb.get("audio_url") else "single",
                "video_url": str(raw_pb.get("video_url") or ""),
                "audio_url": str(raw_pb.get("audio_url") or "") or None,
                "is_hls": bool(raw_pb.get("is_hls", False)),
                "is_dash": bool(raw_pb.get("is_dash", False)),
                "label": str(raw_pb.get("label") or "")[:120],
            }
            if sanitized_headers:
                playback_clean["headers"] = sanitized_headers

            auto_play = bool(data.get("auto_play", False))
            tracks_snapshot = media.get("tracks_snapshot")
            clean_snapshot = None
            if isinstance(tracks_snapshot, dict):
                clean_snapshot = {
                    k: tracks_snapshot[k][:15]
                    for k in ("combined_formats", "video_only_formats", "audio_only_formats")
                    if isinstance(tracks_snapshot.get(k), list)
                } or None

            room.media = {
                "title": str(media.get("title") or "Untitled Stream")[:160],
                "source_url": str(media.get("source_url") or ""),
                "playback": playback_clean,
                "tracks_snapshot": clean_snapshot,
            }
            room.buffering_members.clear()
            room.is_playing = auto_play
            room.desired_playing = auto_play
            room.position = 0.0
            room.playback_rate = 1.0
            room.updated_at = now
            room.sync_seq += 1

            await self.broadcast(
                room,
                {
                    "type": "media_changed",
                    "media": room.media,
                    "is_playing": room.is_playing,
                    "position": room.position,
                    "playback_rate": room.playback_rate,
                    "sync_seq": room.sync_seq,
                    "by": sender.nickname,
                    "by_role": room.role_for(client_id),
                    "sender_id": client_id,
                    "server_ts": room.updated_at,
                },
            )
            return

        if msg_type == "sync":
            if not room.can_control(client_id):
                await self._send_safe(
                    sender.websocket,
                    {
                        "type": "sync_denied",
                        "reason": "Admin-only playback control is active.",
                        "control_mode": room.control_mode,
                        "is_playing": room.is_playing,
                        "position": round(room.current_position(now), 3),
                        "playback_rate": room.playback_rate,
                        "sync_seq": room.sync_seq,
                        "server_ts": now,
                    },
                )
                return

            is_playing = bool(data.get("is_playing", room.is_playing))
            try:
                position = max(0.0, float(data.get("position", room.current_position(now))))
            except (TypeError, ValueError):
                position = room.current_position(now)
            try:
                rate = float(data.get("playback_rate", room.playback_rate))
                rate = min(4.0, max(0.25, rate))
            except (TypeError, ValueError):
                rate = room.playback_rate

            room.desired_playing = is_playing
            if not is_playing:
                room.buffering_members.clear()
                room.is_playing = False
            else:
                room.is_playing = len(room.buffering_members) == 0

            room.position = position
            room.playback_rate = rate
            room.updated_at = now
            room.sync_seq += 1

            await self.broadcast(
                room,
                {
                    "type": "sync",
                    "action": str(data.get("action") or "update"),
                    "is_playing": room.is_playing,
                    "desired_playing": room.desired_playing,
                    "position": round(room.position, 3),
                    "playback_rate": room.playback_rate,
                    "sync_seq": room.sync_seq,
                    "by": sender.nickname,
                    "by_role": room.role_for(client_id),
                    "sender_id": client_id,
                    "server_ts": room.updated_at,
                },
                exclude_id=client_id,
            )
            return

        if msg_type == "heartbeat":
            # Periodic quiet drift anchor sent by Host while playing
            if client_id != room.host_id or not room.is_playing or room.buffering_members:
                return
            try:
                position = max(0.0, float(data.get("position", room.current_position(now))))
            except (TypeError, ValueError):
                return
            try:
                rate = min(4.0, max(0.25, float(data.get("playback_rate", room.playback_rate))))
            except (TypeError, ValueError):
                rate = room.playback_rate

            room.position = position
            room.playback_rate = rate
            room.updated_at = now
            await self.broadcast(
                room,
                {
                    "type": "heartbeat",
                    "is_playing": True,
                    "position": round(room.position, 3),
                    "playback_rate": room.playback_rate,
                    "sync_seq": room.sync_seq,
                    "server_ts": now,
                },
                exclude_id=client_id,
            )
            return

        if msg_type == "buffer_state":
            if room.media is None:
                return
            is_buffering = bool(data.get("buffering", False))
            if is_buffering:
                already = client_id in room.buffering_members
                room.buffering_members[client_id] = now
                if room.is_playing:
                    # Freeze canonical room clock and pause all viewers until buffer clears
                    try:
                        reported_pos = float(data.get("position", room.current_position(now)))
                        room.position = max(0.0, min(room.current_position(now), reported_pos + 0.5))
                    except (TypeError, ValueError):
                        room.position = room.current_position(now)
                    room.desired_playing = True
                    room.is_playing = False
                    room.updated_at = now
                    room.sync_seq += 1
                    await self.broadcast(
                        room,
                        {
                            "type": "buffer_barrier",
                            "active": True,
                            "waiting_for": room.active_buffering_names(),
                            "is_playing": False,
                            "position": round(room.position, 3),
                            "playback_rate": room.playback_rate,
                            "sync_seq": room.sync_seq,
                            "server_ts": now,
                        },
                    )
                elif not already and room.desired_playing:
                    await self.broadcast(
                        room,
                        {
                            "type": "buffer_barrier",
                            "active": True,
                            "waiting_for": room.active_buffering_names(),
                            "is_playing": False,
                            "position": round(room.position, 3),
                            "playback_rate": room.playback_rate,
                            "sync_seq": room.sync_seq,
                            "server_ts": now,
                        },
                    )
            else:
                if room.buffering_members.pop(client_id, None) is not None:
                    await self._evaluate_buffer_barrier_release(room)
            return

        if msg_type == "chat":
            text = str(data.get("text") or "").strip()[:MAX_CHAT_LEN]
            if not text:
                return
            entry = {
                "id": secrets.token_hex(4),
                "sender_id": client_id,
                "nickname": sender.nickname,
                "role": room.role_for(client_id),
                "text": text,
                "ts": now,
            }
            room.chat_history.append(entry)
            if len(room.chat_history) > MAX_CHAT_HISTORY:
                room.chat_history = room.chat_history[-MAX_CHAT_HISTORY:]
            await self.broadcast(room, {"type": "chat", "message": entry})
