"use strict";

const fs = require("fs");
const path = require("path");
const { ElectronBlocker } = require("@ghostery/adblocker-electron");

const FALLBACK_FILTERS = `
! StreamSync Offline Core Fallback Rules
||doubleclick.net^$third-party
||googlesyndication.com^$third-party
||imasdk.googleapis.com^
||exoclick.com^
||exosrv.com^
||realsrv.com^
||popads.net^
||popcash.net^
||propellerads.com^
||adsterra.com^
||highperformanceformat.com^
||profitablecpmrate.com^
||hilltopads.net^
||clickadu.com^
||juicyads.com^
||trafficjunky.net^
||adnxs.com^$third-party
||taboola.com^
||outbrain.com^
||mgid.com^
/vast.xml$third-party
/preroll$third-party
/popunder$third-party
`;

function registrableDomain(host) {
  const clean = String(host || "").toLowerCase().replace(/\.$/, "");
  const parts = clean.split(".");
  if (parts.length <= 2) return clean;
  return `${parts[parts.length - 2]}.${parts[parts.length - 1]}`;
}

function extractHost(url) {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return "";
  }
}

/**
 * Manages uBlock/EasyList network & cosmetic filtering in Electron browserSession
 * via @ghostery/adblocker-electron with persistent disk cache and per-site toggle.
 */
class DesktopAdBlockManager {
  constructor(userDataDir, configStore, onStatsChanged) {
    this.cachePath = path.join(userDataDir, "streamsync-adblocker.bin");
    this.configStore = configStore;
    this.onStatsChanged = onStatsChanged;
    this.blocker = null;
    this.browserSession = null;
    this.isEnabledInSession = false;
    this.blockedOnPage = 0;
    this.disabledDomains = new Set(configStore.get("disabledShieldDomains", []));
  }

  async init(browserSession) {
    this.browserSession = browserSession;
    try {
      this.blocker = await ElectronBlocker.fromPrebuiltAdsAndTracking(fetch, {
        path: this.cachePath,
        read: fs.promises.readFile,
        write: fs.promises.writeFile,
      });
    } catch {
      // Offline first-boot fallback using embedded core EasyList/VAST rules
      this.blocker = ElectronBlocker.parse(FALLBACK_FILTERS);
    }

    if (this.blocker) {
      this.blocker.on("request-blocked", () => {
        this.blockedOnPage += 1;
        this._emitStats();
      });
      this._setSessionBlocking(true);
    }
  }

  isShieldEnabledForUrl(pageUrl) {
    const host = extractHost(pageUrl);
    if (!host) return true;
    const reg = registrableDomain(host);
    return !this.disabledDomains.has(host) && !this.disabledDomains.has(reg);
  }

  syncForNavigation(pageUrl, resetCounter = true) {
    if (resetCounter) {
      this.blockedOnPage = 0;
    }
    const shouldEnable = this.isShieldEnabledForUrl(pageUrl);
    this._setSessionBlocking(shouldEnable);
    this._emitStats(pageUrl);
  }

  toggleForUrl(pageUrl) {
    const host = extractHost(pageUrl);
    if (!host) return true;
    const reg = registrableDomain(host);
    const wasEnabled = this.isShieldEnabledForUrl(pageUrl);
    if (wasEnabled) {
      this.disabledDomains.add(reg);
    } else {
      this.disabledDomains.delete(host);
      this.disabledDomains.delete(reg);
    }
    this.configStore.set("disabledShieldDomains", Array.from(this.disabledDomains));
    this._setSessionBlocking(!wasEnabled);
    this._emitStats(pageUrl);
    return !wasEnabled;
  }

  shouldBlockPopup(targetUrl, currentPageUrl) {
    if (!this.isShieldEnabledForUrl(currentPageUrl)) return false;
    if (!targetUrl || targetUrl === "about:blank") {
      this.blockedOnPage += 1;
      this._emitStats(currentPageUrl);
      return true;
    }
    if (!/^https?:\/\//i.test(targetUrl)) {
      this.blockedOnPage += 1;
      this._emitStats(currentPageUrl);
      return true;
    }

    const targetHost = extractHost(targetUrl);
    const pageHost = extractHost(currentPageUrl);
    if (targetHost && pageHost && registrableDomain(targetHost) !== registrableDomain(pageHost)) {
      this.blockedOnPage += 1;
      this._emitStats(currentPageUrl);
      return true;
    }
    return false;
  }

  _setSessionBlocking(enable) {
    if (!this.blocker || !this.browserSession) return;
    if (enable && !this.isEnabledInSession) {
      this.blocker.enableBlockingInSession(this.browserSession);
      this.isEnabledInSession = true;
    } else if (!enable && this.isEnabledInSession) {
      this.blocker.disableBlockingInSession(this.browserSession);
      this.isEnabledInSession = false;
    }
  }

  _emitStats(pageUrl = "") {
    if (typeof this.onStatsChanged === "function") {
      this.onStatsChanged({
        enabled: pageUrl ? this.isShieldEnabledForUrl(pageUrl) : this.isEnabledInSession,
        blockedCount: this.blockedOnPage,
      });
    }
  }
}

module.exports = { DesktopAdBlockManager, registrableDomain, extractHost };
