"use strict";

const fs = require("fs");
const path = require("path");
const { app, BrowserWindow, WebContentsView, ipcMain, session, clipboard } = require("electron");
const { DesktopAdBlockManager, registrableDomain, extractHost } = require("./adblockManager");
const { DesktopStreamSniffer } = require("./streamSniffer");

const DEFAULT_SERVER_URL = "https://streamsync-pj98.onrender.com/";
const DEFAULT_BROWSER_URL = "https://www.google.com";
const TOPBAR_HEIGHT = 56;
const DRAWER_WIDTH = 440;

class JsonConfigStore {
  constructor(filePath) {
    this.filePath = filePath;
    this.data = {};
    try {
      if (fs.existsSync(filePath)) {
        this.data = JSON.parse(fs.readFileSync(filePath, "utf8"));
      }
    } catch {
      this.data = {};
    }
  }

  get(key, fallback) {
    return this.data[key] !== undefined ? this.data[key] : fallback;
  }

  set(key, value) {
    this.data[key] = value;
    try {
      fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), "utf8");
    } catch {
      // Ignore disk write failure on read-only paths
    }
  }
}

let mainWindow = null;
let roomView = null;
let browserView = null;
let configStore = null;
let adBlockManager = null;
let streamSniffer = null;

let activeTab = "room"; // "room" | "browser"
let drawerOpen = false;
let currentBrowserUrl = DEFAULT_BROWSER_URL;
let currentRoomUrl = DEFAULT_SERVER_URL;
let lastBrowserNavSig = "";

// Host -> { Referer, Origin, User-Agent, Cookie } injected into roomSession for protected streams
const roomStreamHeadersByHost = new Map();

function registerRoomStreamHeaders(urls, headers) {
  if (!headers || typeof headers !== "object") return;
  const clean = {};
  for (const [k, v] of Object.entries(headers)) {
    if (!v) continue;
    const low = k.toLowerCase();
    if (low === "referer" || low === "origin" || low === "user-agent" || low === "cookie") {
      clean[k] = String(v);
    }
  }
  if (Object.keys(clean).length === 0) return;
  for (const u of urls || []) {
    if (!u) continue;
    const host = extractHost(u);
    if (host) {
      roomStreamHeadersByHost.set(host, clean);
      roomStreamHeadersByHost.set(registrableDomain(host), clean);
    }
  }
}

function configureRoomHeaderAndCorsProxy(roomSession) {
  const serverOriginHost = () => extractHost(currentRoomUrl);

  roomSession.webRequest.onBeforeSendHeaders({ urls: ["http://*/*", "https://*/*"] }, (details, callback) => {
    const reqHost = extractHost(details.url);
    const sHost = serverOriginHost();
    if (reqHost && reqHost !== sHost) {
      const custom = roomStreamHeadersByHost.get(reqHost) || roomStreamHeadersByHost.get(registrableDomain(reqHost));
      if (custom) {
        const updated = { ...details.requestHeaders };
        for (const [k, v] of Object.entries(custom)) {
          // Remove case-insensitive duplicates before setting
          for (const existingKey of Object.keys(updated)) {
            if (existingKey.toLowerCase() === k.toLowerCase()) {
              delete updated[existingKey];
            }
          }
          updated[k] = v;
        }
        callback({ requestHeaders: updated });
        return;
      }
    }
    callback({ cancel: false });
  });

  // Relax CORS on cross-origin media responses loaded by hls.js / dash.js inside roomView
  roomSession.webRequest.onHeadersReceived({ urls: ["http://*/*", "https://*/*"] }, (details, callback) => {
    const reqHost = extractHost(details.url);
    const sHost = serverOriginHost();
    if (reqHost && reqHost !== sHost) {
      const hasCustomRule =
        roomStreamHeadersByHost.has(reqHost) || roomStreamHeadersByHost.has(registrableDomain(reqHost));
      const ct = Object.entries(details.responseHeaders || {}).find(([k]) => k.toLowerCase() === "content-type");
      const ctVal = ct ? (Array.isArray(ct[1]) ? ct[1][0] : ct[1]) : "";
      const looksMedia =
        hasCustomRule ||
        /(mpegurl|dash\+xml|^video\/|^audio\/|mp2t|octet-stream)/i.test(String(ctVal)) ||
        /\.(m3u8|mpd|ts|m4s|mp4|webm|m4a|aac|mp3|opus)(\?|$)/i.test(details.url);

      if (looksMedia) {
        const respHeaders = { ...(details.responseHeaders || {}) };
        for (const key of Object.keys(respHeaders)) {
          const low = key.toLowerCase();
          if (
            low === "access-control-allow-origin" ||
            low === "access-control-allow-headers" ||
            low === "access-control-allow-methods" ||
            low === "access-control-allow-credentials"
          ) {
            delete respHeaders[key];
          }
        }
        let originHeader = "*";
        try {
          originHeader = new URL(currentRoomUrl).origin;
        } catch {
          originHeader = "*";
        }
        respHeaders["Access-Control-Allow-Origin"] = [originHeader];
        respHeaders["Access-Control-Allow-Credentials"] = ["true"];
        respHeaders["Access-Control-Allow-Methods"] = ["GET, HEAD, OPTIONS"];
        respHeaders["Access-Control-Allow-Headers"] = ["*"];
        callback({ responseHeaders: respHeaders });
        return;
      }
    }
    callback({ cancel: false });
  });
}

function layoutViews() {
  if (!mainWindow || !roomView || !browserView) return;
  const [winW, winH] = mainWindow.getContentSize();
  const contentH = Math.max(0, winH - TOPBAR_HEIGHT);
  const contentW = drawerOpen ? Math.max(320, winW - DRAWER_WIDTH) : winW;

  const activeBounds = { x: 0, y: TOPBAR_HEIGHT, width: contentW, height: contentH };
  const hiddenBounds = { x: 0, y: TOPBAR_HEIGHT, width: 0, height: 0 };

  if (activeTab === "room") {
    roomView.setBounds(activeBounds);
    browserView.setBounds(hiddenBounds);
  } else {
    browserView.setBounds(activeBounds);
    roomView.setBounds(hiddenBounds);
  }
}

function sendShellState() {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send("shell:state", {
    activeTab,
    drawerOpen,
    url: activeTab === "room" ? currentRoomUrl : currentBrowserUrl,
    canGoBack: activeTab === "room" ? roomView.webContents.canGoBack() : browserView.webContents.canGoBack(),
    canGoForward: activeTab === "room" ? roomView.webContents.canGoForward() : browserView.webContents.canGoForward(),
    shield: {
      enabled: adBlockManager ? adBlockManager.isShieldEnabledForUrl(currentBrowserUrl) : true,
      blockedCount: adBlockManager ? adBlockManager.blockedOnPage : 0,
    },
  });
}

function computeNavSignature(url) {
  try {
    const u = new URL(url);
    return `${registrableDomain(u.hostname)}${u.pathname.replace(/\/+$/, "")}`;
  } catch {
    return "";
  }
}

function installWebRequestMultiplexer(targetSession) {
  const wr = targetSession.webRequest;
  const origBeforeReq = wr.onBeforeRequest.bind(wr);
  const origBeforeSend = wr.onBeforeSendHeaders.bind(wr);
  const origHeadersRecv = wr.onHeadersReceived.bind(wr);

  const beforeReqHandlers = [];
  const beforeSendHandlers = [];
  const headersRecvHandlers = [];

  wr.onBeforeRequest = (filterOrListener, maybeListener) => {
    const fn = typeof filterOrListener === "function" ? filterOrListener : maybeListener;
    if (typeof fn === "function") beforeReqHandlers.push(fn);
    origBeforeReq({ urls: ["<all_urls>"] }, (details, cb) => {
      let idx = 0;
      const next = (res) => {
        if (res && (res.cancel || res.redirectURL)) return cb(res);
        if (idx >= beforeReqHandlers.length) return cb({});
        const h = beforeReqHandlers[idx++];
        try {
          h(details, next);
        } catch {
          next({});
        }
      };
      next({});
    });
  };

  wr.onBeforeSendHeaders = (filterOrListener, maybeListener) => {
    const fn = typeof filterOrListener === "function" ? filterOrListener : maybeListener;
    if (typeof fn === "function") beforeSendHandlers.push(fn);
    origBeforeSend({ urls: ["<all_urls>"] }, (details, cb) => {
      let currentHeaders = details.requestHeaders;
      let idx = 0;
      const next = (res) => {
        if (res && res.cancel) return cb(res);
        if (res && res.requestHeaders) currentHeaders = res.requestHeaders;
        if (idx >= beforeSendHandlers.length) {
          return cb(currentHeaders !== details.requestHeaders ? { requestHeaders: currentHeaders } : {});
        }
        const h = beforeSendHandlers[idx++];
        try {
          h({ ...details, requestHeaders: currentHeaders }, next);
        } catch {
          next({});
        }
      };
      next({});
    });
  };

  wr.onHeadersReceived = (filterOrListener, maybeListener) => {
    const fn = typeof filterOrListener === "function" ? filterOrListener : maybeListener;
    if (typeof fn === "function") headersRecvHandlers.push(fn);
    origHeadersRecv({ urls: ["<all_urls>"] }, (details, cb) => {
      let currentRespHeaders = details.responseHeaders;
      let idx = 0;
      const next = (res) => {
        if (res && res.cancel) return cb(res);
        if (res && res.responseHeaders) currentRespHeaders = res.responseHeaders;
        if (idx >= headersRecvHandlers.length) {
          return cb(currentRespHeaders !== details.responseHeaders ? { responseHeaders: currentRespHeaders } : {});
        }
        const h = headersRecvHandlers[idx++];
        try {
          h({ ...details, responseHeaders: currentRespHeaders }, next);
        } catch {
          next({});
        }
      };
      next({});
    });
  };
}

async function createMainWindow() {
  configStore = new JsonConfigStore(path.join(app.getPath("userData"), "streamsync-desktop-config.json"));
  currentRoomUrl = configStore.get("serverUrl", DEFAULT_SERVER_URL);

  mainWindow = new BrowserWindow({
    width: 1360,
    height: 860,
    minWidth: 920,
    minHeight: 620,
    backgroundColor: "#0B0F17",
    title: "StreamSync Desktop",
    icon: path.join(__dirname, "..", "assets", "icon.png"),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  await mainWindow.loadFile(path.join(__dirname, "index.html"));

  const roomSession = session.fromPartition("persist:streamsync-room");
  const browserSession = session.fromPartition("persist:streamsync-browser");

  configureRoomHeaderAndCorsProxy(roomSession);
  installWebRequestMultiplexer(browserSession);

  // Initialize uBlock / EasyList AdBlock Engine on browserSession
  adBlockManager = new DesktopAdBlockManager(app.getPath("userData"), configStore, () => {
    sendShellState();
  });
  await adBlockManager.init(browserSession);

  // Initialize VDH-grade Stream Sniffer (webRequest multiplexer + CDP + subframe preload JS hooks)
  streamSniffer = new DesktopStreamSniffer((sortedStreams) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send("streams:updated", sortedStreams);
    }
  });
  streamSniffer.attachToSession(browserSession);

  roomView = new WebContentsView({
    webPreferences: {
      session: roomSession,
      preload: path.join(__dirname, "roomPreload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  browserView = new WebContentsView({
    webPreferences: {
      session: browserSession,
      preload: path.join(__dirname, "browserPreload.js"),
      nodeIntegrationInSubFrames: true,
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false,
    },
  });

  streamSniffer.attachToWebContents(browserView.webContents);

  mainWindow.contentView.addChildView(roomView);
  mainWindow.contentView.addChildView(browserView);

  // Popup & Ad Redirect Shield on browserView
  browserView.webContents.setWindowOpenHandler(({ url }) => {
    if (adBlockManager.shouldBlockPopup(url, currentBrowserUrl)) {
      return { action: "deny" };
    }
    if (url && /^https?:\/\//i.test(url)) {
      browserView.webContents.loadURL(url);
    }
    return { action: "deny" };
  });

  browserView.webContents.on("did-start-navigation", (_e, url, _isInPlace, isMainFrame) => {
    if (!isMainFrame || !url) return;
    currentBrowserUrl = url;
    const domainSig = extractHost(url) ? registrableDomain(extractHost(url)) : "";
    if (domainSig && domainSig !== lastBrowserNavSig) {
      lastBrowserNavSig = domainSig;
      adBlockManager.syncForNavigation(url, true);
    } else {
      adBlockManager.syncForNavigation(url, false);
    }
    sendShellState();
  });

  const scanPagePlayerStreams = async () => {
    if (!browserView || browserView.webContents.isDestroyed()) return;
    try {
      const pageUrl = browserView.webContents.getURL();
      const pageTitle = browserView.webContents.getTitle();
      const found = await browserView.webContents.executeJavaScript(`
        (function() {
          const out = [];
          try {
            // 1. YouTube playerResponse streamingData (works even when YouTube uses SABR/UMP!)
            const mp = document.getElementById('movie_player');
            const pr = (mp && typeof mp.getPlayerResponse === 'function' ? mp.getPlayerResponse() : null) || window.ytInitialPlayerResponse;
            const sd = pr && pr.streamingData;
            if (sd) {
              const all = [...(sd.formats || []), ...(sd.adaptiveFormats || [])];
              for (const f of all) {
                if (!f || !f.url) continue;
                const mime = String(f.mimeType || '');
                const isVideo = mime.startsWith('video/');
                const isAudio = mime.startsWith('audio/');
                const hasBoth = Boolean(f.audioQuality) && Boolean(f.width || f.height);
                const kind = hasBoth ? 'COMBINED' : (isVideo ? 'VIDEO_ONLY' : (isAudio ? 'AUDIO_ONLY' : 'COMBINED'));
                const codecsMatch = mime.match(/codecs="([^"]+)"/i);
                const codecs = codecsMatch ? codecsMatch[1] : '';
                const ext = mime.includes('webm') ? 'webm' : (isAudio ? 'm4a' : 'mp4');
                const res = f.qualityLabel || (f.height ? f.height + 'p' : '');
                const label = kind === 'AUDIO_ONLY'
                  ? ('Audio ' + (f.audioTrack && f.audioTrack.displayName ? f.audioTrack.displayName + ' ' : '') + '(' + ext.toUpperCase() + ')')
                  : ((res || 'Video') + ' • ' + (kind === 'VIDEO_ONLY' ? 'Video-Only' : 'Combined') + ' (' + ext.toUpperCase() + ')');
                out.push({
                  url: f.url,
                  kind,
                  container: ext,
                  label,
                  width: f.width || null,
                  height: f.height || null,
                  fps: f.fps || null,
                  vcodec: isVideo ? codecs.split(',')[0].trim() : null,
                  acodec: isAudio ? codecs.split(',')[0].trim() : (hasBoth && codecs.includes(',') ? codecs.split(',')[1].trim() : null),
                  bitrateKbps: f.bitrate ? Math.round(f.bitrate / 1000) : null,
                  contentLength: f.contentLength ? Number(f.contentLength) : null,
                  language: (f.audioTrack && f.audioTrack.displayName) || null,
                  mimeType: mime
                });
              }
              if (sd.hlsManifestUrl) {
                out.push({ url: sd.hlsManifestUrl, kind: 'COMBINED', container: 'm3u8', label: 'HLS Master (YouTube)', isHls: true, isMasterPlaylist: true });
              }
              if (sd.dashManifestUrl) {
                out.push({ url: sd.dashManifestUrl, kind: 'COMBINED', container: 'mpd', label: 'DASH Master (YouTube)', isDash: true, isMasterPlaylist: true });
              }
            }
          } catch (_) {}
          // 2. Direct <video> / <audio> / <source> DOM elements
          try {
            document.querySelectorAll('video, audio, source').forEach((el) => {
              const src = el.currentSrc || el.src || el.getAttribute('src');
              if (src && /^https?:\\/\\//i.test(src)) {
                out.push({
                  url: src,
                  kind: el.tagName === 'AUDIO' ? 'AUDIO_ONLY' : 'COMBINED',
                  label: el.tagName === 'AUDIO' ? 'Page Audio Element' : 'Page Video Element',
                  mimeType: el.getAttribute('type') || ''
                });
              }
            });
          } catch (_) {}
          return out;
        })();
      `);
      if (Array.isArray(found) && found.length > 0) {
        streamSniffer.ingestStructuredStreams(found, pageUrl, pageTitle);
      }
    } catch {
      // Ignore transient navigation execution errors
    }
  };

  browserView.webContents.on("did-finish-load", () => {
    setTimeout(scanPagePlayerStreams, 1200);
  });
  browserView.webContents.on("did-navigate-in-page", (_e, url, isMainFrame) => {
    if (!isMainFrame || !url) return;
    currentBrowserUrl = url;
    sendShellState();
    setTimeout(scanPagePlayerStreams, 1200);
  });

  browserView.webContents.on("page-title-updated", (_e, title) => {
    streamSniffer.setPageTitle(title);
  });

  roomView.webContents.on("did-navigate", (_e, url) => {
    if (url && /^https?:\/\//i.test(url)) {
      currentRoomUrl = url;
      sendShellState();
    }
  });

  roomView.webContents.setWindowOpenHandler(({ url }) => {
    if (url && /^https?:\/\//i.test(url)) {
      browserView.webContents.loadURL(url);
      activeTab = "browser";
      layoutViews();
      sendShellState();
    }
    return { action: "deny" };
  });

  mainWindow.on("resize", layoutViews);
  mainWindow.on("maximize", layoutViews);
  mainWindow.on("unmaximize", layoutViews);

  layoutViews();
  roomView.webContents.loadURL(currentRoomUrl);
  browserView.webContents.loadURL(currentBrowserUrl);
  sendShellState();
}

// ---------- IPC Handlers ----------

ipcMain.handle("shell:switch-tab", (_e, tab) => {
  activeTab = tab === "browser" ? "browser" : "room";
  layoutViews();
  sendShellState();
});

ipcMain.handle("shell:toggle-drawer", (_e, open) => {
  drawerOpen = typeof open === "boolean" ? open : !drawerOpen;
  layoutViews();
  sendShellState();
  return drawerOpen;
});

ipcMain.handle("shell:navigate", (_e, rawUrl) => {
  let target = String(rawUrl || "").trim();
  if (!target) return;
  if (!/^https?:\/\//i.test(target)) {
    target = `https://${target}`;
  }
  if (activeTab === "room") {
    currentRoomUrl = target;
    configStore.set("serverUrl", target);
    roomView.webContents.loadURL(target);
  } else {
    currentBrowserUrl = target;
    streamSniffer.clearForNewPage(target);
    browserView.webContents.loadURL(target);
  }
  sendShellState();
});

ipcMain.handle("shell:nav-action", (_e, action) => {
  const wc = activeTab === "room" ? roomView.webContents : browserView.webContents;
  if (action === "back" && wc.canGoBack()) wc.goBack();
  else if (action === "forward" && wc.canGoForward()) wc.goForward();
  else if (action === "reload") wc.reload();
  sendShellState();
});

ipcMain.handle("shell:toggle-shield", () => {
  const enabled = adBlockManager.toggleForUrl(currentBrowserUrl);
  browserView.webContents.reload();
  sendShellState();
  return enabled;
});

ipcMain.on("browser:media-signal", (_e, payload) => {
  if (!payload || !streamSniffer) return;
  if (payload.type === "candidate" && payload.url) {
    streamSniffer.submitCandidate(
      payload.url,
      payload.contentType || "",
      { Referer: payload.pageUrl || currentBrowserUrl },
      null,
      false
    );
  } else if (payload.type === "structured" && Array.isArray(payload.items)) {
    streamSniffer.ingestStructuredStreams(payload.items, payload.pageUrl, payload.pageTitle);
  } else if (payload.type === "dom_meta") {
    streamSniffer.enrichWithDomMeta(payload.durationSec, payload.width, payload.height);
  }
});

ipcMain.handle("streams:clear", () => {
  streamSniffer.clearForNewPage(currentBrowserUrl);
});

ipcMain.handle("streams:extract-current-page", async () => {
  if (!currentBrowserUrl || !/^https?:\/\//i.test(currentBrowserUrl)) return;
  activeTab = "room";
  drawerOpen = false;
  layoutViews();
  sendShellState();
  const urlStr = JSON.stringify(currentBrowserUrl);
  await roomView.webContents.executeJavaScript(`
    (function() {
      const inp = document.getElementById('url-input');
      const form = document.getElementById('extract-form');
      if (inp && form) {
        inp.value = ${urlStr};
        form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      }
    })();
  `);
});

ipcMain.handle("streams:copy", (_e, text) => {
  if (text) clipboard.writeText(String(text));
});

ipcMain.handle("room:register-headers", (_e, payload) => {
  if (payload && Array.isArray(payload.urls) && payload.headers) {
    registerRoomStreamHeaders(payload.urls, payload.headers);
  }
});

ipcMain.handle("room:extract-source-url", async (_e, { sourceUrl, wantPaired }) => {
  if (!sourceUrl || !/^https?:\/\//i.test(sourceUrl) || !streamSniffer || !browserView) return null;

  // Mute background browserView so there is ZERO double audio while extracting the fresh IP-valid token
  browserView.webContents.setAudioMuted(true);
  currentBrowserUrl = sourceUrl;
  streamSniffer.clearForNewPage(sourceUrl);
  browserView.webContents.loadURL(sourceUrl).catch(() => {});

  // Trigger silent Play across main frame AND all cross-origin iframes
  const triggerPlayAllFrames = () => {
    if (!browserView || browserView.webContents.isDestroyed()) return;
    const js = `(function(){try{var v=document.querySelector('video');if(v){v.muted=true;v.volume=0;if(v.paused)v.play().catch(function(){});}var btn=document.querySelector('.vjs-big-play-button,.plyr__control--overlaid,.jw-icon-display,.jw-display-icon-container,[aria-label*="Play" i],.play-button');if(btn)btn.click();}catch(_){}})();`;
    try {
      const frames = browserView.webContents.mainFrame ? browserView.webContents.mainFrame.framesInSubtree : [];
      for (const frame of frames) {
        frame.executeJavaScript(js).catch(() => {});
      }
    } catch {
      browserView.webContents.executeJavaScript(js).catch(() => {});
    }
  };
  const playPulse1 = setTimeout(triggerPlayAllFrames, 1800);
  const playPulse2 = setTimeout(triggerPlayAllFrames, 4000);

  return await new Promise((resolve) => {
    const start = Date.now();
    let firstCandidateAt = 0;
    const timer = setInterval(() => {
      const list = streamSniffer.getSortedStreams().filter((s) => !s.isDrmProtected);
      const combined = list.filter((s) => s.kind === "COMBINED");
      const videoOnly = list.filter((s) => s.kind === "VIDEO_ONLY");
      const audioOnly = list.filter((s) => s.kind === "AUDIO_ONLY");

      let chosenVideo = null;
      let chosenAudio = null;

      if (wantPaired && videoOnly.length > 0 && audioOnly.length > 0) {
        chosenVideo = videoOnly[0];
        chosenAudio = audioOnly[0];
      } else if (combined.length > 0 || (videoOnly.length > 0 && audioOnly.length > 0)) {
        if (firstCandidateAt === 0) firstCandidateAt = Date.now();
        // When Admin merged Video+Audio, wait up to 1.8s for separated audio+video before falling back to combined
        if (!wantPaired || Date.now() - firstCandidateAt >= 1800) {
          if (videoOnly.length > 0 && audioOnly.length > 0) {
            chosenVideo = videoOnly[0];
            chosenAudio = audioOnly[0];
          } else if (combined.length > 0) {
            chosenVideo = combined[0];
          }
        }
      }

      if (chosenVideo) {
        clearInterval(timer);
        clearTimeout(playPulse1);
        clearTimeout(playPulse2);
        // Stop background browser page immediately and unmute for future manual browsing
        browserView.webContents.loadURL("about:blank").catch(() => {});
        browserView.webContents.setAudioMuted(false);

        const mergedHeaders = { ...(chosenVideo.requestHeaders || {}) };
        if (chosenAudio && chosenAudio.requestHeaders) {
          for (const [k, v] of Object.entries(chosenAudio.requestHeaders)) {
            if (v && !mergedHeaders[k]) mergedHeaders[k] = v;
          }
        }
        registerRoomStreamHeaders(
          [chosenVideo.url, chosenAudio ? chosenAudio.url : null].filter(Boolean),
          mergedHeaders
        );
        resolve({
          mode: chosenAudio ? "paired" : "single",
          video_url: chosenVideo.url,
          audio_url: chosenAudio ? chosenAudio.url : null,
          is_hls: Boolean(chosenVideo.isHls),
          is_dash: Boolean(chosenVideo.isDash),
          label: chosenAudio ? "Refreshed Video + Audio (Synced)" : chosenVideo.label,
          headers: mergedHeaders,
        });
        return;
      }

      if (Date.now() - start > 15000) {
        clearInterval(timer);
        clearTimeout(playPulse1);
        clearTimeout(playPulse2);
        browserView.webContents.setAudioMuted(false);
        resolve(null);
      }
    }, 500);
  });
});

ipcMain.handle("streams:play-in-room", async (_e, { videoStream, audioStream }) => {
  if (!videoStream || !videoStream.url) return;

  const mergedHeaders = { ...(videoStream.requestHeaders || {}) };
  if (audioStream && audioStream.requestHeaders) {
    for (const [k, v] of Object.entries(audioStream.requestHeaders)) {
      if (v && !mergedHeaders[k]) mergedHeaders[k] = v;
    }
  }

  // Register full headers (including Cookie locally) with roomSession so hls.js/dash.js bypasses hotlink/CORS
  registerRoomStreamHeaders(
    [videoStream.url, audioStream ? audioStream.url : null].filter(Boolean),
    mergedHeaders
  );

  // Strip Cookie before broadcasting playback metadata to other room participants
  const publicHeaders = {};
  for (const [k, v] of Object.entries(mergedHeaders)) {
    if (k.toLowerCase() !== "cookie" && v) publicHeaders[k] = v;
  }

  const pageTitle = (browserView.webContents.getTitle() || videoStream.pageTitle || "Detected Stream").slice(0, 140);
  const label = audioStream
    ? `${videoStream.height ? `${videoStream.height}p` : videoStream.container.toUpperCase()} + ${audioStream.language || audioStream.container.toUpperCase()} (Synced)`
    : videoStream.label;

  const payload = {
    video_url: videoStream.url,
    audio_url: audioStream ? audioStream.url : "",
    is_hls: Boolean(videoStream.isHls),
    is_dash: Boolean(videoStream.isDash),
    title: pageTitle,
    label,
    headers: publicHeaders,
    source_url: currentBrowserUrl,
  };

  activeTab = "room";
  drawerOpen = false;
  layoutViews();
  sendShellState();

  const payloadStr = JSON.stringify(payload);
  const injected = await roomView.webContents.executeJavaScript(`
    (function() {
      if (window.loadFromNativePayload) {
        window.loadFromNativePayload(${payloadStr});
        return true;
      }
      if (window.loadFromNativeApp) {
        window.loadFromNativeApp(${JSON.stringify(payload.video_url)}, ${JSON.stringify(payload.audio_url)}, ${JSON.stringify(payload.title)});
        return true;
      }
      return false;
    })();
  `);

  if (!injected) {
    const u = new URL(currentRoomUrl);
    u.searchParams.set("video", payload.video_url);
    u.searchParams.set("title", payload.title);
    if (payload.audio_url) u.searchParams.set("audio", payload.audio_url);
    roomView.webContents.loadURL(u.href);
  }
});

app.whenReady().then(createMainWindow);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});
