"use strict";

const btnTabRoom = document.getElementById("btn-tab-room");
const btnTabBrowser = document.getElementById("btn-tab-browser");
const btnBack = document.getElementById("btn-back");
const btnForward = document.getElementById("btn-forward");
const btnReload = document.getElementById("btn-reload");
const btnShield = document.getElementById("btn-shield");
const urlForm = document.getElementById("url-form");
const urlInput = document.getElementById("url-input");
const btnStreams = document.getElementById("btn-streams");

const drawer = document.getElementById("streams-drawer");
const drawerTitle = document.getElementById("drawer-title");
const pairHint = document.getElementById("pair-hint");
const drawerContent = document.getElementById("drawer-content");
const btnExtractPage = document.getElementById("btn-extract-page");
const btnClearStreams = document.getElementById("btn-clear-streams");
const btnCloseDrawer = document.getElementById("btn-close-drawer");

let latestStreams = [];
let activeDrawerTab = "combined";
let preselectedVideoUrl = null;
let preselectedAudioUrl = null;

btnTabRoom.addEventListener("click", () => window.StreamSyncShell.switchTab("room"));
btnTabBrowser.addEventListener("click", () => window.StreamSyncShell.switchTab("browser"));
btnBack.addEventListener("click", () => window.StreamSyncShell.navAction("back"));
btnForward.addEventListener("click", () => window.StreamSyncShell.navAction("forward"));
btnReload.addEventListener("click", () => window.StreamSyncShell.navAction("reload"));
btnShield.addEventListener("click", () => window.StreamSyncShell.toggleShield());
btnStreams.addEventListener("click", () => window.StreamSyncShell.toggleDrawer());
btnCloseDrawer.addEventListener("click", () => window.StreamSyncShell.toggleDrawer(false));
btnExtractPage.addEventListener("click", () => window.StreamSyncShell.extractCurrentPage());
btnClearStreams.addEventListener("click", () => window.StreamSyncShell.clearStreams());

urlForm.addEventListener("submit", (e) => {
  e.preventDefault();
  window.StreamSyncShell.navigate(urlInput.value);
});

document.querySelectorAll(".dtab").forEach((btn) => {
  btn.addEventListener("click", () => {
    activeDrawerTab = btn.dataset.tab;
    renderDrawer();
  });
});

window.StreamSyncShell.onShellState((state) => {
  const isRoom = state.activeTab === "room";
  btnTabRoom.classList.toggle("active", isRoom);
  btnTabBrowser.classList.toggle("active", !isRoom);
  btnBack.disabled = !state.canGoBack;
  btnForward.disabled = !state.canGoForward;

  if (document.activeElement !== urlInput) {
    urlInput.value = state.url || "";
  }

  btnShield.classList.toggle("hidden", isRoom);
  if (state.shield) {
    btnShield.classList.toggle("off", !state.shield.enabled);
    btnShield.textContent = state.shield.enabled
      ? state.shield.blockedCount > 0
        ? `Shield (${state.shield.blockedCount})`
        : "Shield: ON"
      : "Shield: OFF";
  }

  drawer.classList.toggle("hidden", !state.drawerOpen);
  if (state.drawerOpen) {
    renderDrawer();
  }
});

window.StreamSyncShell.onStreamsUpdated((streams) => {
  latestStreams = Array.isArray(streams) ? streams : [];
  btnStreams.textContent = `Streams (${latestStreams.length})`;
  btnStreams.classList.toggle("has-items", latestStreams.length > 0);
  if (!drawer.classList.contains("hidden")) {
    renderDrawer();
  }
});

function shortUrl(url) {
  try {
    const u = new URL(url);
    return `${u.hostname}${u.pathname}`;
  } catch {
    return url;
  }
}

function renderDrawer() {
  const combined = latestStreams.filter((s) => s.kind === "COMBINED");
  const videoOnly = latestStreams.filter((s) => s.kind === "VIDEO_ONLY");
  const audioOnly = latestStreams.filter((s) => s.kind === "AUDIO_ONLY");
  const pairableVideos = [...videoOnly, ...combined];

  drawerTitle.textContent = `Detected Streams (${latestStreams.length})`;
  document.getElementById("cnt-combined").textContent = String(combined.length);
  document.getElementById("cnt-video").textContent = String(videoOnly.length);
  document.getElementById("cnt-audio").textContent = String(audioOnly.length);

  if (videoOnly.length > 0 && audioOnly.length > 0) {
    pairHint.textContent = `Separated Video (${videoOnly.length}) & Audio (${audioOnly.length}) detected — use "Pair V + A" to sync any Video + Audio language.`;
    pairHint.classList.remove("hidden");
  } else {
    pairHint.classList.add("hidden");
  }

  document.querySelectorAll(".dtab").forEach((b) => {
    b.classList.toggle("active", b.dataset.tab === activeDrawerTab);
  });

  drawerContent.innerHTML = "";

  if (activeDrawerTab === "pair") {
    renderPairTab(pairableVideos, audioOnly);
    return;
  }

  const list =
    activeDrawerTab === "combined"
      ? combined
      : activeDrawerTab === "video"
        ? videoOnly
        : audioOnly;

  if (!list.length) {
    const msg = document.createElement("div");
    msg.className = "empty-msg";
    msg.textContent =
      latestStreams.length === 0
        ? "No media streams detected yet. Open a video page in the Browser tab and press Play."
        : "No streams in this category. Check the other tabs or 'Pair V + A'.";
    drawerContent.appendChild(msg);
    return;
  }

  for (const s of list) {
    const card = document.createElement("div");
    card.className = "stream-card";

    const title = document.createElement("div");
    title.className = s.isDrmProtected ? "stream-title drm" : "stream-title";
    title.textContent = s.label;

    const meta = document.createElement("div");
    meta.className = "stream-meta";
    meta.textContent = s.subtitle || "";

    const urlDiv = document.createElement("div");
    urlDiv.className = "stream-url";
    urlDiv.title = s.url;
    urlDiv.textContent = shortUrl(s.url);

    const actions = document.createElement("div");
    actions.className = "stream-actions";

    if (s.isDrmProtected) {
      const drmSpan = document.createElement("span");
      drmSpan.className = "stream-title drm";
      drmSpan.style.fontSize = "11.5px";
      drmSpan.textContent = "DRM Encrypted (Widevine/PlayReady — not supported)";
      actions.appendChild(drmSpan);
    } else {
      if (s.kind === "COMBINED") {
        const playBtn = document.createElement("button");
        playBtn.className = "btn-sm btn-primary";
        playBtn.textContent = "Play in Room";
        playBtn.addEventListener("click", () => window.StreamSyncShell.playInRoom(s, null));
        actions.appendChild(playBtn);

        if (audioOnly.length > 0) {
          const pairAlt = document.createElement("button");
          pairAlt.className = "btn-sm";
          pairAlt.textContent = "Pair External Audio";
          pairAlt.addEventListener("click", () => {
            preselectedVideoUrl = s.normalizedUrl;
            activeDrawerTab = "pair";
            renderDrawer();
          });
          actions.appendChild(pairAlt);
        }
      } else if (s.kind === "VIDEO_ONLY") {
        if (audioOnly.length > 0) {
          const pairBtn = document.createElement("button");
          pairBtn.className = "btn-sm btn-primary";
          pairBtn.textContent = "Pair with Audio...";
          pairBtn.addEventListener("click", () => {
            preselectedVideoUrl = s.normalizedUrl;
            activeDrawerTab = "pair";
            renderDrawer();
          });
          actions.appendChild(pairBtn);
        }
        const mutedBtn = document.createElement("button");
        mutedBtn.className = "btn-sm btn-warn";
        mutedBtn.textContent = "Play Muted";
        mutedBtn.addEventListener("click", () => window.StreamSyncShell.playInRoom(s, null));
        actions.appendChild(mutedBtn);
      } else {
        if (pairableVideos.length > 0) {
          const pairBtn = document.createElement("button");
          pairBtn.className = "btn-sm btn-primary";
          pairBtn.textContent = "Pair with Video...";
          pairBtn.addEventListener("click", () => {
            preselectedAudioUrl = s.normalizedUrl;
            activeDrawerTab = "pair";
            renderDrawer();
          });
          actions.appendChild(pairBtn);
        }
        const playAudio = document.createElement("button");
        playAudio.className = "btn-sm";
        playAudio.textContent = "Play Audio";
        playAudio.addEventListener("click", () => window.StreamSyncShell.playInRoom(s, null));
        actions.appendChild(playAudio);
      }

      const copyBtn = document.createElement("button");
      copyBtn.className = "btn-sm";
      copyBtn.textContent = "Copy URL";
      copyBtn.addEventListener("click", () => {
        window.StreamSyncShell.copyText(s.url);
        copyBtn.textContent = "Copied!";
        setTimeout(() => (copyBtn.textContent = "Copy URL"), 1200);
      });
      actions.appendChild(copyBtn);
    }

    card.append(title, meta, urlDiv, actions);
    drawerContent.appendChild(card);
  }
}

function renderPairTab(videos, audios) {
  const playableV = videos.filter((s) => !s.isDrmProtected);
  const playableA = audios.filter((s) => !s.isDrmProtected);

  if (!playableV.length || !playableA.length) {
    const empty = document.createElement("div");
    empty.className = "empty-msg";
    empty.textContent =
      "Manual pairing requires at least 1 Video stream and 1 Audio-Only track. Play the video in the Browser tab so both tracks are captured.";
    drawerContent.appendChild(empty);
    return;
  }

  const box = document.createElement("div");
  box.className = "pair-box";

  const vLabel = document.createElement("label");
  vLabel.textContent = `1. Video Stream (${playableV.length})`;
  const vSelect = document.createElement("select");
  playableV.forEach((v, idx) => {
    const opt = document.createElement("option");
    opt.value = String(idx);
    opt.textContent = `[${v.kind === "VIDEO_ONLY" ? "Video-Only" : "Muxed"}] ${v.label} — ${v.subtitle || ""}`;
    if (preselectedVideoUrl && v.normalizedUrl === preselectedVideoUrl) opt.selected = true;
    vSelect.appendChild(opt);
  });
  vLabel.appendChild(vSelect);

  const aLabel = document.createElement("label");
  aLabel.textContent = `2. Audio Track (${playableA.length})`;
  const aSelect = document.createElement("select");
  playableA.forEach((a, idx) => {
    const opt = document.createElement("option");
    opt.value = String(idx);
    opt.textContent = `${a.label} — ${a.subtitle || ""}`;
    if (preselectedAudioUrl && a.normalizedUrl === preselectedAudioUrl) opt.selected = true;
    aSelect.appendChild(opt);
  });
  aLabel.appendChild(aSelect);

  const playBtn = document.createElement("button");
  playBtn.className = "btn-primary";
  playBtn.textContent = "Play Selected Video + Audio in Sync";
  playBtn.addEventListener("click", () => {
    const v = playableV[Number(vSelect.value)];
    const a = playableA[Number(aSelect.value)];
    if (v && a) {
      window.StreamSyncShell.playInRoom(v, a);
    }
  });

  box.append(vLabel, aLabel, playBtn);
  drawerContent.appendChild(box);
}
