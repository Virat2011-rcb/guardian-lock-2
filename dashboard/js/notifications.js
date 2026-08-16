import { state } from "./state.js";

export async function ensureNotificationPermission() {
  if (!state.settings.notificationsEnabled || !("Notification" in window)) return false;
  if (Notification.permission === "granted") return true;
  if (Notification.permission === "denied") return false;
  return (await Notification.requestPermission()) === "granted";
}

export async function notify(title, body) {
  if (!(await ensureNotificationPermission())) return;
  new Notification(title, { body });
}

export function requestNotificationPermission() {
  return ensureNotificationPermission();
}

export function notifyIfNeeded(title, body) {
  notify(title, body);
}

export function watchNotifications(snapshot) {
  const previous = state.lastSnapshot;
  if (previous.photos && snapshot.photos > previous.photos) notify("New photo arrived", "Guardian Lock received a recovery photo.");
  if (previous.audio && snapshot.audio > previous.audio) notify("New audio arrived", "Guardian Lock received a recovery recording.");
  if (previous.lostMode === false && snapshot.lostMode === true) notify("Lost Mode enabled", "The phone entered Lost Mode.");
  if (previous.lostMode === true && snapshot.lostMode === false) notify("Found Device", "Lost Mode was cleared.");
  if (snapshot.battery !== null && snapshot.battery <= 10 && previous.battery !== snapshot.battery) notify("Battery critical", `Phone battery is ${snapshot.battery}%.`);
  const offline = previous.lastSync && Date.now() - snapshot.lastSync > 15000;
  if (offline) notify("Phone may be offline", "No fresh status upload has arrived recently.");
  state.lastSnapshot = snapshot;
}
