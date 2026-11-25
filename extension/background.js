// StreamSync Media Detector — Manifest V3 Background Worker
// Detects non-DRM media streams (.m3u8, .mpd, .mp4, .webm, audio/*) per tab.

const api = typeof browser !== "undefined" ? browser : chrome;

// tabId -> Map<url, MediaEntry>
const tabMedia = new Map();

const SEGMENT_EXT_RE = /\.(?:ts|m4s|cmfv|cmfa|vtt|srt|key)(?:\?|$)/i;
const HLS_RE = /\.m3u8(?:\?|$)/i;
const DASH_RE = /\.mpd(?:\?|$)/i;
const VIDEO_EXT_RE = /\.(?:mp4|webm|m4v|mov)(?:\?|$)/i;
const AUDIO_EXT_RE = /\.(?:m4a|mp3|aac|ogg|opus|wav|flac)(?:\?|$)/i;
const VIDEO_HINT_RE = /(?:video|vcodec|mime=video)/i;
const AUDIO_HINT_RE = /(?:audio|acodec|mime=audio)/i;

function getHeader(headers, name) {
  if (!headers) return "";
  const lower = name.toLowerCase();
  const found = headers.find((h) => h.name && h.name.toLowerCase() === lower);
  return found && found.value ? found.value.toLowerCase() : "";
}

function classifyMedia(url, contentType) {
  if (!url || !/^https?:\/\//i.test(url)) return null;
  if (SEGMENT_EXT_RE.test(url)) return null;

  const isHls =
    HLS_RE.test(url) ||
    contentType.includes("mpegurl") ||
    contentType.includes("vnd.apple.mpegurl");
  if (isHls) {
    return { kind: "combined", ext: "m3u8", is_hls: true, is_dash: false, label: "HLS Stream (.m3u8)" };
  }

  const isDash = DASH_RE.test(url) || contentType.includes("dash+xml");
  if (isDash) {
    return { kind: "combined", ext: "mpd", is_hls: false, is_dash: true, label: "DASH Stream (.mpd)" };
  }

  const isAudio = contentType.startsWith("audio/") || AUDIO_EXT_RE.test(url) || AUDIO_HINT_RE.test(url);
  const isVideo = contentType.startsWith("video/") || VIDEO_EXT_RE.test(url) || VIDEO_HINT_RE.test(url);

  if (isAudio && !isVideo) {
    const ext = (url.match(AUDIO_EXT_RE)?.[0] || ".audio").replace(/^\.|\?.*$/g, "");
    return { kind: "audio", ext, is_hls: false, is_dash: false, label: `Audio (${ext.toUpperCase()})` };
  }

  if (isVideo) {
    const ext = (url.match(VIDEO_EXT_RE)?.[0] || ".mp4").replace(/^\.|\?.*$/g, "");
    // Distinguish likely video-only adaptive tracks when URL hints indicate video-only
    const isVideoOnlyHint = VIDEO_HINT_RE.test(url) && !url.toLowerCase().includes("audio");
    return {
      kind: isVideoOnlyHint ? "video" : "combined",
      ext,
      is_hls: false,
      is_dash: false,
      label: isVideoOnlyHint ? `Video Track (${ext.toUpperCase()})` : `Video (${ext.toUpperCase()})`,
    };
  }

  return null;
}

function updateBadge(tabId) {
  const map = tabMedia.get(tabId);
  const count = map ? map.size : 0;
  try {
    api.action.setBadgeText({ tabId, text: count > 0 ? String(count) : "" });
    if (count > 0) {
      api.action.setBadgeBackgroundColor({ tabId, color: "#6366f1" });
    }
  } catch {
    // Ignore if tab closed
  }
}

api.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.tabId < 0) return;
    if (details.statusCode && (details.statusCode < 200 || details.statusCode >= 400)) return;

    const contentType = getHeader(details.responseHeaders, "content-type");
    const info = classifyMedia(details.url, contentType);
    if (!info) return;

    if (!tabMedia.has(details.tabId)) {
      tabMedia.set(details.tabId, new Map());
    }
    const map = tabMedia.get(details.tabId);
    if (!map.has(details.url)) {
      map.set(details.url, {
        url: details.url,
        ...info,
        contentType,
        detectedAt: Date.now(),
      });
      updateBadge(details.tabId);
    }
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

api.tabs.onRemoved.addListener((tabId) => {
  tabMedia.delete(tabId);
});

api.tabs.onUpdated.addListener((tabId, changeInfo) => {
  if (changeInfo.status === "loading" && changeInfo.url) {
    tabMedia.delete(tabId);
    updateBadge(tabId);
  }
});

api.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg && msg.type === "GET_TAB_MEDIA") {
    const map = tabMedia.get(msg.tabId);
    sendResponse({ items: map ? Array.from(map.values()) : [] });
    return true;
  }
  if (msg && msg.type === "CLEAR_TAB_MEDIA") {
    tabMedia.delete(msg.tabId);
    updateBadge(msg.tabId);
    sendResponse({ ok: true });
    return true;
  }
  return false;
});
