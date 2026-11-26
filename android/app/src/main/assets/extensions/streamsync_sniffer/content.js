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

  // 4. Record Admin's real clicks on links / buttons / players across all frames so Guest can replay them
  let isGuestReplayingNow = false;
  function buildSimpleSelector(el) {
    if (!el || el.nodeType !== 1) return "";
    if (el.id) return "#" + CSS.escape(el.id);
    const parts = [];
    let cur = el;
    for (let depth = 0; depth < 4 && cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement; depth++) {
      let seg = cur.tagName.toLowerCase();
      if (cur.id) {
        parts.unshift("#" + CSS.escape(cur.id));
        break;
      }
      const cls = typeof cur.className === "string"
        ? cur.className.trim().split(/\s+/).filter((c) => c && c.length < 32 && !/^(active|hover|focus|selected)$/i.test(c)).slice(0, 2)
        : [];
      if (cls.length) {
        seg += "." + cls.map((c) => CSS.escape(c)).join(".");
      } else if (cur.parentElement) {
        const sibs = Array.from(cur.parentElement.children).filter((s) => s.tagName === cur.tagName);
        if (sibs.length > 1) {
          seg += ":nth-of-type(" + (sibs.indexOf(cur) + 1) + ")";
        }
      }
      parts.unshift(seg);
      cur = cur.parentElement;
    }
    return parts.join(" > ");
  }

  document.addEventListener(
    "pointerdown",
    (e) => {
      if (isGuestReplayingNow || !e.isTrusted) return;
      const raw = e.target;
      if (!raw || raw.nodeType !== 1) return;
      const interactive = raw.closest("a, button, [role='button'], [onclick], video, [class*='play' i], [class*='server' i], [class*='watch' i], [class*='player' i]") || raw;
      const anchor = raw.closest("a");
      const href = anchor && anchor.href && /^https?:\/\//i.test(anchor.href) ? anchor.href : "";
      const iw = Math.max(1, window.innerWidth || 1);
      const ih = Math.max(1, window.innerHeight || 1);
      try {
        browser.runtime.sendMessage({
          type: "user_click",
          click: {
            tag: (interactive.tagName || "").toLowerCase(),
            id: (interactive.id || "").slice(0, 80),
            selector: buildSimpleSelector(interactive).slice(0, 220),
            href: href.slice(0, 512),
            text: (interactive.innerText || interactive.getAttribute("aria-label") || interactive.getAttribute("title") || "").replace(/\s+/g, " ").trim().slice(0, 80),
            frame_url: location.href.slice(0, 512),
            is_top: window === window.top,
            rel_x: Number((e.clientX / iw).toFixed(4)),
            rel_y: Number((e.clientY / ih).toFixed(4)),
          },
        });
      } catch (_) {}
    },
    true
  );

  // 5. Silent Click-Replay + Auto-Play inside all frames & cross-origin iframes when Guest is extracting
  let autoPlayAttempts = 0;
  const replayedKeys = new Set();

  function frameMatchesClick(c) {
    if (!c) return false;
    const isTopHere = window === window.top;
    if (Boolean(c.is_top) !== isTopHere) return false;
    if (isTopHere) return true;
    try {
      const targetUrl = new URL(c.frame_url || "");
      return targetUrl.hostname === location.hostname;
    } catch (_) {
      return true;
    }
  }

  function findClickTarget(c) {
    if (c.selector) {
      try {
        const bySel = document.querySelector(c.selector);
        if (bySel) return bySel;
      } catch (_) {}
    }
    if (c.href) {
      try {
        const pathPart = new URL(c.href).pathname;
        const anchors = document.querySelectorAll("a[href]");
        for (let i = 0; i < anchors.length; i++) {
          if (anchors[i].href === c.href || (pathPart && pathPart.length > 3 && anchors[i].getAttribute("href")?.includes(pathPart))) {
            return anchors[i];
          }
        }
      } catch (_) {}
    }
    if (c.text && c.text.length >= 2) {
      const needle = c.text.toLowerCase();
      const cand = document.querySelectorAll(c.tag || "a, button, [role='button'], div, span");
      for (let i = 0; i < cand.length; i++) {
        const el = cand[i];
        const t = (el.innerText || el.getAttribute("aria-label") || el.getAttribute("title") || "").replace(/\s+/g, " ").trim().toLowerCase();
        if (t && (t === needle || (needle.length >= 4 && t.includes(needle)))) {
          return el;
        }
      }
    }
    if (typeof c.rel_x === "number" && typeof c.rel_y === "number" && c.rel_x > 0 && c.rel_y > 0) {
      const x = Math.round(c.rel_x * (window.innerWidth || 0));
      const y = Math.round(c.rel_y * (window.innerHeight || 0));
      if (x > 0 && y > 0) {
        return document.elementFromPoint(x, y);
      }
    }
    return null;
  }

  function trySilentGuestAutoPlay() {
    if (autoPlayAttempts >= 22) return;
    try {
      browser.runtime.sendMessage({ type: "query_guest_autoplay" }).then((resp) => {
        if (!resp || !resp.active) {
          isGuestReplayingNow = false;
          autoPlayAttempts = 0;
          replayedKeys.clear();
          return;
        }
        isGuestReplayingNow = true;
        const clicks = Array.isArray(resp.replay_clicks) ? resp.replay_clicks : [];
        for (let i = 0; i < clicks.length; i++) {
          const c = clicks[i];
          const key = i + "|" + (c.selector || c.href || c.text || "");
          if (replayedKeys.has(key) || !frameMatchesClick(c)) continue;
          const targetEl = findClickTarget(c);
          if (targetEl) {
            replayedKeys.add(key);
            try { targetEl.scrollIntoView({ block: "center", behavior: "instant" }); } catch (_) {}
            try { targetEl.click(); } catch (_) {}
            try { targetEl.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, view: window })); } catch (_) {}
            break;
          }
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
  }, 900);
})();
