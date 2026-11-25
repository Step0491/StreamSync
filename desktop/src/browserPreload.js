"use strict";

const { ipcRenderer, webFrame } = require("electron");

// Runs inside EVERY frame (main page + every cross-origin <iframe>) via nodeIntegrationInSubFrames: true
const PAGE_HOOK_SCRIPT = `
(function() {
  if (window.__ssDesktopHooked) return;
  window.__ssDesktopHooked = true;

  const MEDIA_CT_RE = /(mpegurl|dash\\+xml|video\\/|audio\\/|application\\/mp4|application\\/ogg)/i;
  const MEDIA_URL_RE = /\\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|opus|ogg|flac)(\\?|#|$)/i;

  function emit(payload) {
    try {
      window.postMessage({ __ssDesktopMedia: true, payload: payload }, "*");
    } catch (_) {}
  }

  function reportCandidate(url, contentType, sourceTag) {
    if (!url || typeof url !== "string") return;
    if (url.startsWith("blob:") || url.startsWith("data:")) return;
    try {
      const abs = new URL(url, location.href).href;
      emit({ type: "candidate", url: abs, contentType: String(contentType || ""), sourceTag: sourceTag || "", pageUrl: location.href, pageTitle: document.title || "" });
    } catch (_) {}
  }

  // 1. Hook window.fetch
  if (typeof window.fetch === "function") {
    const origFetch = window.fetch;
    window.fetch = function(input) {
      const reqUrl = typeof input === "string" ? input : (input && input.url ? input.url : "");
      if (reqUrl && MEDIA_URL_RE.test(reqUrl)) reportCandidate(reqUrl, "", "fetch-req");
      return origFetch.apply(this, arguments).then((resp) => {
        try {
          const ct = (resp.headers && resp.headers.get("content-type")) || "";
          const finalUrl = resp.url || reqUrl;
          if (MEDIA_CT_RE.test(ct) || MEDIA_URL_RE.test(finalUrl)) {
            reportCandidate(finalUrl, ct, "fetch-resp");
          }
        } catch (_) {}
        return resp;
      });
    };
  }

  // 2. Hook XMLHttpRequest
  if (window.XMLHttpRequest) {
    const origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(_method, url) {
      this.__ssUrl = url;
      if (url && MEDIA_URL_RE.test(String(url))) reportCandidate(String(url), "", "xhr-open");
      this.addEventListener("readystatechange", function() {
        if (this.readyState === 2 || this.readyState === 4) {
          try {
            const ct = this.getResponseHeader("content-type") || "";
            const rUrl = this.responseURL || this.__ssUrl || "";
            if (MEDIA_CT_RE.test(ct) || MEDIA_URL_RE.test(String(rUrl))) {
              reportCandidate(String(rUrl), ct, "xhr-headers");
            }
          } catch (_) {}
        }
      });
      return origOpen.apply(this, arguments);
    };
  }

  // 3. DOM <video> / <audio> / PerformanceResourceTiming / YouTube playerResponse
  document.addEventListener("loadedmetadata", (e) => {
    const t = e.target;
    if (!t || (t.tagName !== "VIDEO" && t.tagName !== "AUDIO")) return;
    const src = t.currentSrc || t.src || "";
    if (src && /^https?:\\/\\//i.test(src)) {
      reportCandidate(src, t.tagName === "AUDIO" ? "audio/mp4" : "video/mp4", "loadedmetadata");
    }
    emit({
      type: "dom_meta",
      durationSec: Number.isFinite(t.duration) && t.duration > 0 ? t.duration : 0,
      width: t.videoWidth || 0,
      height: t.videoHeight || 0
    });
  }, true);

  let lastYtId = "";
  function scanFrame() {
    try {
      document.querySelectorAll("video, audio, source").forEach((el) => {
        const src = el.currentSrc || el.src || el.getAttribute("src");
        if (src) reportCandidate(src, el.getAttribute("type") || "", el.tagName.toLowerCase());
      });
    } catch (_) {}

    try {
      if (performance && typeof performance.getEntriesByType === "function") {
        performance.getEntriesByType("resource").forEach((e) => {
          if (e && e.name && (e.initiatorType === "video" || e.initiatorType === "audio")) {
            reportCandidate(e.name, e.initiatorType === "audio" ? "audio/mp4" : "video/mp4", "perf");
          }
        });
      }
    } catch (_) {}

    try {
      const mp = document.getElementById("movie_player");
      const pr = (mp && typeof mp.getPlayerResponse === "function" ? mp.getPlayerResponse() : null) || window.ytInitialPlayerResponse;
      const vidId = pr && pr.videoDetails && pr.videoDetails.videoId ? String(pr.videoDetails.videoId) : "";
      const sd = pr && pr.streamingData;
      if (sd && vidId && vidId !== lastYtId) {
        lastYtId = vidId;
        const vidDurSec = Number((pr.videoDetails && pr.videoDetails.lengthSeconds) || 0);
        const items = [];
        const all = [].concat(sd.formats || [], sd.adaptiveFormats || []);
        for (const f of all) {
          if (!f || !f.url) continue;
          const mime = String(f.mimeType || "");
          const isVideo = mime.startsWith("video/");
          const isAudio = mime.startsWith("audio/");
          const hasBoth = Boolean(f.audioQuality) && Boolean(f.width || f.height);
          const kind = hasBoth ? "COMBINED" : (isVideo ? "VIDEO_ONLY" : (isAudio ? "AUDIO_ONLY" : "COMBINED"));
          const cm = mime.match(/codecs="([^"]+)"/i);
          const codecs = cm ? cm[1] : "";
          const ext = mime.includes("webm") ? "webm" : (isAudio ? "m4a" : "mp4");
          const res = f.qualityLabel || (f.height ? f.height + "p" : "");
          const lang = (f.audioTrack && f.audioTrack.displayName) || "";
          const label = kind === "AUDIO_ONLY"
            ? ("Audio " + (lang ? lang + " " : "") + "(" + ext.toUpperCase() + ")")
            : ((res || "Video") + " • " + (kind === "VIDEO_ONLY" ? "Video-Only" : "Combined") + " (" + ext.toUpperCase() + ")");
          items.push({
            url: f.url,
            kind,
            container: ext,
            label,
            width: f.width || null,
            height: f.height || null,
            fps: f.fps || null,
            durationSec: f.approxDurationMs ? Number(f.approxDurationMs) / 1000 : vidDurSec,
            vcodec: isVideo ? codecs.split(",")[0].trim() : null,
            acodec: isAudio ? codecs.split(",")[0].trim() : (hasBoth && codecs.includes(",") ? codecs.split(",")[1].trim() : null),
            bitrateKbps: f.bitrate ? Math.round(Number(f.bitrate) / 1000) : null,
            contentLength: f.contentLength ? Number(f.contentLength) : null,
            language: lang || null,
            mimeType: mime
          });
        }
        if (sd.hlsManifestUrl) items.push({ url: sd.hlsManifestUrl, kind: "COMBINED", container: "m3u8", label: "HLS Master (YouTube)", durationSec: vidDurSec, isHls: true, isMasterPlaylist: true });
        if (sd.dashManifestUrl) items.push({ url: sd.dashManifestUrl, kind: "COMBINED", container: "mpd", label: "DASH Master (YouTube)", durationSec: vidDurSec, isDash: true, isMasterPlaylist: true });
        if (items.length) emit({ type: "structured", items, pageUrl: location.href, pageTitle: document.title || "" });
      }
    } catch (_) {}
  }

  setInterval(scanFrame, 2000);
  if (document.readyState !== "loading") scanFrame();
  else document.addEventListener("DOMContentLoaded", scanFrame, { once: true });
})();
`;

window.addEventListener("message", (event) => {
  if (event.source !== window || !event.data || !event.data.__ssDesktopMedia) return;
  ipcRenderer.send("browser:media-signal", event.data.payload);
});

try {
  webFrame.executeJavaScript(PAGE_HOOK_SCRIPT, true).catch(() => {});
} catch (_) {
  window.addEventListener("DOMContentLoaded", () => {
    try {
      webFrame.executeJavaScript(PAGE_HOOK_SCRIPT, true).catch(() => {});
    } catch (_) {}
  });
}
