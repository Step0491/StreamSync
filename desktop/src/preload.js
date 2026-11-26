"use strict";

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("StreamSyncShell", {
  switchTab: (tab) => ipcRenderer.invoke("shell:switch-tab", tab),
  toggleDrawer: (open) => ipcRenderer.invoke("shell:toggle-drawer", open),
  navigate: (url) => ipcRenderer.invoke("shell:navigate", url),
  navAction: (action) => ipcRenderer.invoke("shell:nav-action", action),
  toggleShield: () => ipcRenderer.invoke("shell:toggle-shield"),
  clearStreams: () => ipcRenderer.invoke("streams:clear"),
  extractCurrentPage: () => ipcRenderer.invoke("streams:extract-current-page"),
  copyText: (text) => ipcRenderer.invoke("streams:copy", text),
  playInRoom: (videoStream, audioStream) =>
    ipcRenderer.invoke("streams:play-in-room", { videoStream, audioStream }),
  checkUpdate: () => ipcRenderer.invoke("app:check-update"),
  cancelGuestAssist: () => ipcRenderer.invoke("room:cancel-guest-assist"),
  onUpdateProgress: (cb) => ipcRenderer.on("update:progress", (_e, info) => cb(info)),
  onShellState: (cb) => ipcRenderer.on("shell:state", (_e, state) => cb(state)),
  onStreamsUpdated: (cb) => ipcRenderer.on("streams:updated", (_e, streams) => cb(streams)),
});
