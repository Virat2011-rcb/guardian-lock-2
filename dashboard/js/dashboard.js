import { FriendlyError, api, generateLaptopKey, getPublicKey, signedCommand } from "./api.js";
import { renderAudio } from "./audio.js";
import { renderGallery } from "./gallery.js";
import { copyCoordinates, renderLiveLocation, renderLocation, renderLocationHistory } from "./maps.js";
import { notifyIfNeeded, requestNotificationPermission } from "./notifications.js";
import { saveSettings, state } from "./state.js";
import { renderTimeline } from "./timeline.js";

const pageSize = 24;
let photoOffset = 0;
let audioOffset = 0;
let refreshHandle = null;
let liveLocationHandle = null;
let liveSocket = null;
let liveSocketRetry = null;
let hasSnapshot = false;
let latestSnapshot = { photoCount: 0, audioCount: 0, offline: false, batteryCritical: false, lostMode: false };

const els = {};

document.addEventListener("DOMContentLoaded", () => {
  bindElements();
  loadSettingsIntoUi();
  applyTheme();
  bindEvents();
  resetAndRefresh();
  startAutoRefresh();
  startLiveTransport();
});

function bindElements() {
  for (const id of [
    "connectionBadge", "settingsButton", "batteryValue", "chargingValue", "networkValue", "syncValue",
    "lostModeValue", "ownerValue", "studyModeValue", "maintenanceValue", "commandStatus", "lostMessage",
    "typeFilter", "dateFilter", "sortFilter", "refreshNow", "photoPanel", "audioPanel", "loadMorePhotos",
    "loadMoreAudio", "totalPhotos", "totalAudio", "storageUsed", "recoveryEvents", "debugPanel",
    "debugOutput", "settingsDialog", "serverUrl", "phoneUrl", "tokenInput", "refreshInterval", "darkMode",
    "notificationsEnabled", "debugMode", "generateKey", "saveSettings", "publicKey", "toast",
    "locateNow", "startLiveLocation", "stopLiveLocation", "liveLocationState"
  ]) {
    els[id] = document.getElementById(id);
  }
}

function bindEvents() {
  els.settingsButton.addEventListener("click", () => {
    if (typeof els.settingsDialog.showModal === "function") {
      els.settingsDialog.showModal();
    } else {
      els.settingsDialog.setAttribute("open", "open");
    }
  });
  els.refreshNow.addEventListener("click", () => resetAndRefresh());
  els.loadMorePhotos.addEventListener("click", () => loadMorePhotos());
  els.loadMoreAudio.addEventListener("click", () => loadMoreAudio());
  els.typeFilter.addEventListener("change", () => resetAndRefresh());
  els.dateFilter.addEventListener("change", () => resetAndRefresh());
  els.sortFilter.addEventListener("change", () => resetAndRefresh());
  els.saveSettings.addEventListener("click", saveSettingsFromUi);
  document.getElementById("copyCoordinates").addEventListener("click", () => {
    toast(copyCoordinates() ? "Coordinates copied." : "No coordinates available yet.");
  });
  els.locateNow.addEventListener("click", () => locateNow());
  els.startLiveLocation.addEventListener("click", () => startLiveLocation());
  els.stopLiveLocation.addEventListener("click", () => stopLiveLocation());
  els.generateKey.addEventListener("click", async () => {
    try {
      els.publicKey.value = await generateLaptopKey();
      toast("Laptop key generated. Paste this public key into Guardian Lock on the phone.");
    } catch (error) {
      friendlyError(error, "Laptop key generation failed.");
    }
  });
  els.notificationsEnabled.addEventListener("change", () => {
    if (els.notificationsEnabled.checked) requestNotificationPermission();
  });
  document.querySelectorAll("[data-command]").forEach((button) => {
    button.addEventListener("click", () => runCommand(button.dataset.command));
  });
}

async function resetAndRefresh() {
  photoOffset = 0;
  audioOffset = 0;
  await refreshAll(true);
}

async function refreshAll(resetMedia = false) {
  const debug = {};
  try {
    const [health, latestStatus, latestLocation, stats, live, phone] = await Promise.allSettled([
      api.health(),
      api.latestStatus(),
      api.latestLocation(),
      api.stats(),
      api.liveLocation(),
      fetchPhoneStatus()
    ]);

    const healthData = valueOrNull(health);
    const statusData = valueOrNull(latestStatus);
    const locationData = valueOrNull(latestLocation);
    const statsData = valueOrNull(stats);
    const liveData = valueOrNull(live);
    const phoneData = valueOrNull(phone);

    debug.health = healthData;
    debug.status = statusData;
    debug.location = locationData;
    debug.stats = statsData;
    debug.live = liveData;
    debug.phone = phoneData;

    renderConnection(healthData, phoneData);
    renderStatus(statusData?.status?.data, statusData?.status?.timestamp, phoneData);
    renderLocation(locationData?.location);
    renderLiveLocation(liveData);
    await refreshLocationHistory();
    renderStats(statsData?.stats || statsData);
    renderDebug(debug);
    await refreshMedia(resetMedia);
    await renderTimeline(queryParams());

    const nextSnapshot = {
      photoCount: statsData?.stats?.totalPhotos ?? statsData?.photos ?? 0,
      audioCount: statsData?.stats?.totalAudio ?? statsData?.audio ?? 0,
      offline: !healthData?.ok,
      batteryCritical: isBatteryCritical(statusData?.status?.data),
      lostMode: Boolean(phoneData?.lostMode || statusData?.status?.data?.lostMode)
    };
    if (hasSnapshot) notifyChanges(nextSnapshot);
    hasSnapshot = true;
    latestSnapshot = nextSnapshot;
  } catch (error) {
    friendlyError(error, "Waiting for phone...");
  }
}

async function refreshMedia(resetMedia) {
  const type = els.typeFilter.value;
  els.photoPanel.classList.toggle("hidden", type === "audio" || type === "status");
  els.audioPanel.classList.toggle("hidden", type === "photos" || type === "status");

  if (type !== "audio" && type !== "status") {
    await renderGallery({ ...queryParams(), limit: pageSize, offset: photoOffset }, !resetMedia && photoOffset > 0);
  }
  if (type !== "photos" && type !== "status") {
    await renderAudio({ ...queryParams(), limit: pageSize, offset: audioOffset }, !resetMedia && audioOffset > 0);
  }
}

async function loadMorePhotos() {
  photoOffset += pageSize;
  await renderGallery({ ...queryParams(), limit: pageSize, offset: photoOffset }, true);
}

async function loadMoreAudio() {
  audioOffset += pageSize;
  await renderAudio({ ...queryParams(), limit: pageSize, offset: audioOffset }, true);
}

function queryParams() {
  const params = {
    sort: els.sortFilter.value
  };

  const date = els.dateFilter.value;

  if (date) {
    params.date = date;
  }

  return params;
}

function renderConnection(health, phone) {
  const ok = Boolean(health?.ok);
  els.connectionBadge.textContent = ok ? "Recovery server online" : "Recovery server offline";
  els.connectionBadge.className = `badge ${ok ? "good" : "bad"}`;
  if (phone) els.commandStatus.textContent = "Phone dashboard connected";
}

function renderStatus(status = {}, timestamp, phone = {}) {
  const battery = status.batteryPercent ?? status.battery ?? phone?.batteryPercent ?? "--";
  const charging = status.charging ?? phone?.charging;
  const network = status.network ?? status.mobileNetwork ?? status.wifi ?? phone?.network ?? "--";
  els.batteryValue.textContent = battery === "--" ? "--" : `${battery}%`;
  els.chargingValue.textContent = charging === undefined ? "Charging unknown" : (charging ? "Charging" : "Not charging");
  els.networkValue.textContent = network || "--";
  els.syncValue.textContent = timestamp ? `Last sync ${new Date(timestamp).toLocaleString()}` : "No sync yet";
  els.lostModeValue.textContent = formatOnOff(phone?.lostMode ?? status.lostMode);
  els.ownerValue.textContent = phone?.deviceOwner === undefined ? "Device Owner unknown" : `Device Owner ${formatOnOff(phone.deviceOwner)}`;
  els.studyModeValue.textContent = phone?.studyMode ?? status.studyMode ?? "Ready";
  els.maintenanceValue.textContent = phone?.maintenanceMode === undefined
    ? "Maintenance unknown"
    : `Maintenance ${formatOnOff(phone.maintenanceMode)} · CCTV ${formatOnOff(phone?.cctvMonitor ?? status.cctvMonitor)}`;
}

function renderStats(stats = {}) {
  stats = stats && typeof stats === "object" ? stats : {};
  els.totalPhotos.textContent = stats.totalPhotos ?? stats.photos ?? 0;
  els.totalAudio.textContent = stats.totalAudio ?? stats.audio ?? 0;
  els.storageUsed.textContent = stats.storageUsedHuman ?? formatBytes(stats.storageUsedBytes ?? 0);
  els.recoveryEvents.textContent = stats.recoveryEvents ?? stats.timelineEvents ?? 0;
  renderBatteryHistory(stats.batteryHistory || []);
}

function renderDebug(payload) {
  const enabled = Boolean(state.settings.debugMode);
  els.debugPanel.classList.toggle("hidden", !enabled);
  if (enabled) els.debugOutput.textContent = JSON.stringify(payload, null, 2);
}

async function runCommand(command) {
  try {
    const payload = command === "lost_mode_on" ? els.lostMessage.value : "";
    els.commandStatus.textContent = "Sending signed command...";
    const result = await signedCommand(command, payload);
    if (!result.ok) throw new Error(result.error || "Command rejected");
    els.commandStatus.textContent = result.queued ? "Command queued for phone" : "Command accepted";
    toast(result.queued ? "Command queued. Phone will apply it when it checks the server." : "Command accepted by phone.");
    setTimeout(() => refreshAll(false), 700);
  } catch (error) {
    friendlyError(error, "Signed command rejected. Re-pair laptop key on the phone, then try again.");
  }
}

async function locateNow() {
  await runCommand("locate_now");
  await refreshLocationHistory();
  setTimeout(() => refreshAll(false), 1500);
}

function startLiveLocation() {
  stopLiveLocation(false);
  els.liveLocationState.textContent = "Starting…";
  runCommand("live_tracking_start");
}

function stopLiveLocation(sendCommand = true) {
  if (liveLocationHandle) clearInterval(liveLocationHandle);
  liveLocationHandle = null;
  if (els.liveLocationState) els.liveLocationState.textContent = "Idle";
  if (sendCommand) runCommand("live_tracking_stop");
}

async function refreshLocationHistory() {
  try {
    const data = await api.locationHistory({ limit: 20, offset: 0, sort: "newest" });
    renderLocationHistory(data.items || []);
  } catch {
    renderLocationHistory([]);
  }
}

async function fetchPhoneStatus() {
  if (!state.settings.phoneUrl) return null;
  const response = await fetch(`${state.settings.phoneUrl.replace(/\/$/, "")}/status`, { cache: "no-store" });
  if (!response.ok) return null;
  return response.json();
}

function saveSettingsFromUi() {
  state.settings.serverUrl = els.serverUrl.value.trim() || "http://localhost:8787";
  state.settings.phoneUrl = els.phoneUrl.value.trim();
  state.settings.token = els.tokenInput.value;
  state.settings.refreshInterval = Math.max(1000, Number(els.refreshInterval.value || 3000));
  state.settings.darkMode = els.darkMode.checked;
  state.settings.notificationsEnabled = els.notificationsEnabled.checked;
  state.settings.debugMode = els.debugMode.checked;
  saveSettings();
  applyTheme();
  startAutoRefresh();
  startLiveTransport();
  toast("Dashboard settings saved.");
}

function loadSettingsIntoUi() {
  els.serverUrl.value = state.settings.serverUrl;
  els.phoneUrl.value = state.settings.phoneUrl;
  els.tokenInput.value = state.settings.token;
  els.refreshInterval.value = state.settings.refreshInterval;
  els.darkMode.checked = state.settings.darkMode;
  els.notificationsEnabled.checked = state.settings.notificationsEnabled;
  els.debugMode.checked = state.settings.debugMode;
  els.publicKey.value = getPublicKey() || "";
}

function applyTheme() {
  document.body.classList.toggle("dark", Boolean(state.settings.darkMode));
}

function startAutoRefresh() {
  if (refreshHandle) clearInterval(refreshHandle);
  refreshHandle = setInterval(() => refreshAll(false), state.settings.refreshInterval);
}

function startLiveTransport() {
  if (liveSocketRetry) clearTimeout(liveSocketRetry);
  if (liveSocket) {
    try { liveSocket.close(); } catch { /* best effort */ }
    liveSocket = null;
  }
  const base = state.settings.serverUrl.replace(/\/$/, "");
  if (!state.settings.token || !/^https?:\/\//i.test(base)) return;
  const wsBase = base.replace(/^http/i, "ws");
  api.wsTicket().then(ticketData => {
    if (!ticketData?.ticket) throw new Error("No WebSocket ticket");
    const endpoint = `${wsBase}/ws?role=dashboard&ticket=${encodeURIComponent(ticketData.ticket)}`;
    liveSocket = new WebSocket(endpoint);
    liveSocket.addEventListener("open", () => {
      els.connectionBadge.textContent = "Recovery server online · live";
      els.connectionBadge.className = "badge good";
    });
    liveSocket.addEventListener("message", event => {
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      if (message.type === "command_ack") {
        els.commandStatus.textContent = message.result?.ok ? "Command applied on phone" : `Command failed: ${message.result?.error || "unknown error"}`;
        refreshAll(false);
      } else if (message.type === "phone_presence") {
        els.commandStatus.textContent = message.online ? "Phone connected · push transport active" : "Phone offline · HTTP queue active";
        renderConnection({ ok: true }, message.online ? { online: true } : null);
      } else if (message.type === "location_update") {
        renderLiveLocation({ latest: message.location, path: [message.location] });
        refreshLocationHistory();
      } else if (message.type === "event" || message.type === "status") {
        // Push updates refresh the affected cards and timeline; the existing
        // HTTP poll remains as a recovery path for missed browser messages.
        refreshAll(false);
      }
    });
    liveSocket.addEventListener("close", () => {
      liveSocket = null;
      liveSocketRetry = setTimeout(startLiveTransport, Math.max(3000, state.settings.refreshInterval));
    });
    liveSocket.addEventListener("error", () => {
      try { liveSocket.close(); } catch { /* best effort */ }
    });
  }).catch(() => {
    liveSocketRetry = setTimeout(startLiveTransport, Math.max(3000, state.settings.refreshInterval));
  });
}

function notifyChanges(next) {
  if (next.photoCount > latestSnapshot.photoCount) notifyIfNeeded("Guardian Lock", "New recovery photo received.");
  if (next.audioCount > latestSnapshot.audioCount) notifyIfNeeded("Guardian Lock", "New audio recording received.");
  if (next.offline && !latestSnapshot.offline) notifyIfNeeded("Guardian Lock", "Recovery server appears offline.");
  if (next.batteryCritical && !latestSnapshot.batteryCritical) notifyIfNeeded("Guardian Lock", "Phone battery is critical.");
  if (next.lostMode && !latestSnapshot.lostMode) notifyIfNeeded("Guardian Lock", "Lost Mode enabled.");
  if (!next.lostMode && latestSnapshot.lostMode) notifyIfNeeded("Guardian Lock", "Found Device enabled.");
}

function friendlyError(error, fallback) {
  const message = error instanceof FriendlyError ? error.message : (error?.message || fallback);
  els.connectionBadge.textContent = message;
  els.connectionBadge.className = "badge bad";
  els.commandStatus.textContent = message;
  toast(message);
}

function toast(message) {
  els.toast.textContent = message;
  els.toast.classList.remove("hidden");
  setTimeout(() => els.toast.classList.add("hidden"), 3500);
}

function valueOrNull(result) {
  return result.status === "fulfilled" ? result.value : null;
}

function formatOnOff(value) {
  if (value === undefined || value === null) return "--";
  return value ? "On" : "Off";
}

function isBatteryCritical(status = {}) {
  const value = Number(status.batteryPercent ?? status.battery);
  return Number.isFinite(value) && value <= 10;
}

function renderBatteryHistory(items) {
  const host = document.getElementById("batteryHistory");
  if (!host) return;
  host.innerHTML = "";
  if (!items.length) {
    host.innerHTML = `<span class="muted">No battery history yet.</span>`;
    return;
  }
  for (const item of items.slice(-40)) {
    const level = Math.max(0, Math.min(100, Number(item.batteryPercent)));
    const bar = document.createElement("div");
    bar.className = "battery-bar";
    bar.style.height = `${Math.max(8, level)}%`;
    bar.title = `${level}% · ${new Date(item.timestamp).toLocaleString()}`;
    host.appendChild(bar);
  }
}

function formatBytes(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}
