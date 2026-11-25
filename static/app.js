// StreamSync Frontend: Unified Dual-Track Player (Web/Electron + Native Android ExoPlayer Bridge)
// + RTT-Compensated Watch-Party Room Synchronization & Buffering Barrier

const form = document.getElementById("extract-form");
const urlInput = document.getElementById("url-input");
const extractBtn = document.getElementById("extract-btn");
const directVideoInput = document.getElementById("direct-video-input");
const directAudioInput = document.getElementById("direct-audio-input");
const directPlayBtn = document.getElementById("direct-play-btn");
const statusBox = document.getElementById("status-box");

const extractCard = document.getElementById("extract-card");
const playerSection = document.getElementById("player-section");
const tracksSection = document.getElementById("tracks-section");
const mediaTitle = document.getElementById("media-title");
const mediaExtractor = document.getElementById("media-extractor");
const mediaDuration = document.getElementById("media-duration");
const mediaLive = document.getElementById("media-live");
const nowPlayingEl = document.getElementById("now-playing");
const syncIndicator = document.getElementById("sync-indicator");
const roomStatusStrip = document.getElementById("room-status-strip");
const roomSyncLog = document.getElementById("room-sync-log");
const bufferBarrierBanner = document.getElementById("buffer-barrier-banner");
const waitingHostBanner = document.getElementById("waiting-host-banner");

const videoEl = document.getElementById("video-player");
const audioEl = document.getElementById("audio-player");
const manifestAudioBar = document.getElementById("manifest-audio-bar");
const manifestAudioSelect = document.getElementById("manifest-audio-select");

const pairControls = document.getElementById("pair-controls");
const pairVideoSelect = document.getElementById("pair-video-select");
const pairAudioSelect = document.getElementById("pair-audio-select");
const pairPlayBtn = document.getElementById("pair-play-btn");

// Room UI elements
const roomStatusBadge = document.getElementById("room-status-badge");
const rttBadge = document.getElementById("rtt-badge");
const nicknameInput = document.getElementById("nickname-input");
const roomCodeInput = document.getElementById("room-code-input");
const joinRoomBtn = document.getElementById("join-room-btn");
const createRoomBtn = document.getElementById("create-room-btn");
const inviteRoomBtn = document.getElementById("invite-room-btn");
const leaveRoomBtn = document.getElementById("leave-room-btn");

const roomSidebar = document.getElementById("room-sidebar");
const sidebarRoomCode = document.getElementById("sidebar-room-code");
const memberCount = document.getElementById("member-count");
const memberList = document.getElementById("member-list");
const controlModeLabel = document.getElementById("control-mode-label");
const toggleControlModeBtn = document.getElementById("toggle-control-mode-btn");
const chatMessages = document.getElementById("chat-messages");
const chatForm = document.getElementById("chat-form");
const chatInput = document.getElementById("chat-input");

// Detect Native Android Media3 ExoPlayer Bridge & Electron Desktop Proxy
const hasNativeAndroidPlayer = Boolean(
  window.AndroidNativePlayer &&
    typeof window.AndroidNativePlayer.isAvailable === "function" &&
    window.AndroidNativePlayer.isAvailable()
);
const hasDesktopProxy = Boolean(
  window.StreamSyncDesktop &&
    typeof window.StreamSyncDesktop.registerStreamHeaders === "function"
);

if (hasNativeAndroidPlayer) {
  // Native ExoPlayer renders above roomWebView; never show a duplicate HTML5 player card below it!
  playerSection.classList.add("hidden");
  videoEl.classList.add("hidden");
}

let hlsVideoInstance = null;
let dashVideoInstance = null;
let hlsAudioInstance = null;
let dashAudioInstance = null;

let dualSyncActive = false;
let canonicalPlaybackRate = 1.0;
let videoBuffering = false;
let audioBuffering = false;
let hasReachedReadyForMedia = false;
let pausedByBarrier = false;
let currentData = null;
let lastLoadedPlayback = null;
let lastLoadedTitle = "";
let lastLoadedSourceUrl = "";
let lastKnownRoomSync = null;
let hasAttemptedGuestExtraction = false;
let pendingMediaBroadcast = null;
let currentProxyFallbackTried = false;

// Room WebSocket & Clock Sync state
let ws = null;
let currentRoomId = null;
let intendedRoomId = null;
let reconnectTimer = null;
let reconnectAttempts = 0;
let myClientId = null;
let roomHostId = null;
let roomControlMode = "everyone";
let lastAppliedSyncSeq = -1;
let suppressRoomSync = false;
let suppressTimer = null;

let serverClockOffsetSec = 0;
let hasClockSample = false;
let rttMs = 0;
let pingIntervalId = null;
let heartbeatIntervalId = null;
let localDriftIntervalId = null;

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

nicknameInput.value = localStorage.getItem("streamsync_nick") || "";
nicknameInput.addEventListener("change", () => {
  localStorage.setItem("streamsync_nick", nicknameInput.value.trim());
});

function setStatus(message, isError = false) {
  statusBox.textContent = message;
  statusBox.classList.remove("hidden", "error");
  if (isError) statusBox.classList.add("error");
}

function clearStatus() {
  statusBox.classList.add("hidden");
  statusBox.textContent = "";
}

function formatDuration(seconds) {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return "";
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return h > 0
    ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`
    : `${m}:${String(s).padStart(2, "0")}`;
}

function formatBytes(bytes) {
  if (!bytes) return "";
  const mb = bytes / (1024 * 1024);
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${Math.round(bytes / 1024)} KB`;
}

function estimatedServerNowSec() {
  return Date.now() / 1000 + serverClockOffsetSec;
}

function projectPosition(position, isPlaying, playbackRate, serverTs) {
  const basePos = typeof position === "number" ? Math.max(0, position) : 0;
  if (!isPlaying || !serverTs) return basePos;
  const transit = Math.max(0, Math.min(5.0, estimatedServerNowSec() - serverTs));
  return basePos + transit * (playbackRate || 1.0);
}

function getActivePlaybackState() {
  if (hasNativeAndroidPlayer && window.__nativePlayerState) {
    const st = window.__nativePlayerState;
    return {
      isPlaying: Boolean(st.is_playing),
      position: Number(st.position || 0),
      playbackRate: Number(st.playback_rate || canonicalPlaybackRate || 1.0),
      buffering: Boolean(st.buffering),
    };
  }
  return {
    isPlaying: !videoEl.paused && !pausedByBarrier,
    position: Number(videoEl.currentTime || 0),
    playbackRate: Number(canonicalPlaybackRate || videoEl.playbackRate || 1.0),
    buffering: videoBuffering || (dualSyncActive && audioBuffering),
  };
}

// ---------- Unified Player (Native ExoPlayer Bridge + Upgraded Web Dual-HLS/DASH Player) ----------

function resetWebPlayers() {
  dualSyncActive = false;
  videoBuffering = false;
  audioBuffering = false;
  hasReachedReadyForMedia = false;
  pausedByBarrier = false;
  canonicalPlaybackRate = 1.0;

  syncIndicator.classList.add("hidden");
  manifestAudioBar.classList.add("hidden");
  manifestAudioSelect.innerHTML = "";
  if (localDriftIntervalId) {
    clearInterval(localDriftIntervalId);
    localDriftIntervalId = null;
  }

  if (hlsVideoInstance) {
    hlsVideoInstance.destroy();
    hlsVideoInstance = null;
  }
  if (dashVideoInstance) {
    dashVideoInstance.reset();
    dashVideoInstance = null;
  }
  if (hlsAudioInstance) {
    hlsAudioInstance.destroy();
    hlsAudioInstance = null;
  }
  if (dashAudioInstance) {
    dashAudioInstance.reset();
    dashAudioInstance = null;
  }

  videoEl.pause();
  audioEl.pause();
  videoEl.muted = false;
  videoEl.removeAttribute("src");
  audioEl.removeAttribute("src");
  videoEl.load();
  audioEl.load();
}

function populateManifestAudioTracks(tracks, activeIndex, onSelect) {
  if (!tracks || tracks.length <= 1) {
    manifestAudioBar.classList.add("hidden");
    return;
  }
  manifestAudioBar.classList.remove("hidden");
  manifestAudioSelect.innerHTML = tracks
    .map((t, i) => `<option value="${i}" ${i === activeIndex ? "selected" : ""}>${t.label}</option>`)
    .join("");
  manifestAudioSelect.onchange = () => onSelect(Number(manifestAudioSelect.value));
}

function isUrlHls(url, flag) {
  if (flag) return true;
  return /\.m3u8(\?|#|$)/i.test(url || "") || /mpegurl/i.test(url || "");
}

function isUrlDash(url, flag) {
  if (flag) return true;
  return /\.mpd(\?|#|$)/i.test(url || "") || /dash\+xml/i.test(url || "");
}

function buildWebProxyUrl(rawUrl, headers = {}) {
  if (!rawUrl || rawUrl.startsWith("/api/proxy")) return rawUrl;
  const params = new URLSearchParams();
  params.set("url", rawUrl);
  for (const [k, v] of Object.entries(headers || {})) {
    if (!v) continue;
    const low = k.toLowerCase();
    if (low === "referer") params.set("referer", v);
    else if (low === "origin") params.set("origin", v);
    else if (low === "user-agent") params.set("ua", v);
  }
  return `/api/proxy?${params.toString()}`;
}

function shouldAutoProxyOnWeb(playback) {
  if (hasNativeAndroidPlayer || hasDesktopProxy) return false;
  const hdrs = playback && playback.headers;
  if (!hdrs) return false;
  return Object.keys(hdrs).some((k) => {
    const low = k.toLowerCase();
    return (low === "referer" || low === "origin") && Boolean(hdrs[k]);
  });
}

function attachVideoSource(url, isHls, isDash, showInternalAudioSelector = true, onFatalLoadError = null) {
  if (isUrlDash(url, isDash) && window.dashjs) {
    dashVideoInstance = dashjs.MediaPlayer().create();
    dashVideoInstance.initialize(videoEl, url, false);
    if (onFatalLoadError) {
      dashVideoInstance.on(dashjs.MediaPlayer.events.ERROR, (e) => onFatalLoadError(e && e.error ? e.error.message : "DASH error"));
    }
    if (showInternalAudioSelector) {
      dashVideoInstance.on(dashjs.MediaPlayer.events.STREAM_INITIALIZED, () => {
        const audioTracks = dashVideoInstance.getTracksFor("audio") || [];
        const current = dashVideoInstance.getCurrentTrackFor("audio");
        const activeIdx = Math.max(0, audioTracks.indexOf(current));
        populateManifestAudioTracks(
          audioTracks.map((t, i) => ({
            label: `${t.lang || "Audio " + (i + 1)}${t.roles && t.roles.length ? " (" + t.roles.join(",") + ")" : ""}`,
          })),
          activeIdx,
          (idx) => {
            if (audioTracks[idx]) dashVideoInstance.setCurrentTrack(audioTracks[idx]);
          }
        );
      });
    }
  } else if (isUrlHls(url, isHls) && window.Hls && Hls.isSupported()) {
    hlsVideoInstance = new Hls({ enableWorker: true, lowLatencyMode: false });
    hlsVideoInstance.loadSource(url);
    hlsVideoInstance.attachMedia(videoEl);
    if (onFatalLoadError) {
      hlsVideoInstance.on(Hls.Events.ERROR, (_evt, data) => {
        if (data && data.fatal) {
          const code = data.response && data.response.code ? `HTTP ${data.response.code}` : (data.details || data.type);
          onFatalLoadError(code);
        }
      });
    }
    if (showInternalAudioSelector) {
      hlsVideoInstance.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => {
        const tracks = hlsVideoInstance.audioTracks || [];
        populateManifestAudioTracks(
          tracks.map((t, i) => ({ label: t.name || t.lang || `Audio ${i + 1}` })),
          hlsVideoInstance.audioTrack,
          (idx) => {
            hlsVideoInstance.audioTrack = idx;
          }
        );
      });
    }
  } else {
    videoEl.src = url;
  }
}

function attachAudioSource(audioUrl, onFatalLoadError = null) {
  if (isUrlDash(audioUrl, false) && window.dashjs) {
    dashAudioInstance = dashjs.MediaPlayer().create();
    dashAudioInstance.initialize(audioEl, audioUrl, false);
  } else if (isUrlHls(audioUrl, false) && window.Hls && Hls.isSupported()) {
    hlsAudioInstance = new Hls();
    hlsAudioInstance.loadSource(audioUrl);
    hlsAudioInstance.attachMedia(audioEl);
    if (onFatalLoadError) {
      hlsAudioInstance.on(Hls.Events.ERROR, (_evt, data) => {
        if (data && data.fatal && data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          onFatalLoadError();
        }
      });
    }
  } else {
    audioEl.src = audioUrl;
  }
}

function startLocalDualTrackPhaseLock() {
  if (localDriftIntervalId) clearInterval(localDriftIntervalId);
  localDriftIntervalId = setInterval(() => {
    if (!dualSyncActive || videoEl.paused || pausedByBarrier || videoBuffering || audioBuffering) {
      return;
    }
    const vTime = videoEl.currentTime || 0;
    const aTime = audioEl.currentTime || 0;
    const diff = vTime - aTime;
    const absDiff = Math.abs(diff);
    const baseRate = videoEl.playbackRate || canonicalPlaybackRate || 1.0;

    if (absDiff > 0.28) {
      audioEl.currentTime = vTime;
      audioEl.playbackRate = baseRate;
    } else if (absDiff > 0.045) {
      // Smooth proportional micro-rate adjustment without audible clicks
      const nudge = Math.max(-0.04, Math.min(0.04, diff * 0.25));
      audioEl.playbackRate = Math.max(0.25, Math.min(4.0, baseRate + nudge));
    } else if (Math.abs(audioEl.playbackRate - baseRate) > 0.005) {
      audioEl.playbackRate = baseRate;
    }
  }, 250);
}

async function loadUnifiedPlayback(playback, title = null, broadcast = false, options = {}) {
  if (!playback || !playback.video_url) return;
  const autoPlay = Boolean(options.autoPlay);
  const alreadyLoadedNative = Boolean(options.alreadyLoadedNative);
  const forceProxy = Boolean(options.forceProxy);
  const sourceUrl = options.sourceUrl || (currentData ? currentData.source_url : "") || lastLoadedSourceUrl || "";

  lastLoadedPlayback = playback;
  lastLoadedTitle = title || mediaTitle.textContent || "Untitled Stream";
  if (sourceUrl) lastLoadedSourceUrl = sourceUrl;
  if (!options.isGuestReExtracted) {
    hasAttemptedGuestExtraction = false;
  }
  if (!forceProxy) {
    currentProxyFallbackTried = false;
  }

  // Broadcast immediately so peers receive media_changed without waiting for local proxy registration
  if (broadcast) {
    const mediaMsg = {
      type: "load_media",
      auto_play: autoPlay,
      media: {
        title: title || mediaTitle.textContent || "Untitled Stream",
        source_url: sourceUrl,
        playback,
      },
    };
    if (ws && ws.readyState === WebSocket.OPEN) {
      pendingMediaBroadcast = null;
      ws.send(JSON.stringify(mediaMsg));
    } else if (intendedRoomId || currentRoomId) {
      const targetCode = intendedRoomId || currentRoomId;
      pendingMediaBroadcast = mediaMsg;
      // HTTP fallback guarantees room state updates on the server immediately even if WS is reconnecting
      fetch(`/api/rooms/${encodeURIComponent(targetCode)}/media`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          ...mediaMsg,
          by: (nicknameInput.value || "").trim() || "Host",
        }),
      }).catch(() => {});
      if (!ws || ws.readyState === WebSocket.CLOSED) {
        connectToRoom(targetCode);
      }
    }
  }

  // Wait for Electron main-process session header proxy BEFORE hls.js/dash.js fires requests
  if (hasDesktopProxy && playback.headers) {
    const urls = [playback.video_url, playback.audio_url].filter(Boolean);
    try {
      await window.StreamSyncDesktop.registerStreamHeaders(urls, playback.headers);
    } catch (_) {}
  }

  resetWebPlayers();
  updateStatusStripVisibility();
  if (waitingHostBanner) waitingHostBanner.classList.add("hidden");
  if (title) mediaTitle.textContent = title;

  const isPaired = playback.mode === "paired" && Boolean(playback.audio_url);
  if (isPaired) {
    syncIndicator.classList.remove("hidden");
    nowPlayingEl.textContent = playback.label || "Unified Video + Audio";
  } else {
    nowPlayingEl.textContent = playback.label || "Playing stream";
  }

  if (hasNativeAndroidPlayer) {
    playerSection.classList.add("hidden");
    if (!alreadyLoadedNative) {
      window.AndroidNativePlayer.loadMedia(
        JSON.stringify({
          video_url: playback.video_url,
          audio_url: playback.audio_url || "",
          is_hls: Boolean(playback.is_hls),
          is_dash: Boolean(playback.is_dash),
          title: title || mediaTitle.textContent || "Untitled Stream",
          label: playback.label || "",
          headers: playback.headers || {},
          auto_play: autoPlay,
        })
      );
    }
    return;
  }

  playerSection.classList.remove("hidden");

  // Always try DIRECT playback from the viewer's own IP first (avoids datacenter IP blocks on IP-signed tokens);
  // automatically fall back to /api/proxy ONLY if direct playback hits CORS/Referer network error!
  const useProxy = forceProxy;
  const effectiveVideoUrl = useProxy
    ? buildWebProxyUrl(playback.video_url, playback.headers)
    : playback.video_url;
  const effectiveAudioUrl = isPaired
    ? useProxy
      ? buildWebProxyUrl(playback.audio_url, playback.headers)
      : playback.audio_url
    : null;

  const triggerProxyFallback = (errReason = "") => {
    // On Electron Desktop Guest: if direct URL fails (IP-locked token), auto-extract fresh token from source_url locally!
    if (hasDesktopProxy) {
      tryGuestLocalExtraction(errReason);
      return;
    }
    if (hasNativeAndroidPlayer) return;
    if (currentProxyFallbackTried) {
      roomSyncLog.textContent = `Stream blocked by CDN (${errReason || "403 / IP-locked token"}). Join using StreamSync Desktop (.exe) or Android App.`;
      roomSyncLog.classList.remove("hidden");
      updateStatusStripVisibility();
      return;
    }
    currentProxyFallbackTried = true;
    roomSyncLog.textContent = `Direct stream blocked (${errReason || "CORS"}) — switching to StreamSync Proxy...`;
    roomSyncLog.classList.remove("hidden");
    updateStatusStripVisibility();
    const savedTime = videoEl.currentTime || 0;
    const wasPlaying = !videoEl.paused || autoPlay;
    loadUnifiedPlayback(playback, title, false, {
      autoPlay: wasPlaying,
      forceProxy: true,
    }).then(() => {
      if (savedTime > 0.5) {
        setTimeout(() => {
          videoEl.currentTime = savedTime;
          if (dualSyncActive) audioEl.currentTime = savedTime;
        }, 350);
      }
    });
  };

  if (isPaired) {
    dualSyncActive = true;
    videoEl.muted = true;
    audioEl.muted = false;
    audioEl.volume = videoEl.volume;
    attachVideoSource(effectiveVideoUrl, playback.is_hls, playback.is_dash, false, triggerProxyFallback);
    attachAudioSource(effectiveAudioUrl, triggerProxyFallback);
    startLocalDualTrackPhaseLock();
    if (autoPlay) {
      videoEl.play().catch(() => {});
    }
  } else {
    attachVideoSource(effectiveVideoUrl, playback.is_hls, playback.is_dash, true, triggerProxyFallback);
    if (autoPlay) {
      videoEl.play().catch(() => {});
    }
  }
}

// ---------- Web HTML5 Dual-Track & Buffering Listeners ----------

function reportBufferStateToRoom(isBuffering) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  const st = getActivePlaybackState();
  ws.send(
    JSON.stringify({
      type: "buffer_state",
      buffering: Boolean(isBuffering),
      position: st.position || 0,
    })
  );
}

function updateStatusStripVisibility() {
  if (!roomStatusStrip) return;
  const hasBanner = !bufferBarrierBanner.classList.contains("hidden");
  const hasLog = !roomSyncLog.classList.contains("hidden");
  roomStatusStrip.classList.toggle("hidden", !hasBanner && !hasLog);
}

function updateWebCombinedBuffering() {
  const anyBuffering = videoBuffering || (dualSyncActive && audioBuffering);
  if (dualSyncActive) {
    if (audioBuffering && !videoEl.paused) {
      withSuppressedRoomSync(() => videoEl.pause());
    } else if (!anyBuffering && !pausedByBarrier && videoEl.paused && audioEl.readyState >= 2 && shouldResumeAfterLocalStall) {
      shouldResumeAfterLocalStall = false;
      withSuppressedRoomSync(() => {
        audioEl.currentTime = videoEl.currentTime;
        videoEl.play().catch(() => {});
        audioEl.play().catch(() => {});
      });
    }
  }
  // Never pause the whole room while a newly-joined client is still loading its first frame
  if (anyBuffering && !hasReachedReadyForMedia) return;
  reportBufferStateToRoom(anyBuffering);
}

let shouldResumeAfterLocalStall = false;

if (!hasNativeAndroidPlayer) {
  videoEl.addEventListener("error", () => {
    videoBuffering = false;
    reportBufferStateToRoom(false);
    if (!currentProxyFallbackTried && lastLoadedPlayback && !hasDesktopProxy) {
      currentProxyFallbackTried = true;
      loadUnifiedPlayback(lastLoadedPlayback, lastLoadedTitle, false, {
        autoPlay: true,
        forceProxy: true,
      });
    }
  });

  videoEl.addEventListener("play", () => {
    if (dualSyncActive) {
      audioEl.currentTime = videoEl.currentTime;
      audioEl.playbackRate = videoEl.playbackRate;
      audioEl.play().catch(() => {});
    }
    emitRoomSync("play");
  });

  videoEl.addEventListener("pause", () => {
    if (dualSyncActive) audioEl.pause();
    if (!videoBuffering && !audioBuffering && !pausedByBarrier) {
      emitRoomSync("pause");
    }
  });

  videoEl.addEventListener("seeking", () => {
    if (dualSyncActive) audioEl.currentTime = videoEl.currentTime;
  });

  videoEl.addEventListener("seeked", () => {
    if (dualSyncActive) audioEl.currentTime = videoEl.currentTime;
    emitRoomSync("seek");
  });

  videoEl.addEventListener("waiting", () => {
    videoBuffering = true;
    if (dualSyncActive) audioEl.pause();
    updateWebCombinedBuffering();
  });

  videoEl.addEventListener("playing", () => {
    hasReachedReadyForMedia = true;
    videoBuffering = false;
    if (dualSyncActive && !audioBuffering && !pausedByBarrier) {
      audioEl.currentTime = videoEl.currentTime;
      audioEl.play().catch(() => {});
    }
    updateWebCombinedBuffering();
  });

  videoEl.addEventListener("canplay", () => {
    hasReachedReadyForMedia = true;
    if (videoBuffering) {
      videoBuffering = false;
      updateWebCombinedBuffering();
    }
  });

  audioEl.addEventListener("waiting", () => {
    if (!dualSyncActive) return;
    if (!videoEl.paused) shouldResumeAfterLocalStall = true;
    audioBuffering = true;
    updateWebCombinedBuffering();
  });

  audioEl.addEventListener("canplay", () => {
    if (!dualSyncActive) return;
    if (audioBuffering) {
      audioBuffering = false;
      updateWebCombinedBuffering();
    }
  });

  videoEl.addEventListener("ratechange", () => {
    if (!suppressRoomSync) {
      canonicalPlaybackRate = videoEl.playbackRate || 1.0;
    }
    if (dualSyncActive) audioEl.playbackRate = videoEl.playbackRate;
    emitRoomSync("ratechange");
  });

  videoEl.addEventListener("volumechange", () => {
    if (dualSyncActive) {
      // Keep <video> muted while forwarding volume changes to external <audio>
      audioEl.volume = videoEl.volume;
      if (!videoEl.muted) {
        videoEl.muted = true;
      }
    }
  });
}

function tryGuestLocalExtraction(reason = "") {
  if (hasAttemptedGuestExtraction || !lastLoadedSourceUrl) return false;
  if (!lastLoadedSourceUrl.startsWith("http://") && !lastLoadedSourceUrl.startsWith("https://")) return false;
  hasAttemptedGuestExtraction = true;

  const wantPaired = Boolean(lastLoadedPlayback && lastLoadedPlayback.mode === "paired");
  roomSyncLog.textContent = `Refreshing stream for your IP (${reason || "CDN token"})...`;
  roomSyncLog.classList.remove("hidden");
  updateStatusStripVisibility();

  if (hasNativeAndroidPlayer && typeof window.AndroidNativePlayer.extractFromPageUrl === "function") {
    window.AndroidNativePlayer.extractFromPageUrl(lastLoadedSourceUrl, wantPaired);
    return true;
  }
  if (hasDesktopProxy && typeof window.StreamSyncDesktop.extractFromSourceUrl === "function") {
    window.StreamSyncDesktop.extractFromSourceUrl(lastLoadedSourceUrl, wantPaired)
      .then((freshPlayback) => {
        if (freshPlayback && freshPlayback.video_url) {
          loadUnifiedPlayback(freshPlayback, lastLoadedTitle, false, {
            autoPlay: lastKnownRoomSync ? Boolean(lastKnownRoomSync.is_playing) : true,
            sourceUrl: lastLoadedSourceUrl,
            isGuestReExtracted: true,
          }).then(() => {
            if (lastKnownRoomSync) {
              setTimeout(() => {
                applyRemoteSync(
                  lastKnownRoomSync.is_playing,
                  lastKnownRoomSync.position,
                  lastKnownRoomSync.playback_rate,
                  {
                    serverTs: lastKnownRoomSync.server_ts,
                    logText: "Stream refreshed & synced with room",
                  }
                );
              }, 350);
            }
          });
        } else {
          roomSyncLog.textContent = "Could not extract stream locally — open Browser tab and select stream manually.";
          roomSyncLog.classList.remove("hidden");
          updateStatusStripVisibility();
        }
      })
      .catch(() => {});
    return true;
  }
  return false;
}

window.onGuestNativeReExtracted = (payload) => {
  if (!payload || !payload.video_url) return;
  const media = buildExtensionPlayback(
    payload.video_url,
    payload.audio_url || "",
    lastLoadedTitle || payload.title || "Synced Stream",
    {
      is_hls: payload.is_hls,
      is_dash: payload.is_dash,
      label: payload.label,
      headers: payload.headers,
    }
  );
  loadUnifiedPlayback(media.playback, media.title, false, {
    autoPlay: lastKnownRoomSync ? Boolean(lastKnownRoomSync.is_playing) : true,
    alreadyLoadedNative: Boolean(payload.already_loaded_native),
    sourceUrl: lastLoadedSourceUrl,
    isGuestReExtracted: true,
  });
  if (lastKnownRoomSync) {
    setTimeout(() => {
      applyRemoteSync(
        lastKnownRoomSync.is_playing,
        lastKnownRoomSync.position,
        lastKnownRoomSync.playback_rate,
        {
          serverTs: lastKnownRoomSync.server_ts,
          logText: "Stream refreshed for your IP & synced",
        }
      );
    }, 300);
  }
};

// ---------- Native Android ExoPlayer Event Callback ----------

window.onNativePlayerEvent = (payload) => {
  if (!payload || !payload.event) return;
  window.__nativePlayerState = payload;

  if (payload.event === "playback_error") {
    reportBufferStateToRoom(false);
    tryGuestLocalExtraction("ExoPlayer 403/IP token");
    return;
  }
  if (payload.event === "buffering_start") {
    reportBufferStateToRoom(true);
    return;
  }
  if (payload.event === "buffering_end") {
    reportBufferStateToRoom(false);
    return;
  }
  if (suppressRoomSync) return;

  if (
    payload.event === "play" ||
    payload.event === "pause" ||
    payload.event === "seek" ||
    payload.event === "ratechange"
  ) {
    canonicalPlaybackRate = Number(payload.playback_rate || 1.0);
    emitRoomSync(payload.event);
  }
};

// ---------- Room Synchronization (Host Mode, RTT Clock Sync & Buffering Barrier) ----------

function withSuppressedRoomSync(fn, holdMs = 220) {
  suppressRoomSync = true;
  if (suppressTimer) clearTimeout(suppressTimer);
  try {
    fn();
  } finally {
    suppressTimer = setTimeout(() => {
      suppressRoomSync = false;
    }, holdMs);
  }
}

function canLocalControlRoom() {
  if (!currentRoomId) return true;
  if (roomControlMode === "everyone") return true;
  return Boolean(myClientId && myClientId === roomHostId);
}

function emitRoomSync(action) {
  if (suppressRoomSync || !ws || ws.readyState !== WebSocket.OPEN) return;
  const st = getActivePlaybackState();
  ws.send(
    JSON.stringify({
      type: "sync",
      action,
      is_playing: st.isPlaying,
      position: st.position || 0,
      playback_rate: st.playbackRate || 1.0,
    })
  );
}

function applyRemoteSync(isPlaying, rawPosition, playbackRate, options = {}) {
  const { serverTs = null, syncSeq = null, logText = "", isHeartbeat = false } = options;

  if (typeof syncSeq === "number" && syncSeq < lastAppliedSyncSeq) {
    return;
  }
  if (typeof syncSeq === "number") {
    lastAppliedSyncSeq = syncSeq;
  }
  lastKnownRoomSync = {
    is_playing: Boolean(isPlaying),
    position: Number(rawPosition || 0),
    playback_rate: Number(playbackRate || 1.0),
    server_ts: serverTs || Date.now() / 1000,
  };

  pausedByBarrier = false;
  bufferBarrierBanner.classList.add("hidden");

  const baseRate = playbackRate && playbackRate > 0 ? playbackRate : 1.0;
  canonicalPlaybackRate = baseRate;
  const targetPos = projectPosition(rawPosition, isPlaying, baseRate, serverTs);
  const st = getActivePlaybackState();
  const drift = targetPos - st.position;
  const absDrift = Math.abs(drift);

  // Smooth micro-rate drift correction during heartbeats; hard seek on larger divergence or explicit seek/play/pause
  let effectiveRate = baseRate;
  const seekThreshold = isHeartbeat ? 0.42 : 0.30;
  if (isPlaying && isHeartbeat && absDrift > 0.08 && absDrift <= seekThreshold) {
    const nudge = Math.max(-0.03, Math.min(0.03, drift * 0.12));
    effectiveRate = Math.max(0.25, Math.min(4.0, baseRate + nudge));
  }

  if (hasNativeAndroidPlayer) {
    window.AndroidNativePlayer.applySync(
      Boolean(isPlaying),
      Number(targetPos),
      Number(baseRate),
      Number(effectiveRate),
      Number(seekThreshold)
    );
  } else {
    withSuppressedRoomSync(() => {
      if (Math.abs(videoEl.playbackRate - effectiveRate) > 0.008) {
        videoEl.playbackRate = effectiveRate;
        if (dualSyncActive) audioEl.playbackRate = effectiveRate;
      }
      if (absDrift > seekThreshold) {
        videoEl.currentTime = targetPos;
        if (dualSyncActive) audioEl.currentTime = targetPos;
      }
      if (isPlaying && videoEl.paused) {
        videoEl.play().catch((err) => {
          if (err && err.name === "NotAllowedError") {
            roomSyncLog.textContent = "Browser blocked autoplay — click Play on the video to sync!";
            roomSyncLog.classList.remove("hidden");
            updateStatusStripVisibility();
          }
        });
        if (dualSyncActive) audioEl.play().catch(() => {});
      } else if (!isPlaying && !videoEl.paused) {
        videoEl.pause();
        if (dualSyncActive) audioEl.pause();
      }
    });
  }

  if (logText) {
    roomSyncLog.textContent = logText;
    roomSyncLog.classList.remove("hidden");
    updateStatusStripVisibility();
  }
}

function handleBufferBarrier(msg) {
  if (typeof msg.sync_seq === "number") {
    lastAppliedSyncSeq = Math.max(lastAppliedSyncSeq, msg.sync_seq);
  }
  if (msg.active) {
    pausedByBarrier = true;
    const names = (msg.waiting_for || []).join(", ") || "room member";
    bufferBarrierBanner.textContent = `Waiting for ${names} to buffer... (Room paused in sync)`;
    bufferBarrierBanner.classList.remove("hidden");
    updateStatusStripVisibility();

    if (hasNativeAndroidPlayer) {
      window.AndroidNativePlayer.setBufferingBarrier(true, Number(msg.position || 0), Number(msg.playback_rate || 1));
    } else {
      withSuppressedRoomSync(() => {
        videoEl.pause();
        if (dualSyncActive) audioEl.pause();
      });
    }
  } else {
    pausedByBarrier = false;
    bufferBarrierBanner.classList.add("hidden");
    updateStatusStripVisibility();
    const targetPos = projectPosition(msg.position, msg.is_playing, msg.playback_rate, msg.server_ts);
    if (hasNativeAndroidPlayer) {
      window.AndroidNativePlayer.setBufferingBarrier(false, Number(targetPos), Number(msg.playback_rate || 1));
    } else {
      applyRemoteSync(Boolean(msg.is_playing), targetPos, msg.playback_rate || 1, {
        logText: "All viewers buffered — playback resumed",
      });
    }
  }
}

function updateRoomPermissionsUI() {
  const inRoom = Boolean(currentRoomId);
  const isHost = Boolean(myClientId && myClientId === roomHostId);
  const isGuest = inRoom && Boolean(myClientId) && !isHost;

  // Hide the URL extractor bar when joining another Host's room; always show the streaming window on Web/PC!
  if (extractCard) {
    extractCard.classList.toggle("hidden", isGuest);
  }
  if (isGuest) {
    tracksSection.classList.add("hidden");
    if (!hasNativeAndroidPlayer) {
      playerSection.classList.remove("hidden");
    } else {
      playerSection.classList.add("hidden");
    }
    if (waitingHostBanner) {
      waitingHostBanner.classList.toggle("hidden", Boolean(lastLoadedPlayback));
    }
  } else if (waitingHostBanner && !inRoom) {
    waitingHostBanner.classList.add("hidden");
  }

  const modeText = roomControlMode === "host_only" ? "🔒 Controls: Host Only" : "🔓 Controls: Everyone";
  controlModeLabel.textContent = modeText;
  toggleControlModeBtn.classList.toggle("hidden", !isHost);
  if (isHost) {
    toggleControlModeBtn.textContent =
      roomControlMode === "host_only" ? "Unlock for All" : "Lock to Host Only";
  }
}

toggleControlModeBtn.addEventListener("click", () => {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  const nextMode = roomControlMode === "host_only" ? "everyone" : "host_only";
  ws.send(JSON.stringify({ type: "set_control_mode", control_mode: nextMode }));
});

function appendChat(nickname, text, isSystem = false) {
  const el = document.createElement("div");
  if (isSystem) {
    el.className = "chat-sys";
    el.textContent = text;
  } else {
    el.className = "chat-msg";
    const strong = document.createElement("strong");
    strong.textContent = `${nickname}: `;
    el.append(strong, document.createTextNode(text));
  }
  chatMessages.appendChild(el);
  chatMessages.scrollTop = chatMessages.scrollHeight;
}

function renderMembers(members) {
  memberCount.textContent = `${members.length} online`;
  memberList.innerHTML = "";
  members.forEach((m) => {
    const pill = document.createElement("span");
    pill.className = m.is_host ? "pill pill-accent" : "pill";
    const suffix = m.is_host ? " (Host)" : "";
    const bufTag = m.is_buffering ? " ⏳" : "";
    pill.textContent = `${m.nickname}${suffix}${bufTag}`;
    memberList.appendChild(pill);
  });
}

function stopRoomTimers() {
  if (pingIntervalId) {
    clearInterval(pingIntervalId);
    pingIntervalId = null;
  }
  if (heartbeatIntervalId) {
    clearInterval(heartbeatIntervalId);
    heartbeatIntervalId = null;
  }
}

function startRoomTimers() {
  stopRoomTimers();
  const sendPing = () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(
        JSON.stringify({
          type: "ping",
          client_ts: performance.now(),
          wall_ts: Date.now() / 1000,
        })
      );
    }
  };
  sendPing();
  pingIntervalId = setInterval(sendPing, 5000);

  heartbeatIntervalId = setInterval(() => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (!myClientId || myClientId !== roomHostId || pausedByBarrier) return;
    const st = getActivePlaybackState();
    if (!st.isPlaying || st.buffering) return;
    ws.send(
      JSON.stringify({
        type: "heartbeat",
        is_playing: true,
        position: st.position || 0,
        playback_rate: st.playbackRate || 1.0,
      })
    );
  }, 3000);
}

function setRoomUI(roomId) {
  const inRoom = Boolean(roomId);
  currentRoomId = roomId;
  roomSidebar.classList.toggle("hidden", !inRoom);
  inviteRoomBtn.classList.toggle("hidden", !inRoom);
  leaveRoomBtn.classList.toggle("hidden", !inRoom);
  rttBadge.classList.toggle("hidden", !inRoom);
  roomStatusBadge.textContent = inRoom ? `Room ${roomId}` : "Solo Mode";
  roomStatusBadge.classList.toggle("badge-muted", !inRoom);
  sidebarRoomCode.textContent = roomId || "";

  if (!inRoom) {
    stopRoomTimers();
    myClientId = null;
    roomHostId = null;
    lastAppliedSyncSeq = -1;
    bufferBarrierBanner.classList.add("hidden");
    updateStatusStripVisibility();
    if (extractCard) extractCard.classList.remove("hidden");
    if (waitingHostBanner) waitingHostBanner.classList.add("hidden");
  }

  const url = new URL(window.location.href);
  if (inRoom) {
    url.searchParams.set("room", roomId);
  } else {
    url.searchParams.delete("room");
  }
  window.history.replaceState({}, "", url);
}

function connectToRoom(roomId) {
  const code = roomId.trim().toUpperCase();
  if (!code) return;
  intendedRoomId = code;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    try {
      ws.onclose = null;
      ws.close();
    } catch (_) {}
  }

  const nick = encodeURIComponent(nicknameInput.value.trim() || "Guest");
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${proto}://${window.location.host}/ws/rooms/${code}?nickname=${nick}`);
  ws = socket;

  socket.onopen = () => {
    if (ws !== socket) return;
    reconnectAttempts = 0;
    setRoomUI(code);
    chatMessages.innerHTML = "";
    appendChat("", `Connected to room ${code}.`, true);
    startRoomTimers();
  };

  socket.onmessage = (event) => {
    if (ws !== socket) return;
    const msg = JSON.parse(event.data);

    if (msg.type === "pong") {
      const nowPerf = performance.now();
      const nowWallSec = Date.now() / 1000;
      if (typeof msg.client_ts === "number" && typeof msg.server_ts === "number") {
        rttMs = Math.max(1, Math.round(nowPerf - msg.client_ts));
        const rttSec = rttMs / 1000;
        const sampleOffset = msg.server_ts - (nowWallSec - rttSec / 2);
        serverClockOffsetSec = hasClockSample
          ? serverClockOffsetSec * 0.7 + sampleOffset * 0.3
          : sampleOffset;
        hasClockSample = true;
        rttBadge.textContent = `RTT ${rttMs}ms`;
      }
      return;
    }

    if (msg.type === "room_state") {
      myClientId = msg.client_id || null;
      roomHostId = msg.host_id || null;
      roomControlMode = msg.control_mode || "everyone";
      lastAppliedSyncSeq = typeof msg.sync_seq === "number" ? msg.sync_seq : 0;
      if (typeof msg.server_ts === "number" && !hasClockSample) {
        serverClockOffsetSec = msg.server_ts - Date.now() / 1000;
      }

      renderMembers(msg.members || []);
      (msg.chat_history || []).forEach((c) => appendChat(c.nickname, c.text));

      if (pendingMediaBroadcast) {
        const queued = pendingMediaBroadcast;
        pendingMediaBroadcast = null;
        socket.send(JSON.stringify(queued));
      } else if (msg.media && msg.media.playback) {
        loadUnifiedPlayback(msg.media.playback, msg.media.title, false, {
          sourceUrl: msg.media.source_url || "",
        });
        setTimeout(() => {
          if (msg.buffering_active) {
            handleBufferBarrier({
              active: true,
              waiting_for: msg.waiting_for,
              position: msg.position,
              playback_rate: msg.playback_rate,
              sync_seq: msg.sync_seq,
            });
          } else {
            applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, {
              serverTs: msg.server_ts,
              syncSeq: msg.sync_seq,
              logText: "Synced with room",
            });
          }
        }, 300);
      } else if (myClientId && myClientId === roomHostId && lastLoadedPlayback) {
        // Host already loaded a stream before creating/reconnecting to the room -> broadcast it immediately!
        const st = getActivePlaybackState();
        socket.send(
          JSON.stringify({
            type: "load_media",
            auto_play: st.isPlaying,
            media: {
              title: lastLoadedTitle || "Untitled Stream",
              source_url: currentData ? currentData.source_url : "",
              playback: lastLoadedPlayback,
            },
          })
        );
        if (st.position > 0.5) {
          setTimeout(() => emitRoomSync(st.isPlaying ? "play" : "pause"), 150);
        }
      }
      updateRoomPermissionsUI();
      return;
    }

    if (msg.type === "members_updated") {
      if (msg.host_id) roomHostId = msg.host_id;
      if (msg.control_mode) roomControlMode = msg.control_mode;
      updateRoomPermissionsUI();
      renderMembers(msg.members || []);
      if (msg.system_message) appendChat("", msg.system_message, true);
      return;
    }

    if (msg.type === "control_mode_changed") {
      roomControlMode = msg.control_mode || "host_only";
      updateRoomPermissionsUI();
      if (msg.system_message) appendChat("", msg.system_message, true);
      return;
    }

    if (msg.type === "media_changed") {
      if (msg.media && msg.media.playback) {
        const loadedByMeOnNative = hasNativeAndroidPlayer && msg.sender_id && msg.sender_id === myClientId;
        if (!loadedByMeOnNative) {
          loadUnifiedPlayback(msg.media.playback, msg.media.title, false, {
            autoPlay: Boolean(msg.is_playing),
            sourceUrl: msg.media.source_url || "",
          });
          setTimeout(() => {
            applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, {
              serverTs: msg.server_ts,
              syncSeq: msg.sync_seq,
              logText: `${msg.by} loaded: ${msg.media.title}`,
            });
          }, 250);
        } else if (typeof msg.sync_seq === "number") {
          lastAppliedSyncSeq = msg.sync_seq;
        }
        appendChat("", `${msg.by} loaded "${msg.media.title}"`, true);
      }
      return;
    }

    if (msg.type === "sync") {
      applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, {
        serverTs: msg.server_ts,
        syncSeq: msg.sync_seq,
        logText: `${msg.by}: ${msg.action} @ ${formatDuration(msg.position)}`,
      });
      return;
    }

    if (msg.type === "heartbeat") {
      applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, {
        serverTs: msg.server_ts,
        syncSeq: msg.sync_seq,
        isHeartbeat: true,
      });
      return;
    }

    if (msg.type === "buffer_barrier") {
      handleBufferBarrier(msg);
      return;
    }

    if (msg.type === "sync_denied") {
      applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, {
        serverTs: msg.server_ts,
        syncSeq: msg.sync_seq,
        logText: msg.reason || "Host-only control active",
      });
      return;
    }

    if (msg.type === "chat" && msg.message) {
      appendChat(msg.message.nickname, msg.message.text);
    }
  };

  socket.onclose = () => {
    if (ws !== socket) return;
    stopRoomTimers();
    if (intendedRoomId === code) {
      const delayMs = Math.min(8000, 1000 * Math.pow(2, reconnectAttempts));
      reconnectAttempts += 1;
      appendChat("", `Connection lost — reconnecting to ${code}...`, true);
      reconnectTimer = setTimeout(() => {
        if (intendedRoomId === code) connectToRoom(code);
      }, delayMs);
    } else if (currentRoomId === code) {
      setRoomUI(null);
      roomSyncLog.classList.add("hidden");
    }
  };
}

document.addEventListener("visibilitychange", () => {
  if (
    document.visibilityState === "visible" &&
    intendedRoomId &&
    (!ws || ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING)
  ) {
    reconnectAttempts = 0;
    connectToRoom(intendedRoomId);
  }
});

createRoomBtn.addEventListener("click", async () => {
  try {
    const res = await fetch("/api/rooms", { method: "POST" });
    const data = await res.json();
    roomCodeInput.value = data.room_id;
    connectToRoom(data.room_id);
  } catch {
    setStatus("Could not create room.", true);
  }
});

joinRoomBtn.addEventListener("click", () => {
  const code = roomCodeInput.value.trim().toUpperCase();
  if (code) connectToRoom(code);
});

leaveRoomBtn.addEventListener("click", () => {
  intendedRoomId = null;
  pendingMediaBroadcast = null;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (ws) {
    ws.onclose = null;
    ws.close();
  }
  ws = null;
  setRoomUI(null);
});

inviteRoomBtn.addEventListener("click", async () => {
  if (!currentRoomId) return;
  const inviteUrl = `${window.location.origin}/?room=${currentRoomId}`;
  await navigator.clipboard.writeText(inviteUrl);
  inviteRoomBtn.textContent = "Invite Copied!";
  setTimeout(() => (inviteRoomBtn.textContent = "Copy Invite"), 1500);
});

chatForm.addEventListener("submit", (e) => {
  e.preventDefault();
  const text = chatInput.value.trim();
  if (!text || !ws || ws.readyState !== WebSocket.OPEN) return;
  ws.send(JSON.stringify({ type: "chat", text }));
  chatInput.value = "";
});

function buildExtensionPlayback(videoUrl, audioUrl, title, extra = {}) {
  const lowerVideo = (videoUrl || "").toLowerCase();
  const isHls = Boolean(extra.is_hls) || lowerVideo.includes(".m3u8");
  const isDash = Boolean(extra.is_dash) || lowerVideo.includes(".mpd");
  return {
    title: title || "Detected Stream",
    playback: {
      mode: audioUrl ? "paired" : "single",
      video_url: videoUrl,
      audio_url: audioUrl || null,
      is_hls: isHls,
      is_dash: isDash,
      headers: extra.headers || undefined,
      label:
        extra.label ||
        (audioUrl
          ? "Unified Video + Audio"
          : isHls
            ? "HLS Stream"
            : isDash
              ? "DASH Stream"
              : "Video Stream"),
    },
  };
}

// Rich bridge for upgraded Android Native ExoPlayer app
window.loadFromNativePayload = (payload) => {
  if (!payload || !payload.video_url) return;
  const media = buildExtensionPlayback(
    payload.video_url,
    payload.audio_url || "",
    payload.title || "Detected Stream",
    {
      is_hls: payload.is_hls,
      is_dash: payload.is_dash,
      label: payload.label,
      headers: payload.headers,
    }
  );
  loadUnifiedPlayback(media.playback, media.title, true, {
    autoPlay: true,
    alreadyLoadedNative: Boolean(payload.already_loaded_native),
    sourceUrl: payload.source_url || "",
  });
};

// Backward-compatible bridge for legacy callers / Browser Extension
window.loadFromNativeApp = (videoUrl, audioUrl, title) => {
  if (!videoUrl) return;
  const media = buildExtensionPlayback(videoUrl, audioUrl || "", title || "Detected Stream");
  loadUnifiedPlayback(media.playback, media.title, true, { autoPlay: true });
};

const startupParams = new URLSearchParams(window.location.search);
const initialRoom = startupParams.get("room");
const initialVideo = startupParams.get("video");
const initialAudio = startupParams.get("audio");
const initialTitle = startupParams.get("title") || "Detected Stream";

let pendingExtensionMedia = initialVideo
  ? buildExtensionPlayback(initialVideo, initialAudio || "", initialTitle)
  : null;

if (initialRoom) {
  roomCodeInput.value = initialRoom.toUpperCase();
  connectToRoom(initialRoom);
  if (pendingExtensionMedia && ws) {
    const mediaToSend = pendingExtensionMedia;
    ws.addEventListener("open", () => {
      loadUnifiedPlayback(mediaToSend.playback, mediaToSend.title, true);
    });
  }
} else if (pendingExtensionMedia) {
  loadUnifiedPlayback(pendingExtensionMedia.playback, pendingExtensionMedia.title, false);
}

// ---------- Track Inspection & Manual Video + Audio Pairing UI ----------

function describeFormat(fmt) {
  const parts = [fmt.ext.toUpperCase()];
  if (fmt.resolution) parts.push(fmt.resolution);
  if (fmt.fps) parts.push(`${fmt.fps}fps`);
  if (fmt.abr) parts.push(`${Math.round(fmt.abr)}kbps`);
  else if (fmt.tbr) parts.push(`${Math.round(fmt.tbr)}kbps`);
  if (fmt.filesize) parts.push(formatBytes(fmt.filesize));
  if (fmt.format_note) parts.push(fmt.format_note);
  return parts.join(" • ");
}

function renderFormatList(containerId, formats, emptyText, isVideoOnlyTab = false) {
  const container = document.getElementById(containerId);
  container.innerHTML = "";
  if (!formats.length) {
    container.innerHTML = `<p class="subtitle">${emptyText}</p>`;
    return;
  }

  const list = document.createElement("div");
  list.className = "format-list";

  formats.forEach((fmt, idx) => {
    const row = document.createElement("div");
    row.className = "format-row";

    const info = document.createElement("div");
    info.className = "format-info";
    const title = document.createElement("div");
    title.className = "format-title";
    title.textContent = describeFormat(fmt);

    const sub = document.createElement("div");
    sub.className = "format-sub";
    sub.textContent =
      [fmt.vcodec ? `video: ${fmt.vcodec}` : null, fmt.acodec ? `audio: ${fmt.acodec}` : null]
        .filter(Boolean)
        .join(" | ") || `id: ${fmt.format_id}`;

    info.append(title, sub);

    const actions = document.createElement("div");
    actions.className = "format-actions";

    if (isVideoOnlyTab && currentData && currentData.audio_only_formats.length > 0) {
      const pairSelectBtn = document.createElement("button");
      pairSelectBtn.type = "button";
      pairSelectBtn.className = "btn-sm";
      pairSelectBtn.textContent = "Pair with Selected Audio";
      pairSelectBtn.addEventListener("click", () => {
        pairVideoSelect.value = String(idx);
        pairPlayBtn.click();
      });
      actions.appendChild(pairSelectBtn);
    }

    const playBtn = document.createElement("button");
    playBtn.type = "button";
    playBtn.className = isVideoOnlyTab ? "btn-sm btn-secondary" : "btn-sm";
    playBtn.textContent = isVideoOnlyTab ? "Play Muted" : "Play";
    playBtn.addEventListener("click", () => {
      loadUnifiedPlayback(
        {
          mode: "single",
          video_url: fmt.url,
          is_hls: fmt.is_hls,
          is_dash: fmt.is_dash,
          label: describeFormat(fmt),
        },
        currentData ? currentData.title : null,
        true,
        { autoPlay: true }
      );
    });

    const copyBtn = document.createElement("button");
    copyBtn.type = "button";
    copyBtn.className = "btn-sm btn-secondary";
    copyBtn.textContent = "Copy URL";
    copyBtn.addEventListener("click", async () => {
      await navigator.clipboard.writeText(fmt.url);
      copyBtn.textContent = "Copied!";
      setTimeout(() => (copyBtn.textContent = "Copy URL"), 1500);
    });

    actions.append(playBtn, copyBtn);
    row.append(info, actions);
    list.appendChild(row);
  });

  container.appendChild(list);
}

function renderSubtitles(subs) {
  const container = document.getElementById("tab-subs");
  container.innerHTML = "";
  if (!subs.length) {
    container.innerHTML = `<p class="subtitle">No subtitles found.</p>`;
    return;
  }
  const list = document.createElement("div");
  list.className = "format-list";
  subs.forEach((sub) => {
    const row = document.createElement("div");
    row.className = "format-row";
    const info = document.createElement("div");
    info.className = "format-info";
    const tDiv = document.createElement("div");
    tDiv.className = "format-title";
    tDiv.textContent = `${sub.lang.toUpperCase()} (${sub.ext})`;
    const sDiv = document.createElement("div");
    sDiv.className = "format-sub";
    sDiv.textContent = sub.name || "";
    info.append(tDiv, sDiv);

    const copyBtn = document.createElement("button");
    copyBtn.type = "button";
    copyBtn.className = "btn-sm btn-secondary";
    copyBtn.textContent = "Copy URL";
    copyBtn.addEventListener("click", async () => {
      await navigator.clipboard.writeText(sub.url);
      copyBtn.textContent = "Copied!";
      setTimeout(() => (copyBtn.textContent = "Copy URL"), 1500);
    });
    row.append(info, copyBtn);
    list.appendChild(row);
  });
  container.appendChild(list);
}

let pairableVideoPool = [];

function renderResult(data) {
  currentData = data;
  tracksSection.classList.remove("hidden");

  mediaExtractor.textContent = data.extractor;
  mediaExtractor.classList.remove("hidden");

  const dur = formatDuration(data.duration);
  if (dur) {
    mediaDuration.textContent = dur;
    mediaDuration.classList.remove("hidden");
  } else {
    mediaDuration.classList.add("hidden");
  }

  mediaLive.classList.toggle("hidden", !data.is_live);

  document.getElementById("count-combined").textContent = data.combined_formats.length;
  document.getElementById("count-video").textContent = data.video_only_formats.length;
  document.getElementById("count-audio").textContent = data.audio_only_formats.length;
  document.getElementById("count-subs").textContent = data.subtitles.length;

  renderFormatList("tab-combined", data.combined_formats, "No combined video+audio streams found.", false);
  renderFormatList("tab-video", data.video_only_formats, "No video-only streams found.", true);
  renderFormatList("tab-audio", data.audio_only_formats, "No audio-only streams found.", false);
  renderSubtitles(data.subtitles);

  pairableVideoPool = [...data.video_only_formats, ...data.combined_formats];
  if (pairableVideoPool.length && data.audio_only_formats.length) {
    pairControls.classList.remove("hidden");
    pairVideoSelect.innerHTML = pairableVideoPool
      .map((f, i) => {
        const tag = i < data.video_only_formats.length ? "[Video-Only]" : "[Muxed]";
        return `<option value="${i}">${tag} ${describeFormat(f)}</option>`;
      })
      .join("");
    pairAudioSelect.innerHTML = data.audio_only_formats
      .map((f, i) => `<option value="${i}">${describeFormat(f)}</option>`)
      .join("");
  } else {
    pairControls.classList.add("hidden");
  }

  if (data.default_stream) {
    loadUnifiedPlayback(data.default_stream, data.title, true);
  }
}

pairPlayBtn.addEventListener("click", () => {
  if (!currentData) return;
  const v = pairableVideoPool[Number(pairVideoSelect.value)];
  const a = currentData.audio_only_formats[Number(pairAudioSelect.value)];
  if (v && a) {
    loadUnifiedPlayback(
      {
        mode: "paired",
        video_url: v.url,
        audio_url: a.url,
        is_hls: v.is_hls,
        is_dash: v.is_dash,
        label: `${v.resolution || v.ext} + ${a.format_note || a.ext} (Synced)`,
      },
      currentData.title,
      true,
      { autoPlay: true }
    );
  }
});

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.add("hidden"));
    tab.classList.add("active");
    document.getElementById(`tab-${tab.dataset.tab}`).classList.remove("hidden");
  });
});

if (directPlayBtn) {
  directPlayBtn.addEventListener("click", () => {
    const vUrl = (directVideoInput.value || "").trim();
    const aUrl = (directAudioInput.value || "").trim();
    if (!vUrl) {
      setStatus("Please paste at least a Video URL.", true);
      return;
    }
    clearStatus();
    const media = buildExtensionPlayback(vUrl, aUrl, aUrl ? "Direct Video + Audio" : "Direct Stream");
    loadUnifiedPlayback(media.playback, media.title, true, { autoPlay: true });
  });
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const url = urlInput.value.trim();
  if (!url) return;

  extractBtn.disabled = true;
  extractBtn.textContent = "Loading...";
  setStatus("Extracting public media sources...");

  try {
    const res = await fetch("/api/extract", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    });
    const body = await res.json();
    if (!res.ok) {
      throw new Error(body.detail || "Extraction failed.");
    }
    clearStatus();
    renderResult(body);
  } catch (err) {
    setStatus(err.message || "Could not extract media.", true);
  } finally {
    extractBtn.disabled = false;
    extractBtn.textContent = "Load Stream";
  }
});
