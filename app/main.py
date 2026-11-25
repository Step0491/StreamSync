"""FastAPI application for StreamSync (Step 1: Extractor + Step 2: Watch Rooms)."""

from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from app.extractor import DRMProtectedError, ExtractionError, ExtractionResult, extract_media
from app.rooms import RoomManager
from app.security import UnsafeURLError

BASE_DIR = Path(__file__).resolve().parent.parent
STATIC_DIR = BASE_DIR / "static"

app = FastAPI(title="StreamSync", version="0.2.0")
room_manager = RoomManager()


class ExtractRequest(BaseModel):
    url: str


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
