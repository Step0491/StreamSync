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
 * Uses Target.setAutoAttach (flatten: true) so Network.responseReceived & Network.getResponseBody
 * capture EVERY cross-origin <iframe> (OOPIF) as well as the main page, with zero collision
 * against @ghostery/adblocker-electron's webRequest hooks.
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
      const netOpts = {
        maxResourceBufferSize: 4 * 1024 * 1024,
        maxTotalBufferSize: 16 * 1024 * 1024,
      };
      webContents.debugger.sendCommand("Network.enable", netOpts).catch(() => {});

      // CRITICAL: Auto-attach to all Out-Of-Process Iframes (OOPIFs) & workers so cross-origin
      // video player iframes emit Network.responseReceived events!
      webContents.debugger
        .sendCommand("Target.setAutoAttach", {
          autoAttach: true,
          waitForDebuggerOnStart: false,
          flatten: true,
        })
        .catch(() => {});
    } catch {
      return;
    }

    webContents.debugger.on("message", (_event, method, params, sessionId) => {
      try {
        if (method === "Target.attachedToTarget" && params && params.sessionId) {
          webContents.debugger
            .sendCommand(
              "Network.enable",
              {
                maxResourceBufferSize: 4 * 1024 * 1024,
                maxTotalBufferSize: 16 * 1024 * 1024,
              },
              params.sessionId
            )
            .catch(() => {});
          webContents.debugger
            .sendCommand(
              "Target.setAutoAttach",
              {
                autoAttach: true,
                waitForDebuggerOnStart: false,
                flatten: true,
              },
              params.sessionId
            )
            .catch(() => {});
        } else if (method === "Network.requestWillBeSent") {
          this._onRequestWillBeSent(params, sessionId || "");
        } else if (method === "Network.responseReceived") {
          this._onResponseReceived(params, sessionId || "");
        } else if (method === "Network.loadingFinished") {
          this._onLoadingFinished(params, sessionId || "");
        }
      } catch {
        // Never disrupt browser navigation
      }
    });
  }

  _reqKey(sessionId, requestId) {
    return `${sessionId || "root"}:${requestId}`;
  }

  _onRequestWillBeSent(params, sessionId) {
    const reqId = params.requestId;
    const req = params.request;
    if (!reqId || !req || !req.url) return;

    const picked = {};
    let hasRange = false;
    if (req.headers) {
      for (const [k, v] of Object.entries(req.headers)) {
        const low = k.toLowerCase();
        if (low === "range") hasRange = true;
        if ((low === "referer" || low === "origin" || low === "user-agent" || low === "cookie") && v) {
          picked[k] = String(v);
        }
      }
    }
    const key = this._reqKey(sessionId, reqId);
    this.requestHeadersById.set(key, { url: req.url, headers: picked, hasRange });
    if (this.requestHeadersById.size > 800) {
      const firstKey = this.requestHeadersById.keys().next().value;
      this.requestHeadersById.delete(firstKey);
    }
  }

  _onResponseReceived(params, sessionId) {
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

    if (ct.includes("vnd.yt-ump") || /[?&]ump=1\b/i.test(url)) return;
    if (NON_MEDIA_CT_RE.test(ct)) return;

    const reqKey = this._reqKey(sessionId, reqId);
    const cachedReq = this.requestHeadersById.get(reqKey);
    const hasRange = Boolean(cachedReq && cachedReq.hasRange) || Boolean(cr);

    const isMedia = MEDIA_CT_RE.test(ct) || CANDIDATE_URL_RE.test(url) || (hasRange && !ct.startsWith("text/html"));
    if (!isMedia) return;

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
      this.pendingManifestRequests.set(reqKey, {
        requestId: reqId,
        sessionId,
        cleanUrl,
        normKey,
        ct,
        headers,
      });
    }

    const wasRangeRequest = /[?&](range|bytestart)=/i.test(url) || Boolean(cr);
    const knownTotalSize = this._extractReliableTotalBytes(cr, cl, wasRangeRequest);

    this.submitCandidate(cleanUrl, ct, headers, knownTotalSize, isManifest);
  }

  async _onLoadingFinished(params, sessionId) {
    const reqKey = this._reqKey(sessionId, params.requestId);
    const pending = this.pendingManifestRequests.get(reqKey);
    if (!pending) return;
    this.pendingManifestRequests.delete(reqKey);

    if (!this.webContents || this.webContents.isDestroyed()) return;
    try {
      const bodyResp = pending.sessionId
        ? await this.webContents.debugger.sendCommand(
            "Network.getResponseBody",
            { requestId: pending.requestId },
            pending.sessionId
          )
        : await this.webContents.debugger.sendCommand("Network.getResponseBody", {
            requestId: pending.requestId,
          });
      if (!bodyResp || !bodyResp.body) return;
      const text = bodyResp.base64Encoded
        ? Buffer.from(bodyResp.body, "base64").toString("utf8")
        : String(bodyResp.body);
      this._ingestManifestText(pending.cleanUrl, pending.normKey, pending.ct, pending.headers, text);
    } catch {
      // Fallback probe / optimistic entry remains available
    }
  }

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
        durationSec: item.durationSec || null,
        isLive: Boolean(item.isLive),
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

    // Always add an immediate optimistic entry (including HLS/DASH!) so Cloudflare-protected
    // streams never disappear if background fetch fails.
    if (isLikelyManifest) {
      const entry = {
        id: normKey,
        url: cleanUrl,
        normalizedUrl: normKey,
        kind: StreamKind.COMBINED,
        container: ext === "mpd" ? "mpd" : "m3u8",
        label: ext === "mpd" ? "DASH Stream (.mpd)" : "HLS Stream (.m3u8)",
        width: null,
        height: null,
        fps: null,
        durationSec: null,
        isLive: false,
        vcodec: null,
        acodec: null,
        bitrateKbps: null,
        contentLength: null,
        language: null,
        isHls: ext !== "mpd",
        isDash: ext === "mpd",
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
    } else {
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
        durationSec: null,
        isLive: false,
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
      const existingOverride = this.childOverrides.get(normKey);
      const parsed = parseHlsManifest(cleanUrl, textSample, headers, this.activePageTitle);
      if (parsed) {
        if (existingOverride && parsed.streams.length === 1 && !parsed.streams[0].isMasterPlaylist) {
          const childDur = parsed.streams[0].durationSec;
          const childLive = parsed.streams[0].isLive;
          const upgraded = {
            ...existingOverride,
            durationSec: childDur || existingOverride.durationSec,
            isLive: Boolean(childLive || existingOverride.isLive),
            requestHeaders: { ...existingOverride.requestHeaders, ...headers },
          };
          upgraded.subtitle = formatMetadataSubtitle(upgraded);
          this.childOverrides.set(normKey, upgraded);
          this.streamsByKey.set(normKey, upgraded);

          if (upgraded.parentManifestUrl && (childDur || childLive)) {
            for (const [k, s] of this.streamsByKey.entries()) {
              if (s.normalizedUrl === upgraded.parentManifestUrl || s.parentManifestUrl === upgraded.parentManifestUrl) {
                const updatedSibling = {
                  ...s,
                  durationSec: s.durationSec || childDur,
                  isLive: Boolean(s.isLive || childLive),
                };
                updatedSibling.subtitle = formatMetadataSubtitle(updatedSibling);
                this.streamsByKey.set(k, updatedSibling);
              }
            }
          }
          this._emit();
          return true;
        }

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

      if (!resp.ok) return;

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
        durationSec: null,
        isLive: false,
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
