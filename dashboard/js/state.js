export const defaults = {
  serverUrl: localStorage.getItem("guardian_recovery_server_url") || (location.origin && location.origin !== "null" && location.protocol !== "file:" ? location.origin : "http://localhost:8787"),
  phoneUrl: localStorage.getItem("guardian_lock_phone_url") || "",
  token: localStorage.getItem("guardian_lock_upload_token") || "",
  refreshInterval: Number(localStorage.getItem("guardian_refresh_interval") || 3000),
  darkMode: localStorage.getItem("guardian_dark_mode") === "true",
  notificationsEnabled: localStorage.getItem("guardian_notifications") === "true",
  debugMode: localStorage.getItem("guardian_debug_mode") === "true",
};

export const state = {
  settings: { ...defaults },
  lastSnapshot: { photos: 0, audio: 0, lostMode: null, battery: null, lastSync: 0 },
  photoOffset: 0,
  audioOffset: 0,
  pageSize: 60,
};

export function saveSettings(settings) {
  state.settings = { ...state.settings, ...settings };
  localStorage.setItem("guardian_lock_phone_url", state.settings.phoneUrl);
  localStorage.setItem("guardian_recovery_server_url", state.settings.serverUrl);
  localStorage.setItem("guardian_lock_upload_token", state.settings.token);
  localStorage.setItem("guardian_refresh_interval", String(state.settings.refreshInterval));
  localStorage.setItem("guardian_dark_mode", String(state.settings.darkMode));
  localStorage.setItem("guardian_notifications", String(state.settings.notificationsEnabled));
  localStorage.setItem("guardian_debug_mode", String(state.settings.debugMode));
}
