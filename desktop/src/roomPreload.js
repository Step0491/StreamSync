"use strict";

const { contextBridge, ipcRenderer } = require("electron");

/**
 * Exposed inside roomView so static/app.js can:
 * 1. Register incoming peer stream Referer/Origin headers with Electron's roomSession.
 * 2. Trigger silent background extraction from source_url in browserView if a direct token is IP-locked.
 */
contextBridge.exposeInMainWorld("StreamSyncDesktop", {
  isDesktop: true,
  registerStreamHeaders: (urls, headers) =>
    ipcRenderer.invoke("room:register-headers", { urls, headers }),
  extractFromSourceUrl: (sourceUrl, wantPaired, streamHint) =>
    ipcRenderer.invoke("room:extract-source-url", { sourceUrl, wantPaired, streamHint }),
});
