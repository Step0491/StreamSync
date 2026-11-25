// StreamSync Frontend: Unified Dual-Track Player + Synchronized Watch Rooms

const form = document.getElementById("extract-form");
const urlInput = document.getElementById("url-input");
const extractBtn = document.getElementById("extract-btn");
const statusBox = document.getElementById("status-box");

const playerSection = document.getElementById("player-section");
const tracksSection = document.getElementById("tracks-section");
const mediaTitle = document.getElementById("media-title");
const mediaExtractor = document.getElementById("media-extractor");
const mediaDuration = document.getElementById("media-duration");
const mediaLive = document.getElementById("media-live");
const nowPlayingEl = document.getElementById("now-playing");
const syncIndicator = document.getElementById("sync-indicator");
const roomSyncLog = document.getElementById("room-sync-log");

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
const chatMessages = document.getElementById("chat-messages");
const chatForm = document.getElementById("chat-form");
const chatInput = document.getElementById("chat-input");

let hlsInstance = null;
let dashInstance = null;
let dualSyncActive = false;
let currentData = null;

// Room WebSocket state
let ws = null;
let currentRoomId = null;
let suppressRoomSync = false;

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(() => {});
}

// Restore saved nickname
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
  if (!seconds || Number.isNaN(seconds)) return "";
  const total = Math.floor(seconds);
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

// ---------- Unified Player (Single, HLS, DASH, and Paired Video+Audio) ----------

function resetPlayers() {
  dualSyncActive = false;
  syncIndicator.classList.add("hidden");
  manifestAudioBar.classList.add("hidden");
  manifestAudioSelect.innerHTML = "";
  if (hlsInstance) {
    hlsInstance.destroy();
    hlsInstance = null;
  }
  if (dashInstance) {
    dashInstance.reset();
    dashInstance = null;
  }
  videoEl.pause();
  audioEl.pause();
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

function attachVideoSource(url, isHls, isDash) {
  if (isDash && window.dashjs) {
    dashInstance = dashjs.MediaPlayer().create();
    dashInstance.initialize(videoEl, url, false);
    dashInstance.on(dashjs.MediaPlayer.events.STREAM_INITIALIZED, () => {
      const audioTracks = dashInstance.getTracksFor("audio") || [];
      const current = dashInstance.getCurrentTrackFor("audio");
      const activeIdx = Math.max(0, audioTracks.indexOf(current));
      populateManifestAudioTracks(
        audioTracks.map((t, i) => ({
          label: `${t.lang || "Audio " + (i + 1)}${t.roles && t.roles.length ? " (" + t.roles.join(",") + ")" : ""}`,
        })),
        activeIdx,
        (idx) => {
          if (audioTracks[idx]) dashInstance.setCurrentTrack(audioTracks[idx]);
        }
      );
    });
  } else if (isHls && window.Hls && Hls.isSupported()) {
    hlsInstance = new Hls();
    hlsInstance.loadSource(url);
    hlsInstance.attachMedia(videoEl);
    hlsInstance.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => {
      const tracks = hlsInstance.audioTracks || [];
      populateManifestAudioTracks(
        tracks.map((t, i) => ({ label: t.name || t.lang || `Audio ${i + 1}` })),
        hlsInstance.audioTrack,
        (idx) => {
          hlsInstance.audioTrack = idx;
        }
      );
    });
  } else {
    videoEl.src = url;
  }
}

function loadUnifiedPlayback(playback, title = null, broadcast = false) {
  if (!playback || !playback.video_url) return;
  resetPlayers();
  playerSection.classList.remove("hidden");
  if (title) mediaTitle.textContent = title;

  if (playback.mode === "paired" && playback.audio_url) {
    dualSyncActive = true;
    syncIndicator.classList.remove("hidden");
    nowPlayingEl.textContent = playback.label || "Unified Video + Audio";
    attachVideoSource(playback.video_url, playback.is_hls, playback.is_dash);
    audioEl.src = playback.audio_url;
    audioEl.muted = videoEl.muted;
    audioEl.volume = videoEl.volume;
  } else {
    nowPlayingEl.textContent = playback.label || "Playing stream";
    attachVideoSource(playback.video_url, playback.is_hls, playback.is_dash);
  }

  if (broadcast && ws && ws.readyState === WebSocket.OPEN) {
    ws.send(
      JSON.stringify({
        type: "load_media",
        media: {
          title: title || mediaTitle.textContent || "Untitled Stream",
          source_url: currentData ? currentData.source_url : "",
          playback,
        },
      })
    );
  }
}

// Tight dual-track audio/video synchronization so separated streams behave like a single video
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
  emitRoomSync("pause");
});

videoEl.addEventListener("seeking", () => {
  if (dualSyncActive) audioEl.currentTime = videoEl.currentTime;
});

videoEl.addEventListener("seeked", () => {
  if (dualSyncActive) audioEl.currentTime = videoEl.currentTime;
  emitRoomSync("seek");
});

videoEl.addEventListener("waiting", () => {
  if (dualSyncActive) audioEl.pause();
});

videoEl.addEventListener("playing", () => {
  if (dualSyncActive && !videoEl.paused) {
    audioEl.currentTime = videoEl.currentTime;
    audioEl.play().catch(() => {});
  }
});

videoEl.addEventListener("ratechange", () => {
  if (dualSyncActive) audioEl.playbackRate = videoEl.playbackRate;
  emitRoomSync("ratechange");
});

videoEl.addEventListener("volumechange", () => {
  if (dualSyncActive) {
    audioEl.muted = videoEl.muted;
    audioEl.volume = videoEl.volume;
  }
});

videoEl.addEventListener("timeupdate", () => {
  if (!dualSyncActive || videoEl.paused) return;
  const drift = Math.abs(videoEl.currentTime - audioEl.currentTime);
  if (drift > 0.15) {
    audioEl.currentTime = videoEl.currentTime;
  }
});

// ---------- Room Synchronization (Step 2) ----------

function emitRoomSync(action) {
  if (suppressRoomSync || !ws || ws.readyState !== WebSocket.OPEN) return;
  ws.send(
    JSON.stringify({
      type: "sync",
      action,
      is_playing: !videoEl.paused,
      position: videoEl.currentTime || 0,
      playback_rate: videoEl.playbackRate || 1,
    })
  );
}

function applyRemoteSync(isPlaying, position, playbackRate, logText = "") {
  suppressRoomSync = true;
  try {
    if (playbackRate && Math.abs(videoEl.playbackRate - playbackRate) > 0.01) {
      videoEl.playbackRate = playbackRate;
      if (dualSyncActive) audioEl.playbackRate = playbackRate;
    }
    if (typeof position === "number" && Math.abs((videoEl.currentTime || 0) - position) > 0.35) {
      videoEl.currentTime = position;
      if (dualSyncActive) audioEl.currentTime = position;
    }
    if (isPlaying && videoEl.paused) {
      videoEl.play().catch(() => {});
    } else if (!isPlaying && !videoEl.paused) {
      videoEl.pause();
    }
    if (logText) {
      roomSyncLog.textContent = logText;
      roomSyncLog.classList.remove("hidden");
    }
  } finally {
    setTimeout(() => {
      suppressRoomSync = false;
    }, 180);
  }
}

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
    pill.textContent = m.is_host ? `${m.nickname} (Host)` : m.nickname;
    memberList.appendChild(pill);
  });
}

function setRoomUI(roomId) {
  const inRoom = Boolean(roomId);
  currentRoomId = roomId;
  roomSidebar.classList.toggle("hidden", !inRoom);
  inviteRoomBtn.classList.toggle("hidden", !inRoom);
  leaveRoomBtn.classList.toggle("hidden", !inRoom);
  roomStatusBadge.textContent = inRoom ? `Room ${roomId}` : "Solo Mode";
  roomStatusBadge.classList.toggle("badge-muted", !inRoom);
  sidebarRoomCode.textContent = roomId || "";

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
  if (ws) ws.close();

  const nick = encodeURIComponent(nicknameInput.value.trim() || "Guest");
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  ws = new WebSocket(`${proto}://${window.location.host}/ws/rooms/${code}?nickname=${nick}`);

  ws.onopen = () => {
    setRoomUI(code);
    chatMessages.innerHTML = "";
    appendChat("", `Connected to room ${code}.`, true);
  };

  ws.onmessage = (event) => {
    const msg = JSON.parse(event.data);

    if (msg.type === "room_state") {
      renderMembers(msg.members || []);
      (msg.chat_history || []).forEach((c) => appendChat(c.nickname, c.text));
      if (msg.media && msg.media.playback) {
        loadUnifiedPlayback(msg.media.playback, msg.media.title, false);
        setTimeout(() => applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, "Synced with room"), 350);
      }
      return;
    }

    if (msg.type === "members_updated") {
      renderMembers(msg.members || []);
      if (msg.system_message) appendChat("", msg.system_message, true);
      return;
    }

    if (msg.type === "media_changed") {
      if (msg.media && msg.media.playback) {
        loadUnifiedPlayback(msg.media.playback, msg.media.title, false);
        applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, `${msg.by} loaded: ${msg.media.title}`);
        appendChat("", `${msg.by} loaded "${msg.media.title}"`, true);
      }
      return;
    }

    if (msg.type === "sync") {
      applyRemoteSync(msg.is_playing, msg.position, msg.playback_rate, `${msg.by}: ${msg.action} @ ${formatDuration(msg.position)}`);
      return;
    }

    if (msg.type === "chat" && msg.message) {
      appendChat(msg.message.nickname, msg.message.text);
    }
  };

  ws.onclose = () => {
    if (currentRoomId === code) {
      setRoomUI(null);
      roomSyncLog.classList.add("hidden");
    }
  };
}

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
  if (ws) ws.close();
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

// Auto-join room and/or auto-load stream passed by the StreamSync Browser Extension (?video=...&audio=...&title=...&room=...)
const startupParams = new URLSearchParams(window.location.search);
const initialRoom = startupParams.get("room");
const initialVideo = startupParams.get("video");
const initialAudio = startupParams.get("audio");
const initialTitle = startupParams.get("title") || "Detected Stream";

let pendingExtensionMedia = null;
if (initialVideo) {
  const lowerVideo = initialVideo.toLowerCase();
  const isHls = lowerVideo.includes(".m3u8");
  const isDash = lowerVideo.includes(".mpd");
  pendingExtensionMedia = {
    title: initialTitle,
    playback: {
      mode: initialAudio ? "paired" : "single",
      video_url: initialVideo,
      audio_url: initialAudio || null,
      is_hls: isHls,
      is_dash: isDash,
      label: initialAudio
        ? "Unified Video + Audio (from Extension)"
        : isHls
          ? "HLS Stream (from Extension)"
          : isDash
            ? "DASH Stream (from Extension)"
            : "Video Stream (from Extension)",
    },
  };
}

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

// ---------- Track Inspection & Extraction UI ----------

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

function renderFormatList(containerId, formats, emptyText) {
  const container = document.getElementById(containerId);
  container.innerHTML = "";
  if (!formats.length) {
    container.innerHTML = `<p class="subtitle">${emptyText}</p>`;
    return;
  }

  const list = document.createElement("div");
  list.className = "format-list";

  formats.forEach((fmt) => {
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

    const playBtn = document.createElement("button");
    playBtn.type = "button";
    playBtn.className = "btn-sm";
    playBtn.textContent = "Play";
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
        true
      );
      videoEl.play().catch(() => {});
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
    info.innerHTML = `<div class="format-title">${sub.lang.toUpperCase()} (${sub.ext})</div><div class="format-sub">${sub.name || ""}</div>`;
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

  renderFormatList("tab-combined", data.combined_formats, "No combined video+audio streams found.");
  renderFormatList("tab-video", data.video_only_formats, "No video-only streams found.");
  renderFormatList("tab-audio", data.audio_only_formats, "No audio-only streams found.");
  renderSubtitles(data.subtitles);

  if (data.video_only_formats.length && data.audio_only_formats.length) {
    pairControls.classList.remove("hidden");
    pairVideoSelect.innerHTML = data.video_only_formats
      .map((f, i) => `<option value="${i}">${describeFormat(f)}</option>`)
      .join("");
    pairAudioSelect.innerHTML = data.audio_only_formats
      .map((f, i) => `<option value="${i}">${describeFormat(f)}</option>`)
      .join("");
  } else {
    pairControls.classList.add("hidden");
  }

  // Seamlessly auto-load default_stream (single, HLS, DASH, or auto-paired video+audio)
  if (data.default_stream) {
    loadUnifiedPlayback(data.default_stream, data.title, true);
  }
}

pairPlayBtn.addEventListener("click", () => {
  if (!currentData) return;
  const v = currentData.video_only_formats[Number(pairVideoSelect.value)];
  const a = currentData.audio_only_formats[Number(pairAudioSelect.value)];
  if (v && a) {
    loadUnifiedPlayback(
      {
        mode: "paired",
        video_url: v.url,
        audio_url: a.url,
        is_hls: v.is_hls,
        is_dash: v.is_dash,
        label: `${v.resolution || v.ext} (Unified Video + Audio)`,
      },
      currentData.title,
      true
    );
    videoEl.play().catch(() => {});
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
