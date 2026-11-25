const api = typeof browser !== "undefined" ? browser : chrome;

const serverUrlInput = document.getElementById("server-url");
const roomCodeInput = document.getElementById("room-code");
const clearBtn = document.getElementById("clear-btn");
const countEl = document.getElementById("count");
const streamList = document.getElementById("stream-list");

const pairBox = document.getElementById("pair-box");
const videoSelect = document.getElementById("video-select");
const audioSelect = document.getElementById("audio-select");
const sendPairedBtn = document.getElementById("send-paired-btn");

let activeTab = null;
let detectedItems = [];

function getServerBase() {
  const raw = (serverUrlInput.value || "http://localhost:8000").trim().replace(/\/+$/, "");
  return raw || "http://localhost:8000";
}

function openInStreamSync({ videoUrl, audioUrl = "", title = "" }) {
  const params = new URLSearchParams();
  params.set("video", videoUrl);
  if (audioUrl) params.set("audio", audioUrl);
  if (title) params.set("title", title);
  const room = roomCodeInput.value.trim().toUpperCase();
  if (room) params.set("room", room);

  const target = `${getServerBase()}/?${params.toString()}`;
  api.tabs.create({ url: target });
}

function shortUrl(url) {
  try {
    const u = new URL(url);
    return `${u.hostname}${u.pathname}`;
  } catch {
    return url;
  }
}

function render(items) {
  detectedItems = items;
  countEl.textContent = String(items.length);
  streamList.innerHTML = "";

  const videos = items.filter((i) => i.kind === "video" || i.kind === "combined");
  const audios = items.filter((i) => i.kind === "audio");

  if (videos.length && audios.length) {
    pairBox.classList.remove("hidden");
    videoSelect.innerHTML = videos
      .map((v, idx) => `<option value="${idx}">${v.label} — ${shortUrl(v.url)}</option>`)
      .join("");
    audioSelect.innerHTML = audios
      .map((a, idx) => `<option value="${idx}">${a.label} — ${shortUrl(a.url)}</option>`)
      .join("");
  } else {
    pairBox.classList.add("hidden");
  }

  if (!items.length) {
    streamList.innerHTML = `<div class="empty">No media streams detected on this tab yet. Start playing the video on the page.</div>`;
    return;
  }

  items.forEach((item) => {
    const card = document.createElement("div");
    card.className = "item";

    const top = document.createElement("div");
    top.className = "item-top";
    top.innerHTML = `<strong>${item.label}</strong><span class="tag">${item.kind}</span>`;

    const urlDiv = document.createElement("div");
    urlDiv.className = "url";
    urlDiv.title = item.url;
    urlDiv.textContent = item.url;

    const actions = document.createElement("div");
    actions.className = "actions";

    const openBtn = document.createElement("button");
    openBtn.type = "button";
    openBtn.className = "btn-primary";
    openBtn.textContent = "Open in StreamSync";
    openBtn.addEventListener("click", () => {
      openInStreamSync({
        videoUrl: item.url,
        title: activeTab ? activeTab.title : item.label,
      });
    });

    const copyBtn = document.createElement("button");
    copyBtn.type = "button";
    copyBtn.className = "btn-secondary";
    copyBtn.textContent = "Copy";
    copyBtn.addEventListener("click", async () => {
      await navigator.clipboard.writeText(item.url);
      copyBtn.textContent = "Copied!";
      setTimeout(() => (copyBtn.textContent = "Copy"), 1200);
    });

    actions.append(openBtn, copyBtn);
    card.append(top, urlDiv, actions);
    streamList.appendChild(card);
  });
}

sendPairedBtn.addEventListener("click", () => {
  const videos = detectedItems.filter((i) => i.kind === "video" || i.kind === "combined");
  const audios = detectedItems.filter((i) => i.kind === "audio");
  const v = videos[Number(videoSelect.value)];
  const a = audios[Number(audioSelect.value)];
  if (!v || !a) return;
  openInStreamSync({
    videoUrl: v.url,
    audioUrl: a.url,
    title: activeTab ? activeTab.title : "Paired Stream",
  });
});

serverUrlInput.addEventListener("change", () => {
  api.storage.local.set({ streamsync_server: serverUrlInput.value.trim() });
});

roomCodeInput.addEventListener("change", () => {
  api.storage.local.set({ streamsync_room: roomCodeInput.value.trim().toUpperCase() });
});

clearBtn.addEventListener("click", () => {
  if (!activeTab) return;
  api.runtime.sendMessage({ type: "CLEAR_TAB_MEDIA", tabId: activeTab.id }, () => render([]));
});

async function init() {
  api.storage.local.get(["streamsync_server", "streamsync_room"], (stored) => {
    serverUrlInput.value = stored.streamsync_server || "http://localhost:8000";
    roomCodeInput.value = stored.streamsync_room || "";
  });

  api.tabs.query({ active: true, currentWindow: true }, (tabs) => {
    activeTab = tabs && tabs[0];
    if (!activeTab) return;
    api.runtime.sendMessage({ type: "GET_TAB_MEDIA", tabId: activeTab.id }, (resp) => {
      render((resp && resp.items) || []);
    });
  });
}

init();
