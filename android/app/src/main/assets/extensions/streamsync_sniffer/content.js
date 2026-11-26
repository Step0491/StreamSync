"use strict";

// Runs in ALL frames (main page + every cross-origin video iframe!) at document_start.
(function () {
  if (window.__ssGeckoContentInjected) return;
  window.__ssGeckoContentInjected = true;

  // 1. Cosmetic Ad Hiding
  const COSMETIC_CSS = [
    ".adsbygoogle",
    "ins.adsbygoogle",
    "div[id^='div-gpt-ad']",
    "iframe[src*='doubleclick.net']",
    "iframe[src*='googlesyndication.com']",
    "iframe[src*='exosrv.com']",
    "iframe[src*='realsrv.com']",
    "iframe[src*='adsterra']",
    ".ad-overlay",
    ".video-ad-overlay",
    ".popunder-overlay",
  ].join(", ") + " { display: none !important; visibility: hidden !important; pointer-events: none !important; }";

  const injectCss = () => {
    if (document.getElementById("__ss_gecko_css__")) return;
    const style = document.createElement("style");
    style.id = "__ss_gecko_css__";
    style.textContent = COSMETIC_CSS;
    (document.head || document.documentElement).appendChild(style);
  };
  if (document.head || document.documentElement) injectCss();
  else document.addEventListener("DOMContentLoaded", injectCss, { once: true });

  // 2. Capture exact <video> / <audio> duration & resolution on loadedmetadata (even inside iframes!)
  document.addEventListener(
    "loadedmetadata",
    (e) => {
      const t = e.target;
      if (!t || (t.tagName !== "VIDEO" && t.tagName !== "AUDIO")) return;
      try {
        browser.runtime.sendMessage({
          type: "page_video_meta",
          src: t.currentSrc || t.src || "",
          durationSec: Number.isFinite(t.duration) && t.duration > 0 ? t.duration : 0,
          width: t.videoWidth || 0,
          height: t.videoHeight || 0,
          pageUrl: location.href,
          pageTitle: document.title || "",
        });
      } catch (_) {}
    },
    true
  );

  // 3. YouTube playerResponse extractor (works even when YouTube uses SABR/UMP)
  let lastYtId = "";
  function scanYouTube() {
    try {
      const mp = document.getElementById("movie_player");
      const wrapped = window.wrappedJSObject || window;
      const pr =
        (mp && mp.wrappedJSObject && typeof mp.wrappedJSObject.getPlayerResponse === "function"
          ? mp.wrappedJSObject.getPlayerResponse()
          : null) || wrapped.ytInitialPlayerResponse;
      if (!pr || !pr.streamingData) return;
      const vidId = pr.videoDetails && pr.videoDetails.videoId ? String(pr.videoDetails.videoId) : "";
      if (!vidId || vidId === lastYtId) return;
      lastYtId = vidId;

      const vidDurSec = Number((pr.videoDetails && pr.videoDetails.lengthSeconds) || 0);
      const sd = pr.streamingData;
      const all = [].concat(sd.formats || [], sd.adaptiveFormats || []);
      for (let i = 0; i < all.length; i++) {
        const f = all[i];
        if (!f || !f.url) continue;
        const mime = String(f.mimeType || "");
        const isVideo = mime.indexOf("video/") === 0;
        const isAudio = mime.indexOf("audio/") === 0;
        const hasBoth = Boolean(f.audioQuality) && Boolean(f.width || f.height);
        const kind = hasBoth ? "COMBINED" : isVideo ? "VIDEO_ONLY" : isAudio ? "AUDIO_ONLY" : "COMBINED";
        const cm = mime.match(/codecs="([^"]+)"/i);
        const codecs = cm ? cm[1] : "";
        const ext = mime.indexOf("webm") >= 0 ? "webm" : isAudio ? "m4a" : "mp4";
        const res = f.qualityLabel || (f.height ? f.height + "p" : "");
        const lang = f.audioTrack && f.audioTrack.displayName ? String(f.audioTrack.displayName) : "";
        const label =
          kind === "AUDIO_ONLY"
            ? "Audio " + (lang ? lang + " " : "") + "(" + ext.toUpperCase() + ")"
            : (res || "Video") + " • " + (kind === "VIDEO_ONLY" ? "Video-Only" : "Combined") + " (" + ext.toUpperCase() + ")";
        const vcodec = isVideo ? codecs.split(",")[0].trim() : "";
        const acodec = isAudio
          ? codecs.split(",")[0].trim()
          : hasBoth && codecs.indexOf(",") >= 0
            ? codecs.split(",")[1].trim()
            : "";
        const approxDur = f.approxDurationMs ? Number(f.approxDurationMs) / 1000 : vidDurSec;

        browser.runtime.sendMessage({
          type: "structured_stream",
          url: String(f.url),
          kind,
          container: ext,
          label,
          width: Number(f.width || 0),
          height: Number(f.height || 0),
          fps: Number(f.fps || 0),
          durationSec: Number(approxDur || 0),
          vcodec,
          acodec,
          bitrateKbps: f.bitrate ? Math.round(Number(f.bitrate) / 1000) : 0,
          contentLength: Number(f.contentLength || 0),
          language: lang,
          isHls: false,
          isDash: false,
          pageUrl: location.href,
          pageTitle: document.title || "",
        });
      }
      if (sd.hlsManifestUrl) {
        browser.runtime.sendMessage({
          type: "structured_stream",
          url: String(sd.hlsManifestUrl),
          kind: "COMBINED",
          container: "m3u8",
          label: "HLS Master (YouTube)",
          durationSec: vidDurSec,
          isHls: true,
          isDash: false,
          pageUrl: location.href,
          pageTitle: document.title || "",
        });
      }
      if (sd.dashManifestUrl) {
        browser.runtime.sendMessage({
          type: "structured_stream",
          url: String(sd.dashManifestUrl),
          kind: "COMBINED",
          container: "mpd",
          label: "DASH Master (YouTube)",
          durationSec: vidDurSec,
          isHls: false,
          isDash: true,
          pageUrl: location.href,
          pageTitle: document.title || "",
        });
      }
    } catch (_) {}
  }

  // 4. Silent Auto-Play inside all frames & cross-origin iframes when Guest is extracting a fresh stream
  let autoPlayAttempts = 0;
  function trySilentGuestAutoPlay() {
    if (autoPlayAttempts >= 15) return;
    try {
      browser.runtime.sendMessage({ type: "query_guest_autoplay" }).then((resp) => {
        if (!resp || !resp.active) {
          autoPlayAttempts = 0;
          return;
        }
        const v = document.querySelector("video");
        if (v) {
          v.muted = true;
          v.volume = 0;
          if (!v.paused && v.readyState >= 2) {
            return;
          }
          if (v.paused) {
            v.play().catch(() => {});
          }
        }
        autoPlayAttempts++;
        const playBtn = document.querySelector(
          ".vjs-big-play-button, .vjs-play-control, .plyr__control--overlaid, [data-plyr='play'], .jw-icon-display, .jw-display-icon-container, .jw-icon-playback, [aria-label*='play' i], [title*='play' i], .play-button, .play-btn, #play-btn, [class*='play-btn' i], [class*='playBtn' i]"
        );
        if (playBtn && typeof playBtn.click === "function") {
          playBtn.click();
        } else if (v && typeof v.click === "function") {
          v.click();
        }
      }).catch(() => {});
    } catch (_) {}
  }

  setInterval(() => {
    scanYouTube();
    trySilentGuestAutoPlay();
  }, 1200);
})();
