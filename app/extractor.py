"""Media source extraction for public, non-DRM streams."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urljoin, urlparse

import httpx
import yt_dlp
from pydantic import BaseModel

from app.security import validate_public_url

DIRECT_VIDEO_EXTS = {".mp4", ".webm", ".mov", ".m4v", ".m3u8", ".mpd"}
DIRECT_AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".flac"}

DRM_MARKERS = ("widevine", "playready", "fairplay", "contentprotection", "com.widevine.alpha")
MEDIA_URL_PATTERN = re.compile(
    r"""(?:https?:)?//[^\s"'<>\\]+\.(?:m3u8|mpd|mp4|webm|m4a|mp3)(?:\?[^\s"'<>\\]*)?""",
    re.IGNORECASE,
)


class ExtractionError(Exception):
    """Base error for media extraction failures."""


class DRMProtectedError(ExtractionError):
    """Raised when the target stream is DRM-protected."""


class MediaFormat(BaseModel):
    format_id: str
    url: str
    ext: str
    protocol: str | None = None
    resolution: str | None = None
    width: int | None = None
    height: int | None = None
    fps: float | None = None
    vcodec: str | None = None
    acodec: str | None = None
    tbr: float | None = None
    abr: float | None = None
    filesize: int | None = None
    format_note: str | None = None
    is_hls: bool = False
    is_dash: bool = False


class ResolvedPlayback(BaseModel):
    """Unified playback descriptor so single and separated streams play identically."""

    mode: Literal["single", "paired"]
    video_url: str
    audio_url: str | None = None
    is_hls: bool = False
    is_dash: bool = False
    label: str = ""


class SubtitleTrack(BaseModel):
    lang: str
    ext: str
    url: str
    name: str | None = None


class ExtractionResult(BaseModel):
    source_url: str
    title: str
    extractor: str
    duration: float | None = None
    thumbnail: str | None = None
    is_live: bool = False
    default_stream: ResolvedPlayback | None = None
    combined_formats: list[MediaFormat] = []
    video_only_formats: list[MediaFormat] = []
    audio_only_formats: list[MediaFormat] = []
    subtitles: list[SubtitleTrack] = []


def detect_direct_media(url: str) -> tuple[str | None, str | None]:
    """Return (kind, ext) if URL path ends with a known direct media extension."""
    path = urlparse(url).path.lower()
    ext = Path(path).suffix
    if ext in DIRECT_VIDEO_EXTS:
        return ("video", ext.lstrip("."))
    if ext in DIRECT_AUDIO_EXTS:
        return ("audio", ext.lstrip("."))
    return (None, None)


def _to_media_format(fmt: dict[str, Any]) -> MediaFormat | None:
    url = fmt.get("url")
    if not url:
        return None
    ext = (fmt.get("ext") or "unknown").lower()
    protocol = fmt.get("protocol") or ""
    vcodec = fmt.get("vcodec")
    acodec = fmt.get("acodec")
    width = fmt.get("width")
    height = fmt.get("height")
    resolution = fmt.get("resolution")
    if not resolution and width and height:
        resolution = f"{width}x{height}"

    is_hls = ext == "m3u8" or "m3u8" in protocol
    is_dash = ext == "mpd" or "dash" in protocol

    return MediaFormat(
        format_id=str(fmt.get("format_id") or ext),
        url=url,
        ext=ext,
        protocol=protocol or None,
        resolution=resolution,
        width=width,
        height=height,
        fps=fmt.get("fps"),
        vcodec=None if vcodec == "none" else vcodec,
        acodec=None if acodec == "none" else acodec,
        tbr=fmt.get("tbr"),
        abr=fmt.get("abr"),
        filesize=fmt.get("filesize") or fmt.get("filesize_approx"),
        format_note=fmt.get("format_note"),
        is_hls=is_hls,
        is_dash=is_dash,
    )


def _extract_subtitles(info: dict[str, Any]) -> list[SubtitleTrack]:
    tracks: list[SubtitleTrack] = []
    subs = info.get("subtitles") or {}
    for lang, items in subs.items():
        if not isinstance(items, list):
            continue
        for item in items:
            url = item.get("url")
            ext = item.get("ext")
            if url and ext in ("vtt", "srt"):
                tracks.append(
                    SubtitleTrack(
                        lang=lang,
                        ext=ext,
                        url=url,
                        name=item.get("name"),
                    )
                )
    return tracks


def _classify_formats(
    raw_formats: list[dict[str, Any]],
) -> tuple[list[MediaFormat], list[MediaFormat], list[MediaFormat], bool]:
    combined: list[MediaFormat] = []
    video_only: list[MediaFormat] = []
    audio_only: list[MediaFormat] = []
    saw_drm = False

    for raw in raw_formats:
        if raw.get("has_drm"):
            saw_drm = True
            continue
        mf = _to_media_format(raw)
        if not mf:
            continue
        has_video = mf.vcodec is not None
        has_audio = mf.acodec is not None

        if has_video and has_audio:
            combined.append(mf)
        elif has_video:
            video_only.append(mf)
        elif has_audio:
            audio_only.append(mf)
        elif mf.is_hls or mf.is_dash:
            combined.append(mf)

    combined.sort(key=lambda f: (f.height or 0, f.tbr or 0), reverse=True)
    video_only.sort(key=lambda f: (f.height or 0, f.tbr or 0), reverse=True)
    audio_only.sort(key=lambda f: (f.abr or 0, f.tbr or 0), reverse=True)
    return combined, video_only, audio_only, saw_drm


def choose_default_stream(
    combined: list[MediaFormat],
    video_only: list[MediaFormat],
    audio_only: list[MediaFormat],
) -> ResolvedPlayback | None:
    """Select the best unified playback stream, seamlessly pairing video+audio when optimal."""
    best_combined = combined[0] if combined else None
    best_video = video_only[0] if video_only else None
    best_audio = audio_only[0] if audio_only else None

    # Prefer adaptive HLS/DASH manifest if it's high quality or the only combined option
    if best_combined and (best_combined.is_hls or best_combined.is_dash):
        return ResolvedPlayback(
            mode="single",
            video_url=best_combined.url,
            is_hls=best_combined.is_hls,
            is_dash=best_combined.is_dash,
            label=f"{best_combined.ext.upper()} {best_combined.resolution or 'Adaptive'}".strip(),
        )

    # If separated video+audio provides higher resolution than combined (or no combined exists), pair seamlessly
    if best_video and best_audio:
        combined_height = best_combined.height or 0 if best_combined else 0
        video_height = best_video.height or 0
        if not best_combined or video_height > combined_height:
            res = best_video.resolution or f"{best_video.height}p" if best_video.height else best_video.ext.upper()
            return ResolvedPlayback(
                mode="paired",
                video_url=best_video.url,
                audio_url=best_audio.url,
                is_hls=best_video.is_hls,
                is_dash=best_video.is_dash,
                label=f"{res} (Unified Video + Audio)",
            )

    if best_combined:
        return ResolvedPlayback(
            mode="single",
            video_url=best_combined.url,
            is_hls=best_combined.is_hls,
            is_dash=best_combined.is_dash,
            label=f"{best_combined.ext.upper()} {best_combined.resolution or ''}".strip(),
        )

    if best_audio:
        return ResolvedPlayback(
            mode="single",
            video_url=best_audio.url,
            label=f"Audio {best_audio.ext.upper()}",
        )

    return None


def _fallback_extract_public_html(url: str) -> ExtractionResult | None:
    """Inspect a public HTML page for embedded non-DRM media/manifest URLs."""
    try:
        with httpx.Client(timeout=10.0, follow_redirects=False) as client:
            resp = client.get(url, headers={"User-Agent": "StreamSync/0.2"})
            if resp.is_redirect and resp.headers.get("location"):
                target = validate_public_url(urljoin(url, resp.headers["location"]))
                resp = client.get(target, headers={"User-Agent": "StreamSync/0.2"})
            resp.raise_for_status()
            html = resp.text[:500_000]
    except Exception:
        return None

    lower_html = html.lower()
    if any(marker in lower_html for marker in DRM_MARKERS):
        raise DRMProtectedError("This page uses DRM protection and cannot be extracted.")

    seen: set[str] = set()
    combined: list[MediaFormat] = []
    audio_only: list[MediaFormat] = []

    for match in MEDIA_URL_PATTERN.findall(html):
        candidate = f"https:{match}" if match.startswith("//") else match
        if candidate in seen:
            continue
        seen.add(candidate)
        kind, ext = detect_direct_media(candidate)
        if not kind or not ext:
            continue
        fmt = MediaFormat(
            format_id=f"html-{len(seen)}",
            url=candidate,
            ext=ext,
            protocol="https" if candidate.startswith("https") else "http",
            is_hls=(ext == "m3u8"),
            is_dash=(ext == "mpd"),
        )
        if kind == "video":
            combined.append(fmt)
        else:
            audio_only.append(fmt)

    if not combined and not audio_only:
        return None

    title_match = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    title = re.sub(r"\s+", " ", title_match.group(1)).strip() if title_match else "Embedded Stream"

    return ExtractionResult(
        source_url=url,
        title=title[:120],
        extractor="html-public",
        default_stream=choose_default_stream(combined, [], audio_only),
        combined_formats=combined,
        audio_only_formats=audio_only,
    )


def extract_media(raw_url: str) -> ExtractionResult:
    """Extract playable, non-DRM video/audio stream URLs from a public URL."""
    url = validate_public_url(raw_url)

    kind, ext = detect_direct_media(url)
    if kind and ext:
        fmt = MediaFormat(
            format_id="direct",
            url=url,
            ext=ext,
            protocol="https" if url.startswith("https") else "http",
            is_hls=(ext == "m3u8"),
            is_dash=(ext == "mpd"),
        )
        combined = [fmt] if kind == "video" else []
        audio_only = [fmt] if kind == "audio" else []
        return ExtractionResult(
            source_url=url,
            title=Path(urlparse(url).path).name or "Direct Stream",
            extractor="direct",
            default_stream=choose_default_stream(combined, [], audio_only),
            combined_formats=combined,
            audio_only_formats=audio_only,
        )

    ydl_opts: dict[str, Any] = {
        "skip_download": True,
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "socket_timeout": 15,
        "extract_flat": False,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except yt_dlp.utils.DownloadError as exc:
        msg = str(exc)
        if "DRM" in msg.upper():
            raise DRMProtectedError("This stream is DRM-protected and cannot be extracted.") from exc
        fallback = _fallback_extract_public_html(url)
        if fallback:
            return fallback
        raise ExtractionError(f"Failed to extract media info: {msg}") from exc

    if not isinstance(info, dict):
        raise ExtractionError("No media metadata returned for this URL.")

    if info.get("has_drm") and not info.get("formats"):
        raise DRMProtectedError("This stream is DRM-protected and cannot be extracted.")

    raw_formats = info.get("formats") or []
    if not raw_formats and info.get("url"):
        raw_formats = [info]

    combined, video_only, audio_only, saw_drm = _classify_formats(raw_formats)

    if not combined and not video_only and not audio_only:
        if saw_drm:
            raise DRMProtectedError("All available streams for this URL are DRM-protected.")
        raise ExtractionError("No playable video or audio streams found at this URL.")

    return ExtractionResult(
        source_url=info.get("webpage_url") or url,
        title=info.get("title") or "Untitled Stream",
        extractor=info.get("extractor_key") or info.get("extractor") or "unknown",
        duration=info.get("duration"),
        thumbnail=info.get("thumbnail"),
        is_live=bool(info.get("is_live")),
        default_stream=choose_default_stream(combined, video_only, audio_only),
        combined_formats=combined,
        video_only_formats=video_only,
        audio_only_formats=audio_only,
        subtitles=_extract_subtitles(info),
    )
