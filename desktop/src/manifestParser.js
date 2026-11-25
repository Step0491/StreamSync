"use strict";

/**
 * Pure-JS Media Manifest & Container Inspector for StreamSync Desktop.
 * Parses HLS (#EXTM3U Master & Media), DASH (.mpd), ISO-BMFF (.mp4/.m4a), and WebM/Matroska
 * to reliably categorize streams into COMBINED, VIDEO_ONLY, and AUDIO_ONLY.
 */

const StreamKind = Object.freeze({
  COMBINED: "COMBINED",
  VIDEO_ONLY: "VIDEO_ONLY",
  AUDIO_ONLY: "AUDIO_ONLY",
});

const TRANSIENT_RANGE_PARAMS = new Set([
  "range",
  "bytestart",
  "byteend",
  "rn",
  "rbuf",
  "ump",
  "srfvp",
]);

const SEGMENT_EXT_RE = /\.(ts|m4s|cmfv|cmfa|vtt|srt|webvtt|key|jpg|jpeg|png|gif|webp|svg|css|js|woff2?|ttf|ico)(\?|#|$)/i;
const AUDIO_SEG_RE = /\.(aac|m4a|mp3|ac3|ec3|eac3|ogg|opus|flac|cmfa)(\?|#|$)/i;
const VIDEO_ONLY_SEG_RE = /\.(cmfv|m4v|264|265|hevc)(\?|#|$)/i;
const HLS_ATTR_RE = /([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))/g;

const VIDEO_ONLY_ITAGS = new Set([
  "133", "134", "135", "136", "137", "138", "160", "242", "243", "244", "247", "248",
  "264", "266", "271", "278", "298", "299", "302", "303", "308", "313", "315",
  "394", "395", "396", "397", "398", "399", "400", "401",
]);
const AUDIO_ONLY_ITAGS = new Set([
  "139", "140", "141", "171", "172", "249", "250", "251", "256", "258", "325", "328",
]);

function isSegmentOrNoiseUrl(url) {
  if (!url || !/^https?:\/\//i.test(url)) return true;
  if (SEGMENT_EXT_RE.test(url)) return true;
  const lower = url.toLowerCase();
  if (
    lower.includes("/ptracking") ||
    lower.includes("/log_event") ||
    lower.includes("/stats/qoe") ||
    lower.includes("videostats") ||
    lower.includes("/heartbeat")
  ) {
    return true;
  }
  return false;
}

function stripRangeQueryParams(rawUrl) {
  const noHash = String(rawUrl || "").split("#")[0];
  const qIdx = noHash.indexOf("?");
  if (qIdx < 0) return noHash;
  const base = noHash.slice(0, qIdx);
  const query = noHash.slice(qIdx + 1);
  if (!query) return base;

  const kept = [];
  for (const pair of query.split("&")) {
    if (!pair) continue;
    const eq = pair.indexOf("=");
    const key = (eq >= 0 ? pair.slice(0, eq) : pair).toLowerCase();
    if (!TRANSIENT_RANGE_PARAMS.has(key)) {
      kept.push(pair);
    }
  }
  return kept.length ? `${base}?${kept.join("&")}` : base;
}

function normalizeUrlForKey(rawUrl) {
  return stripRangeQueryParams(rawUrl).trim();
}

function resolveUrl(baseUrl, rel) {
  const trimmed = String(rel || "").trim();
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  try {
    return new URL(trimmed, baseUrl).href;
  } catch {
    return trimmed;
  }
}

function friendlyCodec(raw) {
  if (!raw) return null;
  const lower = String(raw).toLowerCase().trim();
  if (lower.startsWith("avc1") || lower.startsWith("h264") || lower === "h.264") return "H.264";
  if (lower.startsWith("hev1") || lower.startsWith("hvc1") || lower.startsWith("h265")) return "HEVC";
  if (lower.startsWith("av01") || lower === "av1") return "AV1";
  if (lower.startsWith("vp09") || lower.startsWith("vp9") || lower === "v_vp9") return "VP9";
  if (lower.startsWith("vp08") || lower.startsWith("vp8") || lower === "v_vp8") return "VP8";
  if (lower.startsWith("mp4a") || lower === "aac" || lower === "a_aac") return "AAC";
  if (lower.startsWith("opus") || lower === "a_opus") return "Opus";
  if (lower.startsWith("vorbis") || lower === "a_vorbis") return "Vorbis";
  if (lower.startsWith("ec-3") || lower === "eac3") return "E-AC3";
  if (lower.startsWith("ac-3") || lower === "ac3") return "AC3";
  if (lower.startsWith("mp3") || lower === "mp4a.69" || lower === "mp4a.6b") return "MP3";
  if (lower.startsWith("flac")) return "FLAC";
  return raw.split(".")[0].toUpperCase();
}

function splitCodecs(codecsRaw) {
  if (!codecsRaw) return { vcodec: null, acodec: null };
  let vcodec = null;
  let acodec = null;
  for (const token of String(codecsRaw).split(",")) {
    const c = token.trim();
    const lower = c.toLowerCase();
    if (/^(avc1|avc3|hev1|hvc1|vp09|vp9|vp08|vp8|av01|dvh1|mp4v)/.test(lower)) {
      if (!vcodec) vcodec = c;
    } else if (/^(mp4a|ac-3|ec-3|opus|vorbis|flac|alac|mp3)/.test(lower)) {
      if (!acodec) acodec = c;
    }
  }
  return { vcodec, acodec };
}

function formatMetadataSubtitle(s) {
  const pills = [(s.container || "MP4").toUpperCase()];
  if (s.height && s.height > 0) {
    const fpsStr = s.fps && s.fps >= 48 ? String(Math.round(s.fps)) : "";
    pills.push(`${s.height}p${fpsStr}`);
  } else if (s.isMasterPlaylist) {
    pills.push("Adaptive");
  }
  if (s.language) pills.push(`Lang: ${s.language}`);
  const v = friendlyCodec(s.vcodec);
  const a = friendlyCodec(s.acodec);
  if (v && a) pills.push(`${v} + ${a}`);
  else if (v) pills.push(v);
  else if (a) pills.push(a);

  if (s.bitrateKbps && s.bitrateKbps > 0) {
    pills.push(
      s.bitrateKbps >= 1000
        ? `${(s.bitrateKbps / 1000).toFixed(1)} Mbps`
        : `${s.bitrateKbps} kbps`
    );
  }
  if (s.contentLength && s.contentLength > 0) {
    const mb = s.contentLength / (1024 * 1024);
    pills.push(
      mb >= 1024 ? `${(mb / 1024).toFixed(2)} GB` : mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.round(s.contentLength / 1024)} KB`
    );
  }
  if (s.kind === StreamKind.VIDEO_ONLY) pills.push("Video-Only (No Audio)");
  if (s.kind === StreamKind.AUDIO_ONLY) pills.push("Audio-Only");
  if (s.isDrmProtected) pills.push("DRM Protected");
  return pills.join(" • ");
}

function guessContainerExt(url, contentType) {
  let pathname = "";
  try {
    pathname = new URL(stripRangeQueryParams(url)).pathname.toLowerCase();
  } catch {
    pathname = String(url || "").split("?")[0].toLowerCase();
  }
  for (const ext of ["m3u8", "mpd", "mp4", "webm", "m4v", "mov", "m4a", "mp3", "aac", "opus", "ogg", "flac", "wav"]) {
    if (pathname.endsWith(`.${ext}`)) return ext;
  }
  const ct = String(contentType || "").toLowerCase();
  if (ct.includes("mpegurl")) return "m3u8";
  if (ct.includes("dash+xml")) return "mpd";
  if (ct.includes("webm")) return "webm";
  if (ct.includes("audio/mp4") || ct.includes("audio/x-m4a")) return "m4a";
  if (ct.includes("audio/mpeg")) return "mp3";
  if (ct.includes("audio/ogg") || ct.includes("opus")) return "opus";
  if (ct.includes("audio/aac")) return "aac";
  return "mp4";
}

function parseHlsAttributes(attrStr) {
  const attrs = {};
  HLS_ATTR_RE.lastIndex = 0;
  let m;
  while ((m = HLS_ATTR_RE.exec(attrStr)) !== null) {
    attrs[m[1].toUpperCase()] = (m[2] !== undefined ? m[2] : m[3] || "").trim();
  }
  return attrs;
}

function parseHlsManifest(manifestUrl, content, headers = {}, pageTitle = "") {
  const trimmed = String(content || "").replace(/^\uFEFF/, "").trimStart();
  if (!trimmed.startsWith("#EXTM3U")) return null;

  const lines = trimmed
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean);

  const isMaster = lines.some((l) => l.startsWith("#EXT-X-STREAM-INF:") || l.startsWith("#EXT-X-MEDIA:"));
  const sawDrm = lines.some((l) => {
    if (!l.startsWith("#EXT-X-KEY:") && !l.startsWith("#EXT-X-SESSION-KEY:")) return false;
    const up = l.toUpperCase();
    return (
      up.includes("SAMPLE-AES") ||
      up.includes("WIDEVINE") ||
      up.includes("PLAYREADY") ||
      up.includes("FAIRPLAY") ||
      up.includes("COM.APPLE.STREAMINGKEYDELIVERY")
    );
  });

  const cleanMasterUrl = stripRangeQueryParams(manifestUrl);
  const masterKey = normalizeUrlForKey(cleanMasterUrl);
  const streams = [];
  const childOverrides = new Map();

  if (isMaster) {
    const audioGroups = new Map();

    for (const line of lines) {
      if (!line.startsWith("#EXT-X-MEDIA:")) continue;
      const attrs = parseHlsAttributes(line.slice("#EXT-X-MEDIA:".length));
      if ((attrs.TYPE || "").toUpperCase() !== "AUDIO") continue;
      const groupId = attrs["GROUP-ID"] || "audio";
      const uriAttr = attrs.URI ? resolveUrl(cleanMasterUrl, attrs.URI) : null;
      const entry = {
        groupId,
        name: attrs.NAME || null,
        lang: attrs.LANGUAGE || null,
        uri: uriAttr,
        channels: attrs.CHANNELS || null,
      };
      if (!audioGroups.has(groupId)) audioGroups.set(groupId, []);
      audioGroups.get(groupId).push(entry);

      if (uriAttr) {
        const cleanAudioUrl = stripRangeQueryParams(uriAttr);
        const audioNorm = normalizeUrlForKey(cleanAudioUrl);
        const langParts = [entry.name, entry.lang && entry.lang.toLowerCase() !== (entry.name || "").toLowerCase() ? entry.lang : null].filter(Boolean);
        const langDisplay = langParts.join(" - ") || "Audio Track";
        const chSuffix = entry.channels ? ` (${entry.channels}ch)` : "";
        const audioStream = {
          id: audioNorm,
          url: cleanAudioUrl,
          normalizedUrl: audioNorm,
          kind: StreamKind.AUDIO_ONLY,
          container: "m3u8",
          label: `${langDisplay}${chSuffix} • HLS Audio`,
          width: null,
          height: null,
          fps: null,
          vcodec: null,
          acodec: "mp4a",
          bitrateKbps: null,
          contentLength: null,
          language: entry.lang || entry.name,
          isHls: true,
          isDash: false,
          isMasterPlaylist: false,
          isDrmProtected: sawDrm,
          parentManifestUrl: cleanMasterUrl,
          pageTitle,
          requestHeaders: headers,
          detectedAtMs: Date.now(),
        };
        audioStream.subtitle = formatMetadataSubtitle(audioStream);
        streams.push(audioStream);
        childOverrides.set(audioNorm, audioStream);
      }
    }

    let maxHeight = 0;
    let maxWidth = 0;
    let maxBwKbps = 0;
    let masterVCodec = null;
    let masterACodec = null;
    const anyDetachedAudio = Array.from(audioGroups.values()).some((arr) => arr.some((e) => Boolean(e.uri)));

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
      const attrs = parseHlsAttributes(line.slice("#EXT-X-STREAM-INF:".length));
      let j = i + 1;
      while (j < lines.length && lines[j].startsWith("#")) j++;
      if (j >= lines.length) break;

      const variantUrl = stripRangeQueryParams(resolveUrl(cleanMasterUrl, lines[j]));
      const variantNorm = normalizeUrlForKey(variantUrl);
      const bwBits = Number(attrs["AVERAGE-BANDWIDTH"] || attrs.BANDWIDTH || 0) || 0;
      const bwKbps = bwBits > 0 ? Math.round(bwBits / 1000) : null;
      let width = null;
      let height = null;
      if (attrs.RESOLUTION && attrs.RESOLUTION.includes("x")) {
        const [w, h] = attrs.RESOLUTION.split("x").map((n) => parseInt(n, 10));
        if (w > 0 && h > 0) {
          width = w;
          height = h;
        }
      }
      const fps = attrs["FRAME-RATE"] ? parseFloat(attrs["FRAME-RATE"]) : null;
      const { vcodec, acodec } = splitCodecs(attrs.CODECS);
      const linkedGroup = attrs.AUDIO ? audioGroups.get(attrs.AUDIO) : null;
      const hasDetachedAudio = linkedGroup ? linkedGroup.some((e) => Boolean(e.uri)) : false;

      if (height && height > maxHeight) {
        maxHeight = height;
        maxWidth = width || 0;
      }
      if (bwKbps && bwKbps > maxBwKbps) maxBwKbps = bwKbps;
      if (!masterVCodec && vcodec) masterVCodec = vcodec;
      if (!masterACodec && acodec) masterACodec = acodec;

      const isVideoOnlyVariant =
        hasDetachedAudio ||
        (Boolean(vcodec) && !acodec && (anyDetachedAudio || Boolean(attrs.CODECS)));

      const resLabel = height
        ? `${height}p${fps && fps >= 48 ? Math.round(fps) : ""}`
        : bwKbps
          ? `${bwKbps} kbps`
          : "Variant";

      const variantStream = {
        id: variantNorm,
        url: variantUrl,
        normalizedUrl: variantNorm,
        kind: isVideoOnlyVariant ? StreamKind.VIDEO_ONLY : StreamKind.COMBINED,
        container: "m3u8",
        label: `${resLabel} • ${isVideoOnlyVariant ? "HLS Video-Only" : "HLS Variant"}`,
        width,
        height,
        fps,
        vcodec,
        acodec: isVideoOnlyVariant ? null : acodec,
        bitrateKbps: bwKbps,
        contentLength: null,
        language: null,
        isHls: true,
        isDash: false,
        isMasterPlaylist: false,
        isDrmProtected: sawDrm,
        parentManifestUrl: cleanMasterUrl,
        pageTitle,
        requestHeaders: headers,
        detectedAtMs: Date.now(),
      };
      variantStream.subtitle = formatMetadataSubtitle(variantStream);
      streams.push(variantStream);
      childOverrides.set(variantNorm, variantStream);
      i = j;
    }

    if (!masterACodec && audioGroups.size > 0) masterACodec = "mp4a";

    const masterStream = {
      id: masterKey,
      url: cleanMasterUrl,
      normalizedUrl: masterKey,
      kind: StreamKind.COMBINED,
      container: "m3u8",
      label: `${maxHeight > 0 ? `Adaptive (up to ${maxHeight}p)` : "Adaptive Master"} • HLS Master`,
      width: maxWidth || null,
      height: maxHeight || null,
      fps: null,
      vcodec: masterVCodec,
      acodec: masterACodec,
      bitrateKbps: maxBwKbps || null,
      language: null,
      contentLength: null,
      isHls: true,
      isDash: false,
      isMasterPlaylist: true,
      isDrmProtected: sawDrm,
      parentManifestUrl: null,
      pageTitle,
      requestHeaders: headers,
      detectedAtMs: Date.now(),
    };
    masterStream.subtitle = formatMetadataSubtitle(masterStream);
    streams.unshift(masterStream);

    const deduped = Array.from(new Map(streams.map((s) => [s.normalizedUrl, s])).values());
    return { streams: deduped, childOverrides, isDrmProtected: sawDrm };
  }

  // Media Playlist (#EXTINF)
  const segLines = lines.filter((l) => !l.startsWith("#")).slice(0, 8);
  const mapLine = lines.find((l) => l.startsWith("#EXT-X-MAP:"));
  const mapUri = mapLine ? parseHlsAttributes(mapLine.slice("#EXT-X-MAP:".length)).URI || "" : "";
  const samples = [...segLines, mapUri].filter(Boolean);
  const lowerUrl = cleanMasterUrl.toLowerCase();

  const looksAudioOnly =
    samples.some((s) => AUDIO_SEG_RE.test(s)) ||
    /(\/audio\/|[_.-]audio[_.-]|mime=audio|type=audio)/i.test(lowerUrl);
  const looksVideoOnly =
    !looksAudioOnly &&
    (samples.some((s) => VIDEO_ONLY_SEG_RE.test(s)) ||
      (/(\/video\/|[_.-]video[_.-]|mime=video)/i.test(lowerUrl) && !lowerUrl.includes("audio")));

  const kind = looksAudioOnly
    ? StreamKind.AUDIO_ONLY
    : looksVideoOnly
      ? StreamKind.VIDEO_ONLY
      : StreamKind.COMBINED;

  const single = {
    id: masterKey,
    url: cleanMasterUrl,
    normalizedUrl: masterKey,
    kind,
    container: "m3u8",
    label:
      kind === StreamKind.AUDIO_ONLY
        ? "HLS Audio Track (.m3u8)"
        : kind === StreamKind.VIDEO_ONLY
          ? "HLS Video-Only Track (.m3u8)"
          : "HLS Stream (.m3u8)",
    width: null,
    height: null,
    fps: null,
    vcodec: null,
    acodec: null,
    bitrateKbps: null,
    contentLength: null,
    language: null,
    isHls: true,
    isDash: false,
    isMasterPlaylist: false,
    isDrmProtected: sawDrm,
    parentManifestUrl: null,
    pageTitle,
    requestHeaders: headers,
    detectedAtMs: Date.now(),
  };
  single.subtitle = formatMetadataSubtitle(single);
  return { streams: [single], childOverrides, isDrmProtected: sawDrm };
}

function extractXmlAttr(tagStr, attrName) {
  const re = new RegExp(`\\b${attrName}\\s*=\\s*"([^"]*)"`, "i");
  const m = tagStr.match(re);
  return m ? m[1].trim() : "";
}

function parseDashFrameRate(raw) {
  if (!raw) return null;
  if (raw.includes("/")) {
    const [a, b] = raw.split("/").map(Number);
    return b > 0 ? a / b : null;
  }
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function parseDashManifest(manifestUrl, xmlContent, headers = {}, pageTitle = "") {
  const text = String(xmlContent || "").trim();
  if (!/<MPD\b/i.test(text)) return null;

  const lowerText = text.toLowerCase();
  const sawDrm =
    lowerText.includes("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") ||
    lowerText.includes("9a04f079-9840-4286-ab92-e65be0885f95") ||
    lowerText.includes("widevine") ||
    lowerText.includes("playready");

  const cleanMpdUrl = stripRangeQueryParams(manifestUrl);
  const mpdKey = normalizeUrlForKey(cleanMpdUrl);
  const streams = [];

  let hasVideo = false;
  let hasAudio = false;
  let maxHeight = 0;
  let maxWidth = 0;
  let maxBwKbps = 0;
  let bestVCodec = null;
  let bestACodec = null;
  const audioLangs = new Set();

  const adaptRe = /<AdaptationSet\b([^>]*)>([\s\S]*?)<\/AdaptationSet>/gi;
  let adaptMatch;
  while ((adaptMatch = adaptRe.exec(text)) !== null) {
    const adaptAttrs = adaptMatch[1];
    const adaptBody = adaptMatch[2];
    const adaptMime = extractXmlAttr(adaptAttrs, "mimeType").toLowerCase();
    const adaptContent = extractXmlAttr(adaptAttrs, "contentType").toLowerCase();
    const adaptLang = extractXmlAttr(adaptAttrs, "lang") || null;
    const adaptCodecs = extractXmlAttr(adaptAttrs, "codecs") || null;
    const adaptHasTemplate = /<SegmentTemplate\b/i.test(adaptBody);

    const repRe = /<Representation\b([^>]*)(?:\/>|>([\s\S]*?)<\/Representation>)/gi;
    let repMatch;
    while ((repMatch = repRe.exec(adaptBody)) !== null) {
      const repAttrs = repMatch[1];
      const repBody = repMatch[2] || "";
      const mime = (extractXmlAttr(repAttrs, "mimeType") || adaptMime).toLowerCase();
      const codecs = extractXmlAttr(repAttrs, "codecs") || adaptCodecs;
      const width = parseInt(extractXmlAttr(repAttrs, "width") || extractXmlAttr(adaptAttrs, "width"), 10) || null;
      const height = parseInt(extractXmlAttr(repAttrs, "height") || extractXmlAttr(adaptAttrs, "height"), 10) || null;
      const bwBits = parseInt(extractXmlAttr(repAttrs, "bandwidth"), 10) || 0;
      const bwKbps = bwBits > 0 ? Math.round(bwBits / 1000) : null;
      const fps = parseDashFrameRate(extractXmlAttr(repAttrs, "frameRate") || extractXmlAttr(adaptAttrs, "frameRate"));

      const isVideo = adaptContent === "video" || mime.startsWith("video/") || Boolean(height && height > 0);
      const isAudio = !isVideo && (adaptContent === "audio" || mime.startsWith("audio/"));
      const { vcodec, acodec } = splitCodecs(codecs);

      if (isVideo) {
        hasVideo = true;
        if (height && height > maxHeight) {
          maxHeight = height;
          maxWidth = width || 0;
        }
        if (bwKbps && bwKbps > maxBwKbps) maxBwKbps = bwKbps;
        if (!bestVCodec) bestVCodec = vcodec || codecs;
      } else if (isAudio) {
        hasAudio = true;
        if (!bestACodec) bestACodec = acodec || codecs;
        if (adaptLang) audioLangs.add(adaptLang);
      }

      const repHasTemplate = adaptHasTemplate || /<SegmentTemplate\b/i.test(repBody);
      if (!repHasTemplate) {
        const baseMatch = repBody.match(/<BaseURL[^>]*>([^<]+)<\/BaseURL>/i);
        if (baseMatch && baseMatch[1].trim() && !baseMatch[1].trim().endsWith("/")) {
          const resolved = stripRangeQueryParams(resolveUrl(cleanMpdUrl, baseMatch[1].trim()));
          const norm = normalizeUrlForKey(resolved);
          const ext = guessContainerExt(resolved, mime);
          if (isVideo) {
            const vItem = {
              id: norm,
              url: resolved,
              normalizedUrl: norm,
              kind: StreamKind.VIDEO_ONLY,
              container: ext,
              label: `${height ? `${height}p` : "Video"} • DASH Video-Only`,
              width,
              height,
              fps,
              vcodec: vcodec || codecs,
              acodec: null,
              bitrateKbps: bwKbps,
              contentLength: null,
              language: null,
              isHls: false,
              isDash: false,
              isMasterPlaylist: false,
              isDrmProtected: sawDrm,
              parentManifestUrl: cleanMpdUrl,
              pageTitle,
              requestHeaders: headers,
              detectedAtMs: Date.now(),
            };
            vItem.subtitle = formatMetadataSubtitle(vItem);
            streams.push(vItem);
          } else if (isAudio) {
            const aItem = {
              id: norm,
              url: resolved,
              normalizedUrl: norm,
              kind: StreamKind.AUDIO_ONLY,
              container: ext,
              label: `${adaptLang ? adaptLang.toUpperCase() : "Audio"}${bwKbps ? ` (${bwKbps} kbps)` : ""} • DASH Audio`,
              width: null,
              height: null,
              fps: null,
              vcodec: null,
              acodec: acodec || codecs,
              bitrateKbps: bwKbps,
              contentLength: null,
              language: adaptLang,
              isHls: false,
              isDash: false,
              isMasterPlaylist: false,
              isDrmProtected: sawDrm,
              parentManifestUrl: cleanMpdUrl,
              pageTitle,
              requestHeaders: headers,
              detectedAtMs: Date.now(),
            };
            aItem.subtitle = formatMetadataSubtitle(aItem);
            streams.push(aItem);
          }
        }
      }
    }
  }

  const mpdKind =
    hasVideo && hasAudio
      ? StreamKind.COMBINED
      : hasVideo
        ? StreamKind.VIDEO_ONLY
        : hasAudio
          ? StreamKind.AUDIO_ONLY
          : StreamKind.COMBINED;

  const masterDash = {
    id: mpdKey,
    url: cleanMpdUrl,
    normalizedUrl: mpdKey,
    kind: mpdKind,
    container: "mpd",
    label: `${maxHeight > 0 ? `Adaptive (up to ${maxHeight}p)` : "Adaptive DASH"} • DASH (.mpd)`,
    width: maxWidth || null,
    height: maxHeight || null,
    fps: null,
    vcodec: bestVCodec,
    acodec: bestACodec,
    bitrateKbps: maxBwKbps || null,
    contentLength: null,
    language: audioLangs.size ? Array.from(audioLangs).join("/") : null,
    isHls: false,
    isDash: true,
    isMasterPlaylist: true,
    isDrmProtected: sawDrm,
    parentManifestUrl: null,
    pageTitle,
    requestHeaders: headers,
    detectedAtMs: Date.now(),
  };
  masterDash.subtitle = formatMetadataSubtitle(masterDash);
  streams.unshift(masterDash);

  const deduped = Array.from(new Map(streams.map((s) => [s.normalizedUrl, s])).values());
  return { streams: deduped, isDrmProtected: sawDrm };
}

function findIsoHandlerTypes(buf) {
  const found = new Set();
  const hdlr = Buffer.from("hdlr", "ascii");
  let idx = 4;
  while (idx <= buf.length - 16) {
    const pos = buf.indexOf(hdlr, idx);
    if (pos < 0 || pos + 16 > buf.length) break;
    found.add(buf.toString("ascii", pos + 12, pos + 16));
    idx = pos + 16;
  }
  return found;
}

function classifyByUrlAndMimeHints(rawUrl, contentType = "") {
  const lowerUrl = String(rawUrl || "").toLowerCase();
  const lowerCt = String(contentType || "").toLowerCase();
  const itagMatch = lowerUrl.match(/[?&]itag=(\d+)/);
  if (itagMatch) {
    if (VIDEO_ONLY_ITAGS.has(itagMatch[1])) return { kind: StreamKind.VIDEO_ONLY, vcodec: null, acodec: null, isDrmProtected: false };
    if (AUDIO_ONLY_ITAGS.has(itagMatch[1])) return { kind: StreamKind.AUDIO_ONLY, vcodec: null, acodec: null, isDrmProtected: false };
  }
  if (
    lowerCt.startsWith("audio/") ||
    lowerUrl.includes("mime=audio") ||
    AUDIO_SEG_RE.test(lowerUrl) ||
    /\.(m4a|mp3|aac|ogg|opus|wav|flac)(\?|$)/i.test(lowerUrl)
  ) {
    return { kind: StreamKind.AUDIO_ONLY, vcodec: null, acodec: null, isDrmProtected: false };
  }
  if (
    (lowerUrl.includes("mime=video") && (Boolean(itagMatch) || lowerUrl.includes("gir=yes") || lowerUrl.includes("aitags="))) ||
    /([/_.-]video[-_.]?only|acodec=none)/i.test(lowerUrl)
  ) {
    return { kind: StreamKind.VIDEO_ONLY, vcodec: null, acodec: null, isDrmProtected: false };
  }
  return { kind: StreamKind.COMBINED, vcodec: null, acodec: null, isDrmProtected: false };
}

function inspectContainerBytes(buffer, contentType = "", rawUrl = "") {
  const buf = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer || []);
  if (buf.length >= 16 && (buf.includes(Buffer.from("ftyp")) || buf.includes(Buffer.from("moov")))) {
    const hasMoov = buf.includes(Buffer.from("moov"));
    const isDrm =
      buf.includes(Buffer.from("encv")) ||
      buf.includes(Buffer.from("enca")) ||
      buf.includes(Buffer.from("pssh"));
    if (hasMoov) {
      const handlers = findIsoHandlerTypes(buf);
      const hasVide = handlers.has("vide");
      const hasSoun = handlers.has("soun");
      const vcodec = ["avc1", "avc3", "hev1", "hvc1", "av01", "vp09"].find((c) => buf.includes(Buffer.from(c))) || null;
      const acodec = ["mp4a", "Opus", "ec-3", "ac-3", "fLaC"].find((c) => buf.includes(Buffer.from(c)))?.toLowerCase() || null;
      if (hasVide && hasSoun) return { kind: StreamKind.COMBINED, vcodec, acodec, isDrmProtected: isDrm };
      if (hasVide) return { kind: StreamKind.VIDEO_ONLY, vcodec, acodec: null, isDrmProtected: isDrm };
      if (hasSoun) return { kind: StreamKind.AUDIO_ONLY, vcodec: null, acodec, isDrmProtected: isDrm };
    }
  }

  if (buf.length >= 32 && buf[0] === 0x1a && buf[1] === 0x45 && buf[2] === 0xdf && buf[3] === 0xa3) {
    const vcodec = buf.includes("V_AV1") ? "AV1" : buf.includes("V_VP9") ? "VP9" : buf.includes("V_VP8") ? "VP8" : null;
    const acodec = buf.includes("A_OPUS") ? "Opus" : buf.includes("A_VORBIS") ? "Vorbis" : null;
    if (vcodec && acodec) return { kind: StreamKind.COMBINED, vcodec, acodec, isDrmProtected: false };
    if (vcodec) return { kind: StreamKind.VIDEO_ONLY, vcodec, acodec: null, isDrmProtected: false };
    if (acodec) return { kind: StreamKind.AUDIO_ONLY, vcodec: null, acodec, isDrmProtected: false };
  }

  return classifyByUrlAndMimeHints(rawUrl, contentType);
}

module.exports = {
  StreamKind,
  isSegmentOrNoiseUrl,
  stripRangeQueryParams,
  normalizeUrlForKey,
  resolveUrl,
  friendlyCodec,
  splitCodecs,
  formatMetadataSubtitle,
  guessContainerExt,
  parseHlsManifest,
  parseDashManifest,
  inspectContainerBytes,
  classifyByUrlAndMimeHints,
};
