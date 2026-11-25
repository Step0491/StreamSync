"""Step 2: Synchronized watch-party rooms (Rave / Watch2Gether style)."""

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
    media: dict[str, Any] | None = None
    is_playing: bool = False
    position: float = 0.0
    playback_rate: float = 1.0
    updated_at: float = field(default_factory=time.time)
    members: dict[str, RoomMember] = field(default_factory=dict)
    chat_history: list[dict[str, Any]] = field(default_factory=list)

    def current_position(self) -> float:
        if not self.is_playing:
            return max(0.0, self.position)
        elapsed = max(0.0, time.time() - self.updated_at)
        return max(0.0, self.position + elapsed * self.playback_rate)

    def member_list(self) -> list[dict[str, Any]]:
        return [
            {
                "client_id": m.client_id,
                "nickname": m.nickname,
                "is_host": m.client_id == self.host_id,
            }
            for m in self.members.values()
        ]

    def snapshot(self, viewer_id: str) -> dict[str, Any]:
        return {
            "type": "room_state",
            "room_id": self.room_id,
            "client_id": viewer_id,
            "host_id": self.host_id,
            "media": self.media,
            "is_playing": self.is_playing,
            "position": round(self.current_position(), 3),
            "playback_rate": self.playback_rate,
            "server_ts": time.time(),
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
        }

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
        clean_name = (nickname or "").strip()[:24] or f"Guest-{client_id[:4]}"

        member = RoomMember(client_id=client_id, nickname=clean_name, websocket=websocket)
        room.members[client_id] = member
        if room.host_id is None:
            room.host_id = client_id

        await self._send_safe(websocket, room.snapshot(client_id))
        await self.broadcast(
            room,
            {
                "type": "members_updated",
                "host_id": room.host_id,
                "members": room.member_list(),
                "system_message": f"{clean_name} joined the room.",
            },
            exclude_id=client_id,
        )
        return room, client_id

    async def leave(self, room_id: str, client_id: str) -> None:
        code = room_id.strip().upper()
        room = self.rooms.get(code)
        if not room:
            return

        removed = room.members.pop(client_id, None)
        if not room.members:
            self.rooms.pop(code, None)
            return

        if room.host_id == client_id:
            room.host_id = next(iter(room.members))

        if removed:
            await self.broadcast(
                room,
                {
                    "type": "members_updated",
                    "host_id": room.host_id,
                    "members": room.member_list(),
                    "system_message": f"{removed.nickname} left the room.",
                },
            )

    async def handle_message(self, room_id: str, client_id: str, data: dict[str, Any]) -> None:
        room = self.rooms.get(room_id.strip().upper())
        if not room or client_id not in room.members:
            return

        sender = room.members[client_id]
        msg_type = data.get("type")

        if msg_type == "ping":
            await self._send_safe(
                sender.websocket,
                {"type": "pong", "client_ts": data.get("client_ts"), "server_ts": time.time()},
            )
            return

        if msg_type == "load_media":
            media = data.get("media")
            if not isinstance(media, dict) or not isinstance(media.get("playback"), dict):
                return
            room.media = {
                "title": str(media.get("title") or "Untitled Stream")[:160],
                "source_url": str(media.get("source_url") or ""),
                "playback": media["playback"],
            }
            room.is_playing = False
            room.position = 0.0
            room.playback_rate = 1.0
            room.updated_at = time.time()
            await self.broadcast(
                room,
                {
                    "type": "media_changed",
                    "media": room.media,
                    "is_playing": room.is_playing,
                    "position": room.position,
                    "playback_rate": room.playback_rate,
                    "by": sender.nickname,
                    "server_ts": room.updated_at,
                },
            )
            return

        if msg_type == "sync":
            is_playing = bool(data.get("is_playing", room.is_playing))
            try:
                position = max(0.0, float(data.get("position", room.current_position())))
            except (TypeError, ValueError):
                position = room.current_position()
            try:
                rate = float(data.get("playback_rate", room.playback_rate))
                rate = min(4.0, max(0.25, rate))
            except (TypeError, ValueError):
                rate = room.playback_rate

            room.is_playing = is_playing
            room.position = position
            room.playback_rate = rate
            room.updated_at = time.time()

            await self.broadcast(
                room,
                {
                    "type": "sync",
                    "action": str(data.get("action") or "update"),
                    "is_playing": room.is_playing,
                    "position": round(room.position, 3),
                    "playback_rate": room.playback_rate,
                    "by": sender.nickname,
                    "sender_id": client_id,
                    "server_ts": room.updated_at,
                },
                exclude_id=client_id,
            )
            return

        if msg_type == "chat":
            text = str(data.get("text") or "").strip()[:MAX_CHAT_LEN]
            if not text:
                return
            entry = {
                "id": secrets.token_hex(4),
                "sender_id": client_id,
                "nickname": sender.nickname,
                "text": text,
                "ts": time.time(),
            }
            room.chat_history.append(entry)
            if len(room.chat_history) > MAX_CHAT_HISTORY:
                room.chat_history = room.chat_history[-MAX_CHAT_HISTORY:]
            await self.broadcast(room, {"type": "chat", "message": entry})
