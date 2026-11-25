# StreamSync

A mobile-first web app, installable Android PWA, and Manifest V3 Browser Extension for:
1. **Step 1 (Media Extractor & Unified Player):** Extracts public, non-DRM video & audio stream sources (`yt-dlp` + direct `.mp4`/`.m3u8`/`.mpd` + public HTML fallback) and plays complete streams, HLS/DASH multi-audio streams, or separated Video + Audio tracks seamlessly in sync.
2. **Step 2 (Watch-Party Rooms):** Real-time WebSocket rooms (play/pause/seek/rate sync, host management, invite links, and live chat — Rave / Watch2Gether style).
3. **Browser Extension (`extension/`):** Manifest V3 network media detector (like Video DownloadHelper, without needing a desktop Companion App) that detects non-DRM `.m3u8`, `.mpd`, `.mp4`, `.webm`, and separate audio streams on the active tab and sends them directly to StreamSync (Solo or Room).

## Run Locally

```powershell
cd C:\Users\Stefano\.gemini\antigravity\scratch\streamsync
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

Open `http://localhost:8000` on PC, or `http://<your-PC-LAN-IP>:8000` on Android (installable as a PWA via Chrome/Firefox **Add to Home screen**).

## Load the Browser Extension (`extension/`)

- **Chrome / Edge (Desktop):**
  1. Open `chrome://extensions` (or `edge://extensions`) and enable **Developer mode**.
  2. Click **Load unpacked** and select `C:\Users\Stefano\.gemini\antigravity\scratch\streamsync\extension`.
- **Firefox (Desktop):**
  1. Open `about:debugging#/runtime/this-firefox` -> **Load Temporary Add-on...** -> select `extension/manifest.json`.
- **Firefox for Android:**
  1. Package `extension/` as a `.zip` / `.xpi` (or use `web-ext run -t firefox-android`) and set the **StreamSync Server URL** in the popup to `http://<your-PC-LAN-IP>:8000` (or your hosted HTTPS URL).

## Run Tests

```powershell
.\.venv\Scripts\python.exe -m pytest -v
```

## Scope & Security Notes
- Only public, **non-DRM** streams are supported. DRM-protected services are rejected and not bypassed.
- URL validation blocks `localhost`, `.local`, and private/link-local/reserved IP ranges to prevent SSRF.
