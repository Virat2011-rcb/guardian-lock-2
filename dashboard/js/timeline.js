import { api } from "./api.js";

export async function renderTimeline(params) {
  const data = await api.timeline(params);
  const timeline = document.getElementById("timeline");
  document.getElementById("timelineCount").textContent = data.total ? `${data.total} events` : "No events yet";
  timeline.innerHTML = "";
  if (!data.items.length) {
    timeline.innerHTML = `<div class="muted">No recovery events yet.</div>`;
    return data;
  }
  for (const item of data.items) {
    const row = document.createElement("div");
    row.className = "timeline-row";
    row.innerHTML = `
      <time>${formatTime(item.timestamp)}</time>
      <div>
        <strong>${labelFor(item)}</strong>
        <p>${descriptionFor(item)}</p>
      </div>
    `;
    timeline.appendChild(row);
  }
  return data;
}

function labelFor(item) {
  if (item.type === "photo") return `${titleCase(item.camera || "camera")} Camera`;
  if (item.type === "audio") return "Audio Recorded";
  if (item.type === "status") return item.event || "Status Updated";
  return "Recovery Event";
}

function descriptionFor(item) {
  if (item.type === "photo") return "Recovery photo uploaded.";
  if (item.type === "audio") return "Recovery audio uploaded.";
  if (item.type === "status") {
    const battery = item.batteryPercent ?? item.data?.batteryPercent ?? item.data?.battery ?? null;
    const network = item.network ?? item.data?.network ?? item.data?.mobileNetwork ?? item.data?.wifi ?? "";
    const parts = [];
    if (battery !== null && battery !== undefined) parts.push(`Battery ${battery}%`);
    if (network) parts.push(String(network));
    return parts.join(" · ") || "Device status captured locally.";
  }
  return "";
}

function formatTime(timestamp) {
  return new Date(timestamp).toLocaleString();
}

function titleCase(value) {
  return String(value).charAt(0).toUpperCase() + String(value).slice(1);
}
