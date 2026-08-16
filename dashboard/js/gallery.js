import { api } from "./api.js";
import { state } from "./state.js";

function authenticatedMediaUrl(path) {
  const mediaUrl = new URL(path, `${state.settings.serverUrl.replace(/\/$/, "")}/`);
  if (state.settings.token) {
    mediaUrl.searchParams.set("token", state.settings.token);
  }
  return mediaUrl.toString();
}

export async function renderGallery(params, append = false) {
  const data = await api.photos(params);
  const gallery = document.getElementById("gallery");
  if (!append) gallery.innerHTML = "";
  document.getElementById("photoCount").textContent = data.total ? `${data.total} photos` : "No photos yet";
  if (!data.items.length && !append) {
    gallery.innerHTML = `<div class="muted">No photos yet. Waiting for phone...</div>`;
    return data;
  }
  for (const item of data.items) {
    const url = authenticatedMediaUrl(item.url);
    const card = document.createElement("article");
    card.className = "photo-card";
    card.innerHTML = `
      <a href="${url}" target="_blank" rel="noreferrer"><img loading="lazy" src="${url}" alt="${item.camera} recovery photo"></a>
      <div class="meta">${formatTime(item.timestamp)} · ${item.camera}</div>
      <div class="button-row">
        <a class="button" href="${url}" target="_blank" rel="noreferrer">Open</a>
        <a class="button" href="${url}" download>Download</a>
      </div>
    `;
    gallery.appendChild(card);
  }
  return data;
}

function formatTime(timestamp) {
  return new Date(timestamp).toLocaleString();
}
