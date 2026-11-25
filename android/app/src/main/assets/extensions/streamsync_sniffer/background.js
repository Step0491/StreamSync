"use strict";

// StreamSync Built-In Firefox WebExtension (GeckoView)
// Uses webRequestBlocking (uBlock-style Ad/Tracker/VAST Shield) +
// webRequest.onHeadersReceived + filterResponseData (exact Video DownloadHelper API)
// to read real Content-Type headers & Cloudflare-authenticated manifests/container headers directly from Gecko RAM.

const port = browser.runtime.connectNative("streamsync_bridge");

let shieldEnabled = true;
let guestAutoPlayActive = false;

port.onMessage.addListener((msg) => {
  if (!msg || typeof msg !== "object") return;
  if (msg.type === "set_shield") {
    shieldEnabled = Boolean(msg.enabled);
  } else if (msg.type === "set_guest_autoplay") {
    guestAutoPlayActive = Boolean(msg.active);
  }
});

function postToAndroid(payload) {
  try {
    port.postMessage(payload);
  } catch (_) {}
}

// ---------- 1. uBlock-Style Ad, Tracker & VAST/IMA Blocker ----------

const AD_DOMAIN_SUFFIXES = [
  "doubleclick.net",
  "googlesyndication.com",
  "googleadservices.com",
  "imasdk.googleapis.com",
  "adservice.google.com",
  "exoclick.com",
  "exosrv.com",
  "realsrv.com",
  "tsyndicate.com",
  "popads.net",
  "popcash.net",
  "propellerads.com",
  "onclickads.net",
  "hilltopads.net",
  "adsterra.com",
  "highperformanceformat.com",
  "profitablecpmrate.com",
  "topcreativeformat.com",
  "acscdn.com",
  "clickadu.com",
  "galaksion.com",
  "juicyads.com",
  "trafficjunky.net",
  "trafficstars.com",
  "adcash.com",
  "bidvertiser.com",
  "revcontent.com",
  "mgid.com",
  "taboola.com",
  "outbrain.com",
  "admaven.com",
  "adnxs.com",
  "criteo.com",
  "criteo.net",
  "pubmatic.com",
  "rubiconproject.com",
  "openx.net",
  "smartadserver.com",
  "teads.tv",
  "spotxchange.com",
  "springserve.com",
  "fwmrm.net",
  "innovid.com",
  "moatads.com",
  "doubleverify.com",
  "adsafeprotected.com",
  "google-analytics.com",
  "googletagmanager.com",
  "scorecardresearch.com",
  "quantserve.com",
  "hotjar.com",
  "clarity.ms",
  "jwpltx.com",
];

const AD_URL_RE = /(\/vast\.xml|\/vast\?|\/vpaid\.|\/preroll|\/midroll|\/popunder|\/adserver\/|\/pagead\/|ad_type=video|ima3\.js)/i;
const WHITELIST_HOSTS = ["cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com", "ssl.p.jwpcdn.com", "vjs.zencdn.net"];

function hostMatchesList(host, list) {
  for (let i = 0; i < list.length; i++) {
    const d = list[i];
    if (host === d || host.endsWith("." + d)) return true;
  }
  return false;
}

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (!shieldEnabled || details.type === "main_frame") return {};
    try {
      const u = new URL(details.url);
      const host = u.hostname.toLowerCase();
      if (hostMatchesList(host, WHITELIST_HOSTS)) return {};
      if (hostMatchesList(host, AD_DOMAIN_SUFFIXES) || AD_URL_RE.test(details.url)) {
        postToAndroid({ type: "blocked_request", url: details.url });
        return { cancel: true };
      }
    } catch (_) {}
    return {};
  },
  { urls: ["http://*/*", "https://*/*"] },
  ["blocking"]
);

// ---------- 2. Capture Exact Request Headers (Referer, Origin, User-Agent, Cookie) ----------

const requestHeadersMap = new Map();

browser.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    if (!details.requestId || !details.requestHeaders) return;
    const picked = {};
    for (let i = 0; i < details.requestHeaders.length; i++) {
      const h = details.requestHeaders[i];
      if (!h || !h.name || !h.value) continue;
      const low = h.name.toLowerCase();
      if (low === "referer" || low === "origin" || low === "user-agent" || low === "cookie" || low === "authorization") {
        picked[h.name] = h.value;
      }
    }
    requestHeadersMap.set(details.requestId, {
      url: details.url,
      headers: picked,
      originUrl: details.originUrl || details.documentUrl || "",
    });
    if (requestHeadersMap.size > 500) {
      const firstKey = requestHeadersMap.keys().next().value;
      requestHeadersMap.delete(firstKey);
    }
  },
  { urls: ["http://*/*", "https://*/*"] },
  ["requestHeaders"]
);

// ---------- 3. Video DownloadHelper Engine: onHeadersReceived + filterResponseData ----------

const SEGMENT_RE = /\.(ts|m4s|cmfv|cmfa|vtt|srt|webvtt|key|jpg|jpeg|png|gif|webp|svg|css|js|mjs|woff2?|ttf|ico)(\?|#|$)/i;
const MANIFEST_CT_RE = /(mpegurl|dash\+xml)/i;
const MANIFEST_URL_RE = /\.(m3u8|mpd)(\?|#|$)/i;
const MEDIA_CT_RE = /^(video\/|audio\/|application\/mp4|application\/ogg)/i;
const MEDIA_URL_RE = /\.(mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|flac)(\?|#|$)/i;
const NON_MEDIA_CT_RE = /^(text\/html|application\/json|text\/javascript|application\/javascript|image\/|text\/css|font\/)/i;

function getRespHeader(headers, name) {
  if (!headers) return "";
  const target = name.toLowerCase();
  for (let i = 0; i < headers.length; i++) {
    if (headers[i].name && headers[i].name.toLowerCase() === target) {
      return headers[i].value || "";
    }
  }
  return "";
}

function uint8ToBase64(bytes) {
  let binary = "";
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunkSize));
  }
  return btoa(binary);
}

function concatChunks(chunks, totalLen) {
  const out = new Uint8Array(totalLen);
  let offset = 0;
  for (let i = 0; i < chunks.length; i++) {
    out.set(chunks[i], offset);
    offset += chunks[i].byteLength;
  }
  return out;
}

const tappedMediaKeys = new Set();

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.statusCode < 200 || details.statusCode >= 400) return {};
    const url = details.url || "";
    if (!url.startsWith("http://") && !url.startsWith("https://")) return {};
    if (SEGMENT_RE.test(url)) return {};
    // Ignore YouTube UMP/SABR proprietary chunks (handled by content.js playerResponse extractor)
    if (/[?&]ump=1\b/i.test(url)) return {};

    const ct = getRespHeader(details.responseHeaders, "content-type").toLowerCase();
    if (ct.includes("vnd.yt-ump") || NON_MEDIA_CT_RE.test(ct)) return {};

    const cr = getRespHeader(details.responseHeaders, "content-range");
    const cl = getRespHeader(details.responseHeaders, "content-length");

    const isManifest = MANIFEST_CT_RE.test(ct) || MANIFEST_URL_RE.test(url);
    const isMediaBinary =
      !isManifest &&
      (MEDIA_CT_RE.test(ct) ||
        MEDIA_URL_RE.test(url) ||
        /[?&]mime=(video|audio)/i.test(url) ||
        /\/videoplayback\b/i.test(url));

    if (!isManifest && !isMediaBinary) return {};

    const cached = requestHeadersMap.get(details.requestId) || { headers: {}, originUrl: "" };
    const headers = Object.assign({}, cached.headers);
    if (!headers.Referer && !headers.referer && cached.originUrl) {
      headers.Referer = cached.originUrl;
    }

    // Tap the live Gecko response stream WITHOUT making any second Cloudflare-blocked request!
    if (typeof browser.webRequest.filterResponseData === "function") {
      if (isManifest) {
        try {
          const filter = browser.webRequest.filterResponseData(details.requestId);
          const chunks = [];
          let capturedBytes = 0;
          const MAX_MANIFEST_BYTES = 512 * 1024;

          filter.ondata = (event) => {
            filter.write(event.data);
            if (capturedBytes < MAX_MANIFEST_BYTES) {
              const view = new Uint8Array(event.data);
              const take = Math.min(view.byteLength, MAX_MANIFEST_BYTES - capturedBytes);
              chunks.push(view.slice(0, take));
              capturedBytes += take;
            }
          };

          filter.onstop = () => {
            filter.disconnect();
            try {
              const full = concatChunks(chunks, capturedBytes);
              const text = new TextDecoder("utf-8", { fatal: false }).decode(full);
              postToAndroid({
                type: "manifest_body",
                url,
                contentType: ct,
                body: text,
                headers,
              });
            } catch (_) {}
          };

          filter.onerror = () => {
            try { filter.disconnect(); } catch (_) {}
          };
        } catch (_) {
          // Fallback: notify URL + headers if filterResponseData unavailable
          postToAndroid({ type: "media_response", url, contentType: ct, contentRange: cr, contentLength: cl, headBase64: "", headers });
        }
        return {};
      }

      // Binary media (MP4 / WebM / M4A / Audio): passively capture ONLY first 64 KB (moov/mvhd/tkhd/hdlr!)
      const dedupKey = url.split("?")[0] + "|" + (ct.split(";")[0] || "");
      if (!tappedMediaKeys.has(dedupKey)) {
        tappedMediaKeys.add(dedupKey);
        if (tappedMediaKeys.size > 200) tappedMediaKeys.clear();
        try {
          const filter = browser.webRequest.filterResponseData(details.requestId);
          const chunks = [];
          let capturedBytes = 0;
          const MAX_HEAD_BYTES = 64 * 1024;
          let sent = false;

          const emitHead = () => {
            if (sent) return;
            sent = true;
            try {
              const full = concatChunks(chunks, capturedBytes);
              postToAndroid({
                type: "media_response",
                url,
                contentType: ct,
                contentRange: cr,
                contentLength: cl,
                headBase64: full.byteLength > 0 ? uint8ToBase64(full) : "",
                headers,
              });
            } catch (_) {}
          };

          filter.ondata = (event) => {
            filter.write(event.data);
            if (capturedBytes < MAX_HEAD_BYTES) {
              const view = new Uint8Array(event.data);
              const take = Math.min(view.byteLength, MAX_HEAD_BYTES - capturedBytes);
              chunks.push(view.slice(0, take));
              capturedBytes += take;
              if (capturedBytes >= MAX_HEAD_BYTES) {
                emitHead();
                // Disconnect filter immediately after 64 KB so video playback stays at 100% native speed!
                try { filter.disconnect(); } catch (_) {}
              }
            }
          };

          filter.onstop = () => {
            emitHead();
            try { filter.disconnect(); } catch (_) {}
          };

          filter.onerror = () => {
            emitHead();
            try { filter.disconnect(); } catch (_) {}
          };
          return {};
        } catch (_) {}
      }
    }

    // Immediate metadata notification for subsequent chunks or non-filtered streams
    postToAndroid({
      type: "media_response",
      url,
      contentType: ct,
      contentRange: cr,
      contentLength: cl,
      headBase64: "",
      headers,
    });
    return {};
  },
  { urls: ["http://*/*", "https://*/*"] },
  ["blocking", "responseHeaders"]
);

// Relay structured player data & DOM video metadata from content.js (all frames & iframes!)
browser.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || typeof msg !== "object") return;
  if (msg.type === "query_guest_autoplay") {
    sendResponse({ active: guestAutoPlayActive });
    return true;
  }
  if (msg.type === "structured_stream" || msg.type === "blocked_popup" || msg.type === "page_video_meta") {
    postToAndroid(msg);
  }
});
