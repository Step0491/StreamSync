# StreamSync

A native **Android** media-streaming & watch-party application (with a shared **FastAPI + WebSocket Room** backend, **Web/Electron-ready** dual-track player, and **Manifest V3 Browser Extension**):

1. **3-Layer Ad, Tracker & Popup Shield (`android/.../adblock/`):**
   - Network-level uBlock/EasyList static filter engine (`||domain^`, `$third-party`, `$script`, `$media`, `$xhr`, `@@` exceptions) inside `shouldInterceptRequest` so VAST/IMA pre-rolls never load or pollute stream discovery.
   - Multi-window popup (`onCreateWindow`), non-HTTP scheme hijack (`shouldOverrideUrlLoading`), and JS `window.open` / clickjack overlay neutralizer, with a per-site toolbar toggle (`Shield: ON (N)` / `Shield: OFF`).
2. **State-of-the-Art Media Stream Extractor (`android/.../extractor/` + `app/extractor.py`):**
   - Multi-signal sniffer combining Chromium network interception (capturing `Referer`, `Origin`, `User-Agent`, and `Cookie` headers) with a DOM/JS `fetch` + `XMLHttpRequest` + `<video>`/`<audio>` hook for extensionless manifest endpoints.
   - Deep asynchronous **HLS (`#EXTM3U`)**, **DASH (`.mpd`)**, and **ISO-BMFF/WebM byte-range header** parser separating streams into **Combined (Video+Audio)**, **Video-Only**, and **Audio-Only** with resolution, FPS, codecs, bitrate/size, and language metadata.
3. **Manual Track Pairing & Real-Time Watch-Party Sync (`android/.../player/` + `app/rooms.py` + `static/`):**
   - Tabbed Material3 Stream Selector (`Combined`, `Pair V + A`, `Video-Only`, `Audio-Only`) allowing manual selection of **1 Video URL + 1 Audio URL** (or 1 Combined stream).
   - **Android:** Native **AndroidX Media3 `ExoPlayer`** using `MergingMediaSource` for hardware-locked A/V sync + `DefaultHttpDataSource` header injection, docked above the live Room UI via `@JavascriptInterface`.
   - **Web / Desktop (`.exe` ready):** Dual `hls.js` / `dash.js` video+audio phase-locked player, RTT clock-offset compensation (`ping`/`pong`), **Host-Only / Everyone** permission toggle, and a **Pause-for-All Buffering Barrier**.

## Run Backend & Web Room Locally

```powershell
cd C:\Users\STEFANO\.gemini\antigravity\scratch\StreamSync
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

## Run Tests

```powershell
# Backend & Watch-Party WebSocket Room tests
.\.venv\Scripts\python.exe -m pytest -v

# Android JVM Unit Tests & Debug APK Build (requires JDK 17+ & Android SDK 34)
cd android
gradle testDebugUnitTest assembleDebug
```

## Scope & Security Notes
- Only public, **non-DRM** streams are supported. DRM-protected streams (`Widevine`, `PlayReady`, `FairPlay`, `SAMPLE-AES`) are flagged and rejected.
- Server-side URL extraction blocks `localhost`, `.local`, and private/link-local/reserved IP ranges to prevent SSRF, and room relays strip sensitive `Cookie` headers before broadcasting metadata to peers.
