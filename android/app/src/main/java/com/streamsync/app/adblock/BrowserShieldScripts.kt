package com.streamsync.app.adblock

import org.json.JSONObject

/**
 * Generates early DOM / JavaScript shield payloads injected into browserWebView:
 * 1. Cosmetic CSS element hiding (uBlock ##selectors)
 * 2. Anti-popup / window.open & invisible clickjack overlay neutralizer
 * 3. Fetch / XMLHttpRequest / HTMLMediaElement stream discovery hook (VDH-style)
 */
object BrowserShieldScripts {

    fun buildInjectionScript(
        shieldEnabled: Boolean,
        cosmeticSelectors: List<String>
    ): String {
        val cssRule = if (shieldEnabled && cosmeticSelectors.isNotEmpty()) {
            cosmeticSelectors.joinToString(", ") + " { display: none !important; visibility: hidden !important; pointer-events: none !important; }"
        } else {
            ""
        }
        val quotedCss = JSONObject.quote(cssRule)
        val shieldFlag = if (shieldEnabled) "true" else "false"

        return """
            (function() {
              if (window.__streamSyncInjected) return;
              window.__streamSyncInjected = true;

              const SHIELD_ACTIVE = $shieldFlag;
              const COSMETIC_CSS = $quotedCss;

              // 1. Cosmetic Element Hiding
              if (SHIELD_ACTIVE && COSMETIC_CSS.length > 0) {
                const applyCss = () => {
                  if (document.getElementById('__streamsync_cosmetic__')) return;
                  const style = document.createElement('style');
                  style.id = '__streamsync_cosmetic__';
                  style.textContent = COSMETIC_CSS;
                  (document.head || document.documentElement).appendChild(style);
                };
                if (document.head || document.documentElement) applyCss();
                else document.addEventListener('DOMContentLoaded', applyCss, { once: true });
              }

              // 2. Popup & Clickjack Overlay Guard
              if (SHIELD_ACTIVE) {
                const origOpen = window.open;
                window.open = function(url) {
                  try {
                    if (window.StreamSnifferBridge && window.StreamSnifferBridge.onBlockedPopup) {
                      window.StreamSnifferBridge.onBlockedPopup(String(url || 'about:blank'));
                    }
                  } catch (_) {}
                  return null;
                };

                // Neutralize transparent full-viewport clickjack anchors injected over video players
                document.addEventListener('click', function(e) {
                  const target = e.target && e.target.closest ? e.target.closest('a[target="_blank"]') : null;
                  if (!target) return;
                  const href = target.href || '';
                  if (!href) return;
                  try {
                    const dest = new URL(href, location.href);
                    const rect = target.getBoundingClientRect();
                    const coversScreen = rect.width >= window.innerWidth * 0.75 && rect.height >= window.innerHeight * 0.5;
                    const style = window.getComputedStyle(target);
                    const invisible = parseFloat(style.opacity || '1') < 0.08 || style.zIndex > 999999;
                    if ((coversScreen || invisible) && dest.hostname !== location.hostname) {
                      e.preventDefault();
                      e.stopPropagation();
                      if (window.StreamSnifferBridge && window.StreamSnifferBridge.onBlockedPopup) {
                        window.StreamSnifferBridge.onBlockedPopup(href);
                      }
                    }
                  } catch (_) {}
                }, true);
              }

              // 3. State-of-the-Art Media Sniffer Hook (Fetch, XHR, <video>/<audio>/<source>)
              function reportCandidate(url, contentType, sourceTag) {
                if (!url || typeof url !== 'string') return;
                if (url.startsWith('blob:') || url.startsWith('data:')) return;
                try {
                  const abs = new URL(url, location.href).href;
                  if (window.StreamSnifferBridge && window.StreamSnifferBridge.onMediaCandidate) {
                    window.StreamSnifferBridge.onMediaCandidate(
                      abs,
                      String(contentType || ''),
                      String(location.href || ''),
                      String(document.title || ''),
                      String(sourceTag || '')
                    );
                  }
                } catch (_) {}
              }

              const MEDIA_CT_RE = /(mpegurl|dash\+xml|video\/|audio\/|application\/mp4|application\/ogg)/i;
              const MEDIA_URL_RE = /\.(m3u8|mpd|mp4|webm|m4v|mov|m4a|mp3|aac|opus|ogg|flac)(\?|#|$)/i;

              // Hook window.fetch
              if (typeof window.fetch === 'function') {
                const origFetch = window.fetch;
                window.fetch = function(input, init) {
                  const reqUrl = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                  if (reqUrl && MEDIA_URL_RE.test(reqUrl)) {
                    reportCandidate(reqUrl, '', 'fetch-req');
                  }
                  return origFetch.apply(this, arguments).then(function(resp) {
                    try {
                      const ct = (resp.headers && resp.headers.get('content-type')) || '';
                      const finalUrl = resp.url || reqUrl;
                      if (MEDIA_CT_RE.test(ct) || MEDIA_URL_RE.test(finalUrl)) {
                        reportCandidate(finalUrl, ct, 'fetch-resp');
                      }
                    } catch (_) {}
                    return resp;
                  });
                };
              }

              // Hook XMLHttpRequest
              if (window.XMLHttpRequest) {
                const origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__ssUrl = url;
                  if (url && MEDIA_URL_RE.test(String(url))) {
                    reportCandidate(String(url), '', 'xhr-open');
                  }
                  this.addEventListener('readystatechange', function() {
                    if (this.readyState === 2 || this.readyState === 4) {
                      try {
                        const ct = this.getResponseHeader('content-type') || '';
                        const rUrl = this.responseURL || this.__ssUrl || '';
                        if (MEDIA_CT_RE.test(ct) || MEDIA_URL_RE.test(String(rUrl))) {
                          reportCandidate(String(rUrl), ct, 'xhr-headers');
                        }
                      } catch (_) {}
                    }
                  });
                  return origOpen.apply(this, arguments);
                };
              }

              // Observe <video>, <audio>, <source>, PerformanceResourceTiming, and YouTube playerResponse
              let lastYtVideoId = '';
              function scanMediaElements() {
                try {
                  const els = document.querySelectorAll('video, audio, source');
                  for (let i = 0; i < els.length; i++) {
                    const el = els[i];
                    const src = el.currentSrc || el.src || el.getAttribute('src');
                    const type = el.getAttribute('type') || '';
                    if (src) reportCandidate(src, type, el.tagName.toLowerCase());
                  }
                } catch (_) {}

                // Scan Chromium resource timing entries for video/audio initiators
                try {
                  if (performance && typeof performance.getEntriesByType === 'function') {
                    const entries = performance.getEntriesByType('resource');
                    for (let i = 0; i < entries.length; i++) {
                      const e = entries[i];
                      if (!e || !e.name) continue;
                      if (e.initiatorType === 'video' || e.initiatorType === 'audio') {
                        reportCandidate(e.name, e.initiatorType === 'audio' ? 'audio/mp4' : 'video/mp4', 'perf-' + e.initiatorType);
                      }
                    }
                  }
                } catch (_) {}

                // YouTube playerResponse extractor (bypasses SABR/UMP opaque binary chunks)
                try {
                  if (window.StreamSnifferBridge && window.StreamSnifferBridge.onStructuredStream) {
                    const mp = document.getElementById('movie_player');
                    const pr = (mp && typeof mp.getPlayerResponse === 'function' ? mp.getPlayerResponse() : null) || window.ytInitialPlayerResponse;
                    const vidId = pr && pr.videoDetails && pr.videoDetails.videoId ? String(pr.videoDetails.videoId) : '';
                    const sd = pr && pr.streamingData;
                    if (sd && vidId && vidId !== lastYtVideoId) {
                      lastYtVideoId = vidId;
                      const vidDurSec = Number((pr.videoDetails && pr.videoDetails.lengthSeconds) || 0);
                      const all = [].concat(sd.formats || [], sd.adaptiveFormats || []);
                      for (let i = 0; i < all.length; i++) {
                        const f = all[i];
                        if (!f || !f.url) continue;
                        const mime = String(f.mimeType || '');
                        const isVideo = mime.indexOf('video/') === 0;
                        const isAudio = mime.indexOf('audio/') === 0;
                        const hasBoth = Boolean(f.audioQuality) && Boolean(f.width || f.height);
                        const kind = hasBoth ? 'COMBINED' : (isVideo ? 'VIDEO_ONLY' : (isAudio ? 'AUDIO_ONLY' : 'COMBINED'));
                        const cm = mime.match(/codecs="([^"]+)"/i);
                        const codecs = cm ? cm[1] : '';
                        const ext = mime.indexOf('webm') >= 0 ? 'webm' : (isAudio ? 'm4a' : 'mp4');
                        const res = f.qualityLabel || (f.height ? f.height + 'p' : '');
                        const lang = (f.audioTrack && f.audioTrack.displayName) ? String(f.audioTrack.displayName) : '';
                        const label = kind === 'AUDIO_ONLY'
                          ? ('Audio ' + (lang ? lang + ' ' : '') + '(' + ext.toUpperCase() + ')')
                          : ((res || 'Video') + ' • ' + (kind === 'VIDEO_ONLY' ? 'Video-Only' : 'Combined') + ' (' + ext.toUpperCase() + ')');
                        const vcodec = isVideo ? codecs.split(',')[0].trim() : '';
                        const acodec = isAudio ? codecs.split(',')[0].trim() : (hasBoth && codecs.indexOf(',') >= 0 ? codecs.split(',')[1].trim() : '');
                        const approxDur = f.approxDurationMs ? (Number(f.approxDurationMs) / 1000) : vidDurSec;
                        window.StreamSnifferBridge.onStructuredStream(
                          String(f.url),
                          kind,
                          ext,
                          label,
                          Number(f.width || 0),
                          Number(f.height || 0),
                          Number(f.fps || 0),
                          Number(approxDur || 0),
                          vcodec,
                          acodec,
                          f.bitrate ? Math.round(Number(f.bitrate) / 1000) : 0,
                          Number(f.contentLength || 0),
                          lang,
                          false,
                          false,
                          String(location.href || ''),
                          String(document.title || '')
                        );
                      }
                      if (sd.hlsManifestUrl) {
                        window.StreamSnifferBridge.onStructuredStream(
                          String(sd.hlsManifestUrl), 'COMBINED', 'm3u8', 'HLS Master (YouTube)',
                          0, 0, 0, vidDurSec, '', '', 0, 0, '', true, false, String(location.href || ''), String(document.title || '')
                        );
                      }
                      if (sd.dashManifestUrl) {
                        window.StreamSnifferBridge.onStructuredStream(
                          String(sd.dashManifestUrl), 'COMBINED', 'mpd', 'DASH Master (YouTube)',
                          0, 0, 0, vidDurSec, '', '', 0, 0, '', false, true, String(location.href || ''), String(document.title || '')
                        );
                      }
                    }
                  }
                } catch (_) {}
              }

              document.addEventListener('loadedmetadata', function(e) {
                const t = e.target;
                if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO')) {
                  reportCandidate(t.currentSrc || t.src, '', t.tagName.toLowerCase());
                }
              }, true);

              setInterval(scanMediaElements, 2000);
              if (document.readyState !== 'loading') scanMediaElements();
              else document.addEventListener('DOMContentLoaded', scanMediaElements, { once: true });
            })();
        """.trimIndent()
    }
}
