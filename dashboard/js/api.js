import { state } from "./state.js";

let cloudSocket = null;
const cloudCommandWaiters = new Map();

export class FriendlyError extends Error {
  constructor(message, detail = "") {
    super(message);
    this.detail = detail;
  }
}

function apiUrl(path) {
  // REST requests carry the token in X-Guardian-Token. Keeping it out of
  // ordinary URLs prevents it leaking into browser history and proxy logs.
  return `${state.settings.serverUrl.replace(/\/$/, "")}${path}`;
}

async function request(path, options = {}) {
  try {
    const response = await fetch(apiUrl(path), {
      ...options,
      headers: {
        "X-Guardian-Token": state.settings.token,
        ...(options.headers || {}),
      },
    });
    if (response.status === 401) throw new FriendlyError("Invalid token", "Check the recovery server token.");
    if (response.status === 404) throw new FriendlyError("Nothing found yet", "No matching recovery data is available.");
    if (!response.ok) throw new FriendlyError("Recovery server error", `${response.status} ${response.statusText}`);
    return response.json();
  } catch (error) {
    if (error instanceof FriendlyError) throw error;
    throw new FriendlyError("Recovery server offline", "Start guardian-server.js or check the server URL.");
  }
}

function cleanParams(params = {}) {
  return Object.fromEntries(
    Object.entries(params).filter(
      ([, value]) => value !== undefined && value !== null && value !== ""
    )
  );
}

export const api = {
  health: () => request("/api/health"),
  wsTicket: () => request("/api/ws-ticket"),

  latestStatus: () => request("/api/status/latest"),

  latestLocation: () => request("/api/location/latest"),

  liveLocation: () => request("/api/location/live"),

  locationHistory: params =>
    request(`/api/location/history?${new URLSearchParams(cleanParams(params))}`),

  photos: params =>
    request(`/api/photos?${new URLSearchParams(cleanParams(params))}`),

  audio: params =>
    request(`/api/audio?${new URLSearchParams(cleanParams(params))}`),

  timeline: params =>
    request(`/api/timeline?${new URLSearchParams(cleanParams(params))}`),

  stats: () => request("/api/stats"),

  fileUrl: path =>
    `${state.settings.serverUrl.replace(/\/$/, "")}${path}?token=${encodeURIComponent(state.settings.token)}`
};

const keyName = "guardian_lock_dashboard_private_jwk_v1";

function bytesToBase64(bytes) {
  let binary = "";
  new Uint8Array(bytes).forEach(byte => binary += String.fromCharCode(byte));
  return btoa(binary);
}

async function loadPrivateKey() {
  ensureCryptoAvailable();
  const saved = localStorage.getItem(keyName);
  if (!saved) return null;
  return crypto.subtle.importKey("jwk", JSON.parse(saved), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

function ensureCryptoAvailable() {
  if (!window.isSecureContext || !crypto?.subtle) {
    throw new FriendlyError(
      "Open dashboard using localhost",
      "Laptop key generation needs browser secure mode. Open http://localhost:8787 on the laptop, not file:// or http://192.168.x.x:8787."
    );
  }
}

export async function generateLaptopKey() {
  ensureCryptoAvailable();
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const privateJwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
  const publicSpki = await crypto.subtle.exportKey("spki", pair.publicKey);
  localStorage.setItem(keyName, JSON.stringify(privateJwk));
  localStorage.setItem("guardian_lock_public_spki", bytesToBase64(publicSpki));
  return bytesToBase64(publicSpki);
}

export function publicKey() {
  return localStorage.getItem("guardian_lock_public_spki") || "";
}

export const getPublicKey = publicKey;

/**
 * Local HTTP is intentionally disabled from HTTPS pages. This prevents a
 * public Railway dashboard from making mixed-content requests to a LAN IP.
 * Nearby mode is explicit; auto mode only uses local HTTP on a non-HTTPS page.
 */
export function localTransportAllowed() {
  const mode = state.settings.transportMode || "auto";
  if (!state.settings.phoneUrl || mode === "cloud") return false;
  if (window.location.protocol === "https:") return false;
  return mode === "nearby" || mode === "auto";
}

export function setCloudWebSocket(socket) {
  cloudSocket = socket;
}

export function clearCloudWebSocket(socket) {
  if (!socket || cloudSocket === socket) cloudSocket = null;
  for (const [requestId, waiter] of cloudCommandWaiters) {
    waiter.reject(new FriendlyError("Cloud connection lost", "The command can still use the HTTP fallback."));
    cloudCommandWaiters.delete(requestId);
  }
}

export function cloudWebSocketConnected() {
  return Boolean(cloudSocket && cloudSocket.readyState === WebSocket.OPEN);
}

export function handleCloudSocketMessage(message) {
  if (message?.type !== "command_accepted" || !message.requestId) return false;
  const waiter = cloudCommandWaiters.get(message.requestId);
  if (!waiter) return false;
  cloudCommandWaiters.delete(message.requestId);
  if (message.ok === false) waiter.reject(new FriendlyError("Cloud command rejected", message.error || "Command was not queued."));
  else waiter.resolve({ ok: true, queued: true, transport: "cloud_wss", id: message.id, serverReceivedAt: message.serverReceivedAt });
  return true;
}

export async function signedCommand(command, payload = "") {
  ensureCryptoAvailable();
  const privateKey = await loadPrivateKey();
  if (!privateKey) throw new FriendlyError("Generate laptop key first", "Paste the public key into Guardian Lock on the phone.");
  const nonce = crypto.randomUUID();
  const issuedAt = Date.now();
  const message = `${command}\n${payload}\n${nonce}\n${issuedAt}`;
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, new TextEncoder().encode(message));
  const signed = { command, payload, nonce, issuedAt, signatureBase64: bytesToBase64(signature) };
  if (localTransportAllowed()) {
    try {
      const response = await fetch(`${state.settings.phoneUrl.replace(/\/$/, "")}/command`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(signed),
      });
      const text = await response.text();
      const json = text ? JSON.parse(text) : { ok: response.ok };
      if (!response.ok || !json.ok) throw new FriendlyError(json.error === "Signed command rejected" ? "Signed command rejected" : "Phone command failed", json.error || response.statusText);
      return { ...json, transport: "nearby" };
    } catch (error) {
      if (error instanceof FriendlyError && error.message === "Signed command rejected") throw error;
      // A nearby phone can disappear; cloud transport remains the recovery path.
    }
  }
  if (cloudWebSocketConnected()) {
    const requestId = crypto.randomUUID();
    const result = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        cloudCommandWaiters.delete(requestId);
        reject(new FriendlyError("Cloud command timeout", "The server did not acknowledge the command."));
      }, 10_000);
      cloudCommandWaiters.set(requestId, {
        resolve: value => { clearTimeout(timeout); resolve(value); },
        reject: error => { clearTimeout(timeout); reject(error); },
      });
    });
    try {
      cloudSocket.send(JSON.stringify({ type: "command", requestId, command: signed }));
      return await result;
    } catch (error) {
      cloudCommandWaiters.delete(requestId);
      // The socket may fail after the server accepted the frame. The phone's
      // nonce/replay protection makes retrying through the durable HTTP queue
      // safe and preserves delivery when the cloud connection is unstable.
    }
    return queueRemoteCommand(signed);
  }
  return queueRemoteCommand(signed);
}

async function queueRemoteCommand(signed) {
  const response = await fetch(apiUrl("/api/commands"), {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Guardian-Token": state.settings.token,
    },
    body: JSON.stringify(signed),
  });
  const json = await response.json().catch(() => ({}));
  if (!response.ok || !json.ok) throw new FriendlyError("Cloud command queue failed", json.error || response.statusText);
  return { ok: true, queued: true, id: json.queued };
}
