import { api } from "./api.js";

export async function renderAudio(params, append = false) {
  const data = await api.audio(params);
  const list = document.getElementById("audioList");
  if (!append) list.innerHTML = "";
  document.getElementById("audioCount").textContent = data.total ? `${data.total} recordings` : "No audio yet";
  if (!data.items.length && !append) {
    list.innerHTML = `<div class="muted">No audio recordings yet. Waiting for phone...</div>`;
    return data;
  }
  for (const item of data.items) {
    const url = api.fileUrl(item.url);
    const card = document.createElement("article");
    card.className = "audio-card";
    card.innerHTML = `
      <div>
        <strong>Audio Recorded</strong>
        <div class="meta">${formatTime(item.timestamp)} · <span class="duration">Duration loading...</span></div>
      </div>
      <audio controls preload="metadata" src="${url}"></audio>
      <a class="button" href="${url}" download>Download</a>
    `;
    const audio = card.querySelector("audio");
    const duration = card.querySelector(".duration");
    audio.addEventListener("loadedmetadata", () => {
      duration.textContent = Number.isFinite(audio.duration) ? formatDuration(audio.duration) : "Duration unavailable";
    });
    audio.addEventListener("error", () => {
      duration.textContent = "Cannot read duration";
    });
    list.appendChild(card);
  }
  return data;
}

function formatTime(timestamp) {
  return new Date(timestamp).toLocaleString();
}

function formatDuration(seconds) {
  const total = Math.round(seconds);
  const mins = Math.floor(total / 60).toString().padStart(2, "0");
  const secs = (total % 60).toString().padStart(2, "0");
  return `${mins}:${secs}`;
}
