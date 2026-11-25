"""Tests for URL security validation, format separation, and API endpoints."""

from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.extractor import DRMProtectedError, extract_media
from app.main import app
from app.security import UnsafeURLError, validate_public_url

client = TestClient(app)


def test_validate_public_url_rejects_private_and_local():
    for bad in [
        "",
        "ftp://example.com/video.mp4",
        "http://localhost/video.mp4",
        "http://127.0.0.1:8000/video.mp4",
        "http://192.168.1.10/stream.m3u8",
        "http://10.0.0.1/stream.mp4",
        "http://169.254.169.254/latest/meta-data/",
    ]:
        with pytest.raises(UnsafeURLError):
            validate_public_url(bad)


def test_direct_media_url_detection():
    with patch("app.security.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]):
        result = extract_media("https://example.com/media/movie.m3u8")
    assert result.extractor == "direct"
    assert len(result.combined_formats) == 1
    assert result.combined_formats[0].is_hls is True
    assert result.combined_formats[0].ext == "m3u8"


def test_ytdlp_separates_combined_video_and_audio():
    fake_info = {
        "title": "Sample Video",
        "extractor_key": "SampleExtractor",
        "webpage_url": "https://example.com/watch?v=1",
        "duration": 120,
        "formats": [
            {"format_id": "18", "url": "https://cdn.example.com/360.mp4", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a", "height": 360},
            {"format_id": "137", "url": "https://cdn.example.com/1080v.mp4", "ext": "mp4", "vcodec": "avc1", "acodec": "none", "height": 1080},
            {"format_id": "140", "url": "https://cdn.example.com/audio.m4a", "ext": "m4a", "vcodec": "none", "acodec": "mp4a", "abr": 128},
        ],
    }

    mock_ydl = MagicMock()
    mock_ydl.__enter__.return_value.extract_info.return_value = fake_info

    with (
        patch("app.security.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]),
        patch("app.extractor.yt_dlp.YoutubeDL", return_value=mock_ydl),
    ):
        result = extract_media("https://example.com/watch?v=1")

    assert result.title == "Sample Video"
    assert len(result.combined_formats) == 1
    assert len(result.video_only_formats) == 1
    assert len(result.audio_only_formats) == 1
    assert result.video_only_formats[0].height == 1080
    assert result.audio_only_formats[0].abr == 128
    # Higher-res separated video+audio should be auto-paired as default_stream
    assert result.default_stream is not None
    assert result.default_stream.mode == "paired"
    assert result.default_stream.video_url == "https://cdn.example.com/1080v.mp4"
    assert result.default_stream.audio_url == "https://cdn.example.com/audio.m4a"


def test_drm_streams_are_rejected():
    fake_info = {
        "title": "Protected",
        "formats": [
            {"format_id": "drm1", "url": "https://cdn.example.com/enc.mpd", "ext": "mpd", "has_drm": True}
        ],
    }
    mock_ydl = MagicMock()
    mock_ydl.__enter__.return_value.extract_info.return_value = fake_info

    with (
        patch("app.security.socket.getaddrinfo", return_value=[(2, 1, 6, "", ("93.184.216.34", 443))]),
        patch("app.extractor.yt_dlp.YoutubeDL", return_value=mock_ydl),
        pytest.raises(DRMProtectedError),
    ):
        extract_media("https://example.com/protected")


def test_api_health_and_bad_url():
    res = client.get("/api/health")
    assert res.status_code == 200
    assert res.json() == {"status": "ok"}

    bad = client.post("/api/extract", json={"url": "http://127.0.0.1/test.mp4"})
    assert bad.status_code == 400


def test_proxy_blocks_ssrf_and_rewrites_hls():
    from app.main import rewrite_hls_playlist

    ssrf = client.get("/api/proxy?url=http://127.0.0.1/secret.m3u8")
    assert ssrf.status_code == 400

    raw_m3u8 = (
        "#EXTM3U\n"
        '#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",URI="audio/it.m3u8"\n'
        "#EXTINF:6.0,\n"
        "seg_001.ts\n"
    )
    rewritten = rewrite_hls_playlist(
        raw_m3u8,
        base_url="https://cdn.example.com/hls/master.m3u8",
        referer="https://site.example/",
    )
    assert "/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fhls%2Faudio%2Fit.m3u8&referer=https%3A%2F%2Fsite.example%2F" in rewritten
    assert "/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fhls%2Fseg_001.ts&referer=https%3A%2F%2Fsite.example%2F" in rewritten
