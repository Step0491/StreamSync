"use strict";

const { contextBridge, ipcRenderer } = require("electron");

/**
 * Exposed inside roomView so static/app.js can register incoming peer stream
 * Referer/Origin headers with Electron's roomSession before hls.js/dash.js fetches them.
 */
contextBridge.exposeInMainWorld("StreamSyncDesktop", {
  isDesktop: true,
  registerStreamHeaders: (urls, headers) =>
    ipcRenderer.invoke("room:register-headers", { urls, headers }),
});
