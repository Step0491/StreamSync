"use strict";

const {
  StreamKind,
  isSegmentOrNoiseUrl,
  stripRangeQueryParams,
  normalizeUrlForKey,
  guessContainerExt,
  parseHlsManifest,
  parseDashManifest,
  inspectContainerBytes,
  classifyByUrlAndMimeHints,
  formatMetadataSubtitle,
  friendlyCodec,
} = require("./manifestParser");

const CANDIDATE_URL_RE = /(\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|flac)(\?|#|$)|\/videoplayback\b|mime=(video|audio)|format=(m3u8|mpd)|mpegurl|dash\+xml|(master|index|playlist)\.m3u8|\/hls\/|\/dash\/|\/stream\/|\/manifest)/i;
const MEDIA_CT_RE = /(mpegurl|dash\+xml|^video\/|^audio\/|application\/mp4|application\/ogg)/i;
const NON_MEDIA_CT_RE = /^(application\/json|text\/javascript|application\/javascript|application\/x-javascript|image\/|text\/css|font\/)/i;

/**
 * CDP-powered (Chrome DevTools Protocol) media sniffer for Electron browserView.
 * Using webContents.debugger avoids any listener collision with @ghostery/adblocker-electron's
 * webRequest hooks, captures ALL cross-origin iframes, reads real Content-Type headers,
 * and can read HLS/DASH manifest bodies directly from Chromium memory via Network.getResponseBody.
 */
class DesktopStreamSniffer {
  constructor(onStreamsUpdated) {
    this.onStreamsUpdated = onStreamsUpdated;
    this.streamsByKey = new Map();
    this.childOverrides = new Map();
    this.inspectedKeys = new Set();
    this.requestHeadersById = new Map();
    this.pendingManifestRequests = new Map();
    this.activePageUrl = "";
    this.activePageTitle = "";
    this.webContents = null;
  }

  attachToWebContents(webContents) {
    this.webContents = webContents;
    try {
      if (!webContents.debugger.isAttached()) {
        webContents.debugger.attach("1.3");
      }
      webContents.debugger.sendCommand("Network.enable", {
        maxResourceBufferSize: 4 * 1024 * 1024,
        maxTotalBufferSize: 16 * 1024 * 1024,
      }).catch(() => {});
    } catch {
      return;
    }

    webContents.debugger.on("message", (_event, method, params) => {
      try {
        if (method === "Network.requestWillBeSent") {
          this._onRequestWillBeSent(params);
        } else if (method === "Network.responseReceived") {
          this._onResponseReceived(params);
        } else if (method === "Network.loadingFinished") {
          this._onLoadingFinished(params);
        }
      } catch {
        // Never disrupt browser navigation
      }
    });
  }

  _onRequestWillBeSent(params) {
    const reqId = params.requestId;
    const req = params.request;
    if (!reqId || !req || !req.url) return;

    const picked = {};
    if (req.headers) {
      for (const [k, v] of Object.entries(req.headers)) {
        const low = k.toLowerCase();
        if ((low === "referer" || low === "origin" || low === "user-agent" || low === "cookie") && v) {
          picked[k] = String(v);
        }
      }
    }
    this.requestHeadersById.set(reqId, { url: req.url, headers: picked });
    if (this.requestHeadersById.size > 600) {
      const firstKey = this.requestHeadersById.keys().next().value;
      this.requestHeadersById.delete(firstKey);
    }
  }

  _onResponseReceived(params) {
    const reqId = params.requestId;
    const resp = params.response;
    if (!resp || !resp.url) return;
    if (resp.status < 200 || resp.status >= 400) return;

    const url = resp.url;
    if (isSegmentOrNoiseUrl(url)) return;

    const respHeaders = resp.headers || {};
    const ct = (resp.mimeType || this._getHeader(respHeaders, "content-type") || "").toLowerCase();
    const cr = this._getHeader(respHeaders, "content-range");
    const cl = this._getHeader(respHeaders, "content-length");

    // Ignore YouTube UMP/SABR proprietary binary chunks and strictly non-media MIME types (JSON, JS, images)
    if (ct.includes("vnd.yt-ump") || /[?&]ump=1\b/i.test(url)) return;
    if (NON_MEDIA_CT_RE.test(ct)) return;

    const isMedia = MEDIA_CT_RE.test(ct) || CANDIDATE_URL_RE.test(url);
    if (!isMedia) return;

    const cachedReq = this.requestHeadersById.get(reqId);
    const headers = { ...(cachedReq ? cachedReq.headers : {}) };
    if (!this._hasHeaderKey(headers, "referer") && this.activePageUrl) {
      headers.Referer = this.activePageUrl;
    }

    const cleanUrl = stripRangeQueryParams(url);
    const normKey = normalizeUrlForKey(cleanUrl);
    const ext = guessContainerExt(cleanUrl, ct);
    const isManifest =
      ext === "m3u8" ||
      ext === "mpd" ||
      ct.includes("mpegurl") ||
      ct.includes("dash+xml");

    if (isManifest) {
      // Queue for zero-overhead CDP Network.getResponseBody once loadingFinished fires
      this.pendingManifestRequests.set(reqId, { cleanUrl, normKey, ct, headers });
    }

    // Only treat Content-Length as true total size when NOT a ranged request
    const wasRangeRequest = /[?&](range|bytestart)=/i.test(url) || Boolean(cr);
    const knownTotalSize = this._extractReliableTotalBytes(cr, cl, wasRangeRequest);

    this.submitCandidate(cleanUrl, ct, headers, knownTotalSize, isManifest);
  }

  async _onLoadingFinished(params) {
    const reqId = params.requestId;
    const pending = this.pendingManifestRequests.get(reqId);
    if (!pending) return;
    this.pendingManifestRequests.delete(reqId);

    if (!this.webContents || this.webContents.isDestroyed()) return;
    try {
      const bodyResp = await this.webContents.debugger.sendCommand("Network.getResponseBody", {
        requestId: reqId,
      });
      if (!bodyResp || !bodyResp.body) return;
      const text = bodyResp.base64Encoded
        ? Buffer.from(bodyResp.body, "base64").toString("utf8")
        : String(bodyResp.body);
      this._ingestManifestText(pending.cleanUrl, pending.normKey, pending.ct, pending.headers, text);
    } catch {
      // Fallback probe in submitCandidate will still run if needed
    }
  }

  /**
   * Ingests structured streams extracted directly from page JS players (e.g. YouTube ytInitialPlayerResponse).
   */
  ingestStructuredStreams(items, pageUrl, pageTitle) {
    if (!Array.isArray(items) || items.length === 0) return;
    let changed = false;
    for (const item of items) {
      if (!item || !item.url || !/^https?:\/\//i.test(item.url)) continue;
      const cleanUrl = stripRangeQueryParams(item.url);
      const normKey = normalizeUrlForKey(cleanUrl);
      const entry = {
        id: normKey,
        url: cleanUrl,
        normalizedUrl: normKey,
        kind: item.kind || StreamKind.COMBINED,
        container: item.container || guessContainerExt(cleanUrl, item.mimeType || ""),
        label: item.label || "Stream",
        width: item.width || null,
        height: item.height || null,
        fps: item.fps || null,
        vcodec: item.vcodec || null,
        acodec: item.acodec || null,
        bitrateKbps: item.bitrateKbps || null,
        contentLength: item.contentLength || null,
        language: item.language || null,
        isHls: Boolean(item.isHls),
        isDash: Boolean(item.isDash),
        isMasterPlaylist: Boolean(item.isMasterPlaylist),
        isDrmProtected: Boolean(item.isDrmProtected),
        parentManifestUrl: null,
        pageTitle: pageTitle || this.activePageTitle,
        requestHeaders: { Referer: pageUrl || this.activePageUrl },
        detectedAtMs: Date.now(),
      };
      entry.subtitle = formatMetadataSubtitle(entry);
      this.streamsByKey.set(normKey, entry);
      this.inspectedKeys.add(normKey);
      changed = true;
    }
    if (changed) this._emit();
  }

  clearForNewPage(newUrl) {
    this.activePageUrl = newUrl || "";
    this.streamsByKey.clear();
    this.childOverrides.clear();
    this.inspectedKeys.clear();
    this.pendingManifestRequests.clear();
    this._emit();
  }

  setPageTitle(title) {
    if (title) this.activePageTitle = title;
  }

  getSortedStreams() {
    const kindOrder = { [StreamKind.COMBINED]: 0, [StreamKind.VIDEO_ONLY]: 1, [StreamKind.AUDIO_ONLY]: 2 };
    return Array.from(this.streamsByKey.values()).sort((a, b) => {
      if (Boolean(a.isDrmProtected) !== Boolean(b.isDrmProtected)) return a.isDrmProtected ? 1 : -1;
      if (kindOrder[a.kind] !== kindOrder[b.kind]) return kindOrder[a.kind] - kindOrder[b.kind];
      if (Boolean(a.isMasterPlaylist) !== Boolean(b.isMasterPlaylist)) return a.isMasterPlaylist ? -1 : 1;
      if ((b.height || 0) !== (a.height || 0)) return (b.height || 0) - (a.height || 0);
      if ((b.bitrateKbps || 0) !== (a.bitrateKbps || 0)) return (b.bitrateKbps || 0) - (a.bitrateKbps || 0);
      return (a.detectedAtMs || 0) - (b.detectedAtMs || 0);
    });
  }

  submitCandidate(rawUrl, contentTypeHint, headers, knownTotalSize, isKnownManifest = false) {
    const cleanUrl = stripRangeQueryParams(rawUrl);
    const normKey = normalizeUrlForKey(cleanUrl);

    if (this.childOverrides.has(normKey)) {
      const ov = this.childOverrides.get(normKey);
      this.streamsByKey.set(normKey, {
        ...ov,
        requestHeaders: { ...ov.requestHeaders, ...headers },
      });
      this._emit();
      return;
    }

    if (this.inspectedKeys.has(normKey)) return;
    this.inspectedKeys.add(normKey);

    const ext = guessContainerExt(cleanUrl, contentTypeHint);
    const lowerCt = String(contentTypeHint || "").toLowerCase();
    const isLikelyManifest =
      isKnownManifest ||
      ext === "m3u8" ||
      ext === "mpd" ||
      lowerCt.includes("mpegurl") ||
      lowerCt.includes("dash+xml") ||
      /\/manifest/i.test(cleanUrl);

    if (!isLikelyManifest) {
      if (knownTotalSize && knownTotalSize > 0 && knownTotalSize < 120000) {
        return;
      }
      const quick = classifyByUrlAndMimeHints(cleanUrl, lowerCt);
      const entry = {
        id: normKey,
        url: cleanUrl,
        normalizedUrl: normKey,
        kind: quick.kind,
        container: ext,
        label:
          quick.kind === StreamKind.AUDIO_ONLY
            ? `Audio Track (${ext.toUpperCase()})`
            : quick.kind === StreamKind.VIDEO_ONLY
              ? `Video-Only Track (${ext.toUpperCase()})`
              : `Video Stream (${ext.toUpperCase()})`,
        width: null,
        height: null,
        fps: null,
        vcodec: null,
        acodec: null,
        bitrateKbps: null,
        contentLength: knownTotalSize || null,
        language: null,
        isHls: false,
        isDash: false,
        isMasterPlaylist: false,
        isDrmProtected: false,
        parentManifestUrl: null,
        pageTitle: this.activePageTitle,
        requestHeaders: headers,
        detectedAtMs: Date.now(),
      };
      entry.subtitle = formatMetadataSubtitle(entry);
      this.streamsByKey.set(normKey, entry);
      this._emit();
    }

    this._probeRemote(cleanUrl, normKey, contentTypeHint, headers, isLikelyManifest, knownTotalSize).catch(() => {});
  }

  _ingestManifestText(cleanUrl, normKey, respCt, headers, textSample) {
    if (textSample.replace(/^\uFEFF/, "").trimStart().startsWith("#EXTM3U")) {
      if (this.childOverrides.has(normKey)) {
        const ov = this.childOverrides.get(normKey);
        this.streamsByKey.set(normKey, { ...ov, requestHeaders: { ...ov.requestHeaders, ...headers } });
        this._emit();
        return true;
      }
      const parsed = parseHlsManifest(cleanUrl, textSample, headers, this.activePageTitle);
      if (parsed) {
        for (const [ck, cs] of parsed.childOverrides.entries()) {
          this.childOverrides.set(ck, cs);
          if (this.streamsByKey.has(ck)) this.streamsByKey.set(ck, cs);
        }
        for (const s of parsed.streams) {
          this.streamsByKey.set(s.normalizedUrl, this.childOverrides.get(s.normalizedUrl) || s);
        }
        this._emit();
        return true;
      }
    }

    if (respCt.includes("dash+xml") || /<MPD\b/i.test(textSample)) {
      const parsed = parseDashManifest(cleanUrl, textSample, headers, this.activePageTitle);
      if (parsed) {
        for (const s of parsed.streams) {
          this.streamsByKey.set(s.normalizedUrl, s);
        }
        this._emit();
        return true;
      }
    }
    return false;
  }

  async _probeRemote(cleanUrl, normKey, contentTypeHint, headers, expectManifest, knownTotalSize) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
    try {
      const fetchHeaders = { ...headers };
      const sentRange = !expectManifest;
      if (sentRange) {
        fetchHeaders.Range = "bytes=0-65535";
      }

      const resp = await fetch(cleanUrl, {
        method: "GET",
        headers: fetchHeaders,
        redirect: "follow",
        signal: controller.signal,
      });

      if (!resp.ok) {
        if (expectManifest && !this.streamsByKey.has(normKey)) {
          const ext = guessContainerExt(cleanUrl, contentTypeHint);
          const fallback = {
            id: normKey,
            url: cleanUrl,
            normalizedUrl: normKey,
            kind: StreamKind.COMBINED,
            container: ext,
            label: ext === "mpd" ? "DASH Stream (.mpd)" : "HLS Stream (.m3u8)",
            isHls: ext === "m3u8",
            isDash: ext === "mpd",
            isMasterPlaylist: false,
            isDrmProtected: false,
            pageTitle: this.activePageTitle,
            requestHeaders: headers,
            detectedAtMs: Date.now(),
          };
          fallback.subtitle = formatMetadataSubtitle(fallback);
          this.streamsByKey.set(normKey, fallback);
          this._emit();
        }
        return;
      }

      const respCt = (resp.headers.get("content-type") || contentTypeHint || "").toLowerCase();
      const hasExplicitExt = /\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|ogg|opus|flac)(\?|#|$)|mime=(video|audio)|\/videoplayback\b/i.test(cleanUrl);
      if (NON_MEDIA_CT_RE.test(respCt)) {
        this.streamsByKey.delete(normKey);
        this._emit();
        return;
      }
      if (respCt.startsWith("text/html")) {
        if (!hasExplicitExt) {
          this.streamsByKey.delete(normKey);
          this._emit();
        }
        return;
      }

      const reliableTotalSize =
        this._extractReliableTotalBytes(
          resp.headers.get("content-range"),
          resp.headers.get("content-length"),
          sentRange
        ) ||
        knownTotalSize ||
        null;

      const maxBytes = expectManifest || respCt.includes("mpegurl") || respCt.includes("dash+xml") || respCt.includes("xml")
        ? 512 * 1024
        : 64 * 1024;
      const buf = await this._readUpTo(resp, maxBytes);
      const textSample = buf.toString("utf8");

      if (this._ingestManifestText(cleanUrl, normKey, respCt, headers, textSample)) {
        return;
      }

      // Only drop confirmed-small files (< 120 KB total), never 64 KB Range chunks!
      if (reliableTotalSize && reliableTotalSize > 0 && reliableTotalSize < 120000) {
        this.streamsByKey.delete(normKey);
        this._emit();
        return;
      }

      const ext = guessContainerExt(cleanUrl, respCt);
      const probe = inspectContainerBytes(buf, respCt, cleanUrl);
      const codecTxt = [friendlyCodec(probe.vcodec), friendlyCodec(probe.acodec)].filter(Boolean).join("+");
      const label =
        probe.kind === StreamKind.COMBINED
          ? `Video + Audio (${ext.toUpperCase()}${codecTxt ? ` • ${codecTxt}` : ""})`
          : probe.kind === StreamKind.VIDEO_ONLY
            ? `Video-Only (${ext.toUpperCase()}${codecTxt ? ` • ${codecTxt}` : ""})`
            : `Audio-Only (${ext.toUpperCase()}${codecTxt ? ` • ${codecTxt}` : ""})`;

      const richEntry = {
        id: normKey,
        url: cleanUrl,
        normalizedUrl: normKey,
        kind: probe.kind,
        container: ext,
        label,
        width: null,
        height: null,
        fps: null,
        vcodec: probe.vcodec,
        acodec: probe.acodec,
        bitrateKbps: null,
        contentLength: reliableTotalSize,
        language: null,
        isHls: false,
        isDash: false,
        isMasterPlaylist: false,
        isDrmProtected: probe.isDrmProtected,
        parentManifestUrl: null,
        pageTitle: this.activePageTitle,
        requestHeaders: headers,
        detectedAtMs: Date.now(),
      };
      richEntry.subtitle = formatMetadataSubtitle(richEntry);
      this.streamsByKey.set(normKey, richEntry);
      this._emit();
    } finally {
      clearTimeout(timeout);
    }
  }

  async _readUpTo(resp, maxBytes) {
    if (!resp.body || typeof resp.body.getReader !== "function") {
      const ab = await resp.arrayBuffer();
      return Buffer.from(ab.slice(0, maxBytes));
    }
    const reader = resp.body.getReader();
    const chunks = [];
    let total = 0;
    try {
      while (total < maxBytes) {
        const { done, value } = await reader.read();
        if (done || !value) break;
        const slice = total + value.byteLength > maxBytes ? value.subarray(0, maxBytes - total) : value;
        chunks.push(Buffer.from(slice));
        total += slice.byteLength;
      }
    } finally {
      reader.cancel().catch(() => {});
    }
    return Buffer.concat(chunks);
  }

  _getHeader(headersObj, name) {
    const target = name.toLowerCase();
    for (const [k, v] of Object.entries(headersObj)) {
      if (k.toLowerCase() === target) {
        return Array.isArray(v) ? String(v[0] || "") : String(v || "");
      }
    }
    return "";
  }

  _hasHeaderKey(obj, name) {
    const target = name.toLowerCase();
    return Object.keys(obj).some((k) => k.toLowerCase() === target);
  }

  _extractReliableTotalBytes(contentRange, contentLength, wasRangeRequest) {
    if (contentRange && contentRange.includes("/")) {
      const total = parseInt(contentRange.split("/")[1], 10);
      if (Number.isFinite(total) && total > 0) return total;
    }
    // If we requested a small byte range and the server returned Content-Length without Content-Range,
    // that Content-Length is just the chunk size (~64KB), NOT the full media file size!
    if (wasRangeRequest) return null;
    const len = parseInt(contentLength, 10);
    return Number.isFinite(len) && len > 0 ? len : null;
  }

  _emit() {
    if (typeof this.onStreamsUpdated === "function") {
      this.onStreamsUpdated(this.getSortedStreams());
    }
  }
}

module.exports = { DesktopStreamSniffer };
