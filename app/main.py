"""FastAPI application for StreamSync (Extractor, Watch Rooms, and SSRF-safe Media CORS/Referer Proxy)."""

from __future__ import annotations

import re
from pathlib import Path
from typing import AsyncIterator
from urllib.parse import quote, urljoin

import httpx
from fastapi import FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from app.extractor import DRMProtectedError, ExtractionError, ExtractionResult, extract_media
from app.rooms import RoomManager
from app.security import UnsafeURLError, validate_public_url

BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"

app = FastAPI(title="StreamSync", version="0.3.0")
room_manager = RoomManager()


@app.middleware("http")
async def disable_ui_cache(request: Request, call_next):
    response = await call_next(request)
    if request.url.path in ("/", "/static/index.html", "/static/app.js", "/static/styles.css"):
        response.headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
        response.headers["Pragma"] = "no-cache"
        response.headers["Expires"] = "0"
    return response

_HLS_URI_ATTR_RE = re.compile(r'(URI=")([^"]+)(")', re.IGNORECASE)
_MAX_MANIFEST_PROXY_BYTES = 4 * 1024 * 1024


class ExtractRequest(BaseModel):
    url: str


def _build_proxy_url(
    target_url: str,
    referer: str | None = None,
    origin: str | None = None,
    ua: str | None = None,
) -> str:
    params = [f"url={quote(target_url, safe='')}"]
    if referer:
        params.append(f"referer={quote(referer, safe='')}")
    if origin:
        params.append(f"origin={quote(origin, safe='')}")
    if ua:
        params.append(f"ua={quote(ua, safe='')}")
    return f"/api/proxy?{'&'.join(params)}"


def rewrite_hls_playlist(
    playlist_text: str,
    base_url: str,
    referer: str | None = None,
    origin: str | None = None,
    ua: str | None = None,
) -> str:
    """Rewrite relative and absolute URLs inside an HLS playlist to route through /api/proxy."""
    out_lines: list[str] = []
    for raw_line in playlist_text.splitlines():
        line = raw_line.strip()
        if not line:
            out_lines.append(raw_line)
            continue
        if line.startswith("#"):
            rewritten = _HLS_URI_ATTR_RE.sub(
                lambda m: f'{m.group(1)}{_build_proxy_url(urljoin(base_url, m.group(2)), referer, origin, ua)}{m.group(3)}',
                raw_line,
            )
            out_lines.append(rewritten)
            continue
        resolved = urljoin(base_url, line)
        out_lines.append(_build_proxy_url(resolved, referer, origin, ua))
    return "\n".join(out_lines)


@app.get("/api/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/extract", response_model=ExtractionResult)
async def api_extract(payload: ExtractRequest) -> ExtractionResult:
    try:
        return await run_in_threadpool(extract_media, payload.url)
    except UnsafeURLError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except DRMProtectedError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except ExtractionError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.api_route("/api/proxy", methods=["GET", "HEAD"])
async def proxy_media(
    request: Request,
    url: str = Query(...),
    referer: str | None = Query(default=None),
    origin: str | None = Query(default=None),
    ua: str | None = Query(default=None),
) -> Response:
    """Relay public media streams and HLS playlists with Referer/Origin headers & CORS."""
    try:
        current_url = await run_in_threadpool(validate_public_url, url)
    except UnsafeURLError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    path_lower = current_url.lower().split("?", 1)[0]
    if path_lower.endswith((".mp4", ".ts", ".m4s", ".webm", ".mkv", ".m4a", ".aac", ".mp3", ".opus", ".mov", ".avi")):
        raise HTTPException(
            status_code=403,
            detail="Heavy media proxying is disabled on this server to preserve bandwidth; direct client playback is required.",
        )

    upstream_headers: dict[str, str] = {
        "User-Agent": (ua or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")[:512],
        "Accept": "*/*",
        "Accept-Language": "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
    }
    if referer:
        upstream_headers["Referer"] = referer[:512]
    if origin:
        upstream_headers["Origin"] = origin[:512]
    range_hdr = request.headers.get("range")
    if range_hdr:
        upstream_headers["Range"] = range_hdr

    cors_headers = {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers": "*",
        "Access-Control-Expose-Headers": "Content-Length, Content-Range, Accept-Ranges, Content-Type",
    }

    client = httpx.AsyncClient(timeout=httpx.Timeout(15.0, read=30.0), follow_redirects=False)
    try:
        resp: httpx.Response | None = None
        for _ in range(6):
            req = client.build_request(request.method, current_url, headers=upstream_headers)
            resp = await client.send(req, stream=True)
            if resp.is_redirect and resp.headers.get("location"):
                next_raw = urljoin(current_url, resp.headers["location"])
                await resp.aclose()
                current_url = await run_in_threadpool(validate_public_url, next_raw)
                continue
            break

        if resp is None:
            await client.aclose()
            raise HTTPException(status_code=502, detail="Upstream redirect loop.")

        ct = (resp.headers.get("content-type") or "application/octet-stream").lower()
        is_hls = (
            "mpegurl" in ct
            or current_url.lower().split("?", 1)[0].endswith(".m3u8")
        )
        is_dash = (
            "dash+xml" in ct
            or current_url.lower().split("?", 1)[0].endswith(".mpd")
        )

        if request.method == "HEAD":
            resp_headers = dict(cors_headers)
            for h in ("content-type", "content-length", "content-range", "accept-ranges"):
                if resp.headers.get(h):
                    resp_headers[h] = resp.headers[h]
            status_code = resp.status_code
            await resp.aclose()
            await client.aclose()
            return Response(status_code=status_code, headers=resp_headers)

        if not is_hls and not is_dash:
            await resp.aclose()
            await client.aclose()
            raise HTTPException(
                status_code=403,
                detail="Heavy media proxying is disabled on this server to preserve bandwidth; direct client playback is required.",
            )

        if (is_hls or is_dash) and resp.status_code < 400:
            raw_bytes = bytearray()
            async for chunk in resp.aiter_bytes():
                raw_bytes.extend(chunk)
                if len(raw_bytes) > _MAX_MANIFEST_PROXY_BYTES:
                    break
            status_code = resp.status_code
            await resp.aclose()
            await client.aclose()

            if is_hls:
                text = bytes(raw_bytes).decode("utf-8", errors="replace")
                if text.lstrip("\ufeff \t\r\n").startswith("#EXTM3U"):
                    rewritten = rewrite_hls_playlist(text, current_url, referer, origin, ua)
                    return Response(
                        content=rewritten,
                        status_code=status_code,
                        media_type="application/vnd.apple.mpegurl",
                        headers=cors_headers,
                    )
            return Response(content=bytes(raw_bytes), status_code=status_code, media_type=ct, headers=cors_headers)

        await resp.aclose()
        await client.aclose()
        raise HTTPException(status_code=resp.status_code if resp.status_code >= 400 else 403, detail="Upstream manifest unavailable.")
    except UnsafeURLError as exc:
        await client.aclose()
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except httpx.HTTPError as exc:
        await client.aclose()
        raise HTTPException(status_code=502, detail=f"Proxy upstream error: {exc}") from exc


@app.post("/api/rooms")
async def create_room() -> dict[str, str]:
    room_id = room_manager.create_room()
    return {"room_id": room_id}


@app.get("/api/rooms/{room_id}")
async def get_room(room_id: str) -> dict:
    summary = room_manager.get_room_summary(room_id)
    if not summary:
        raise HTTPException(status_code=404, detail="Room not found.")
    return summary


@app.post("/api/rooms/{room_id}/media")
async def post_room_media(room_id: str, payload: dict) -> dict:
    media = payload.get("media") if isinstance(payload, dict) else None
    if not isinstance(media, dict) or not isinstance(media.get("playback"), dict):
        raise HTTPException(status_code=400, detail="Invalid media payload.")
    return await room_manager.set_media_http(
        room_id=room_id,
        media=media,
        auto_play=bool(payload.get("auto_play", True)),
        sender_name=str(payload.get("by") or "Admin"),
    )


@app.websocket("/ws/rooms/{room_id}")
async def room_websocket(
    websocket: WebSocket,
    room_id: str,
    nickname: str = Query(default="Guest"),
) -> None:
    room, client_id = await room_manager.join(room_id, websocket, nickname)
    try:
        while True:
            data = await websocket.receive_json()
            if isinstance(data, dict):
                await room_manager.handle_message(room.room_id, client_id, data)
    except WebSocketDisconnect:
        await room_manager.leave(room.room_id, client_id)
    except Exception:
        await room_manager.leave(room.room_id, client_id)


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


@app.get("/manifest.json")
async def manifest() -> FileResponse:
    return FileResponse(STATIC_DIR / "manifest.json", media_type="application/manifest+json")


@app.get("/sw.js")
async def service_worker() -> FileResponse:
    return FileResponse(STATIC_DIR / "sw.js", media_type="application/javascript")
