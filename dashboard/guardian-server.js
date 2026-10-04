const http = require("http");
const fs = require("fs");
const path = require("path");
const url = require("url");
const crypto = require("crypto");
const { WebSocketServer } = require("ws");

const port = Number(process.env.PORT || 8787);
const token = process.env.GUARDIAN_TOKEN || "change-me-before-use";
const root = __dirname;
const dataRoot = process.env.GUARDIAN_DATA_DIR || path.join(root, "data");
const uploads = path.join(dataRoot, "recover_uploads");
const commandDir = path.join(dataRoot, "recover_commands");
const pairingDir = path.join(dataRoot, "watch_pairings");
const eventDir = path.join(dataRoot, "recover_events");
fs.mkdirSync(uploads, { recursive: true });
fs.mkdirSync(commandDir, { recursive: true });
fs.mkdirSync(pairingDir, { recursive: true });
fs.mkdirSync(eventDir, { recursive: true });

// WebSocket is an acceleration layer only. The signed HTTP queue remains the
// source of truth so an offline phone can reconnect without losing commands.
let phoneSocket = null;
const dashboardSockets = new Set();
const watchSockets = new Set();
const wsTickets = new Map();
const liveLocationHistory = [];

function issueWebSocketTicket(role = "dashboard") {
  const now = Date.now();
  for (const [ticket, record] of wsTickets) if (record.expiresAt <= now) wsTickets.delete(ticket);
  const ticket = crypto.randomBytes(24).toString("base64url");
  wsTickets.set(ticket, { role, expiresAt: now + 30_000 });
  return ticket;
}

function socketOpen(socket) {
  return Boolean(socket && socket.readyState === 1);
}

function sendSocket(socket, payload) {
  if (!socketOpen(socket)) return false;
  try {
    socket.send(JSON.stringify(payload));
    return true;
  } catch {
    return false;
  }
}

function broadcastSocket(payload, includePhone = false) {
  for (const socket of dashboardSockets) sendSocket(socket, payload);
  for (const socket of watchSockets) sendSocket(socket, payload);
  if (includePhone) sendSocket(phoneSocket, payload);
}

function pushCommandToPhone(id, command, serverReceivedAt = Date.now()) {
  if (!socketOpen(phoneSocket)) return false;
  return sendSocket(phoneSocket, {
    type: "command",
    id,
    command,
    serverReceivedAt,
  });
}

function queuedCommandEnvelope(item) {
  try {
    const payload = JSON.parse(fs.readFileSync(item.file, "utf8"));
    return { type: "command", id: payload.id, command: payload.command, serverReceivedAt: payload.serverReceivedAt || payload.createdAt };
  } catch {
    return null;
  }
}

function acceptLiveLocation(value, source = "phone") {
  const location = value && typeof value === "object" ? value : {};
  const latitude = Number(location.latitude);
  const longitude = Number(location.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    throw new Error("invalid_location");
  }
  const item = {
    timestamp: Number(location.timestamp || Date.now()),
    latitude,
    longitude,
    accuracyMeters: Number.isFinite(Number(location.accuracyMeters)) ? Number(location.accuracyMeters) : null,
    speedMps: Number.isFinite(Number(location.speedMps)) ? Number(location.speedMps) : null,
    bearing: Number.isFinite(Number(location.bearing)) ? Number(location.bearing) : null,
    source: safeName(source),
  };
  const last = liveLocationHistory[liveLocationHistory.length - 1];
  if (!last || last.timestamp !== item.timestamp || last.latitude !== item.latitude || last.longitude !== item.longitude) {
    liveLocationHistory.push(item);
    while (liveLocationHistory.length > 240) liveLocationHistory.shift();
    broadcastSocket({ type: "location_update", location: item });
  }
  return item;
}

const MIME = {
  ".html": "text/html",
  ".css": "text/css",
  ".js": "text/javascript",
  ".json": "application/json",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".png": "image/png",
  ".m4a": "audio/mp4",
  ".aac": "audio/aac",
  ".mp3": "audio/mpeg",
  ".wav": "audio/wav",
  ".apk": "application/vnd.android.package-archive",
};

function send(res, code, body, type = "application/json") {
  const text = Buffer.isBuffer(body) ? body : Buffer.from(typeof body === "string" ? body : JSON.stringify(body, null, 2));
  res.writeHead(code, {
    "Content-Type": `${type}; charset=utf-8`,
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-Guardian-Token",
    "Cache-Control": "no-store",
  });
  res.end(text);
}

function authorized(req, parsed) {
  return req.headers["x-guardian-token"] === token || parsed.query.token === token;
}

function collect(req, limitBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on("data", chunk => {
      total += chunk.length;
      if (total > limitBytes) {
        reject(new Error("too_large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function safeName(name) {
  return String(name || "upload.bin").replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 160);
}

function commandFiles() {
  return fs.readdirSync(commandDir)
    .filter(name => /^cmd_\d+_[a-zA-Z0-9._-]+\.json$/.test(name))
    .map(name => {
      const file = path.join(commandDir, name);
      const stat = fs.statSync(file);
      return { name, file, createdAt: stat.mtimeMs };
    })
    .sort((a, b) => a.createdAt - b.createdAt);
}

function queueCommand(body) {
  const command = JSON.parse(body.toString("utf8"));
  const required = ["command", "payload", "nonce", "issuedAt", "signatureBase64"];
  for (const key of required) {
    if (command[key] === undefined || command[key] === null) throw new Error(`missing_${key}`);
  }
  const id = `cmd_${Date.now()}_${safeName(command.command)}_${safeName(command.nonce).slice(0, 18)}.json`;
  const createdAt = Date.now();
  const serverReceivedAt = createdAt;
  fs.writeFileSync(path.join(commandDir, id), JSON.stringify({ id, command, createdAt, serverReceivedAt }, null, 2));
  // Push after durable queueing. A failed push is harmless; HTTP polling will
  // deliver the same signed envelope later.
  pushCommandToPhone(id, command, serverReceivedAt);
  return { id, serverReceivedAt };
}

function ackCommand(id, result) {
  const safe = safeName(path.basename(id));
  const file = path.join(commandDir, safe);
  if (!fs.existsSync(file)) return false;
  const ackFile = path.join(commandDir, safe.replace(/^cmd_/, "ack_"));
  const payload = JSON.parse(fs.readFileSync(file, "utf8"));
  const ackReceivedAt = Date.now();
  payload.ackedAt = ackReceivedAt;
  payload.result = result || {};
  payload.latency = {
    commandCreatedAt: payload.createdAt || null,
    serverReceivedAt: payload.serverReceivedAt || payload.createdAt || null,
    phoneReceivedAt: result?.phoneReceivedAt || null,
    executionStartedAt: result?.executionStartedAt || null,
    executionFinishedAt: result?.executionFinishedAt || null,
    ackSentAt: result?.ackSentAt || null,
    dashboardReceivedAckAt: ackReceivedAt,
  };
  fs.writeFileSync(ackFile, JSON.stringify(payload, null, 2));
  fs.unlinkSync(file);
  return payload;
}

function cleanupExpiredPairings() {
  const now = Date.now();
  for (const name of fs.readdirSync(pairingDir)) {
    if (!/^pair_\d{7}\.json$/.test(name)) continue;
    const file = path.join(pairingDir, name);
    const data = runCatchingJson(file);
    if (!data || Number(data.expiresAt || 0) < now) {
      runCatching(() => fs.unlinkSync(file));
    }
  }
}

function saveWatchPairing(body) {
  cleanupExpiredPairings();
  const json = JSON.parse(body.toString("utf8"));
  const code = String(json.code || "").trim();
  const publicKey = String(json.publicKey || "").trim();
  if (!/^\d{7}$/.test(code)) throw new Error("pairing_code_must_be_7_digits");
  if (publicKey.length < 60) throw new Error("public_key_too_short");
  const maxExpiry = Date.now() + 15 * 60 * 1000;
  const expiresAt = Math.min(Number(json.expiresAt || maxExpiry), maxExpiry);
  const file = path.join(pairingDir, `pair_${code}.json`);
  fs.writeFileSync(file, JSON.stringify({ code, publicKey, expiresAt, createdAt: Date.now() }, null, 2));
  return { code, expiresAt };
}

function getWatchPairing(code) {
  cleanupExpiredPairings();
  if (!/^\d{7}$/.test(String(code || ""))) return null;
  const file = path.join(pairingDir, `pair_${code}.json`);
  const data = runCatchingJson(file);
  if (!data || Number(data.expiresAt || 0) < Date.now()) return null;
  return data;
}

function runCatchingJson(file) {
  try {
    if (!fs.existsSync(file)) return null;
    return JSON.parse(fs.readFileSync(file, "utf8"));
  } catch {
    return null;
  }
}

function runCatching(fn) {
  try {
    fn();
  } catch {
    // best effort cleanup
  }
}

function saveRecoveryEvent(body) {
  const json = JSON.parse(body.toString("utf8"));
  const type = safeName(json.type || "event");
  const timestamp = Number(json.timestamp || Date.now());
  const event = {
    id: `event_${timestamp}_${type}`,
    type,
    detail: String(json.detail || "").slice(0, 600),
    timestamp,
    createdAt: Date.now(),
    source: safeName(json.source || "phone"),
  };
  const file = path.join(eventDir, `${event.id}.json`);
  fs.writeFileSync(file, JSON.stringify(event, null, 2));
  return event;
}

function recoveryEvents(query = {}) {
  const items = fs.readdirSync(eventDir)
    .filter(name => /^event_\d+_[a-zA-Z0-9._-]+\.json$/.test(name))
    .map(name => runCatchingJson(path.join(eventDir, name)))
    .filter(Boolean)
    .sort((a, b) => query.sort === "oldest" ? a.timestamp - b.timestamp : b.timestamp - a.timestamp);
  return items;
}

function allUploadFiles() {
  return fs.readdirSync(uploads)
    .map(name => {
      const file = path.join(uploads, name);
      const stat = fs.statSync(file);
      return { name, file, size: stat.size, modifiedAt: stat.mtimeMs, createdAt: timestampFromName(name) || stat.mtimeMs };
    })
    .filter(item => fs.statSync(item.file).isFile());
}

function timestampFromName(name) {
  const match = name.match(/^(\d{10,})/);
  return match ? Number(match[1]) : 0;
}

function isPhoto(name) {
  return /_photo_|photo_/i.test(name) || /\.(jpg|jpeg|png)$/i.test(name);
}

function isAudio(name) {
  return /_audio_|audio_/i.test(name) || /\.(m4a|aac|mp3|wav)$/i.test(name);
}

function isStatus(name) {
  return /^status_\d+\.json$/i.test(name);
}

function statusFiles() {
  return allUploadFiles().filter(item => isStatus(item.name)).sort((a, b) => b.createdAt - a.createdAt);
}

function parseStatus(item) {
  try {
    return { meta: publicMeta(item, "status"), data: JSON.parse(fs.readFileSync(item.file, "utf8")) };
  } catch {
    return { meta: publicMeta(item, "status"), data: {} };
  }
}

function publicMeta(item, type) {
  return {
    id: item.name,
    name: item.name,
    type,
    size: item.size,
    timestamp: item.createdAt,
    isoTime: new Date(item.createdAt).toISOString(),
  };
}

function cameraFromName(name) {
  if (/rear|back/i.test(name)) return "rear";
  if (/front/i.test(name)) return "front";
  return "camera";
}

function paginate(items, query) {
  const limit = Math.min(Number(query.limit || 60), 200);
  const offset = Math.max(Number(query.offset || 0), 0);
  return { limit, offset, total: items.length, items: items.slice(offset, offset + limit) };
}

function filteredMedia(type, query) {
  const filter = type === "photo" ? isPhoto : isAudio;
  let items = allUploadFiles().filter(item => filter(item.name));
  if (query.date) {
    items = items.filter(item => new Date(item.createdAt).toISOString().slice(0, 10) === query.date);
  }
  items.sort((a, b) => query.sort === "oldest" ? a.createdAt - b.createdAt : b.createdAt - a.createdAt);
  return items;
}

function latestStatus() {
  const latest = statusFiles()[0];
  return latest ? parseStatus(latest) : null;
}

function timeline(query) {
  const events = [];
  for (const event of recoveryEvents(query)) {
    events.push({ ...event, label: event.type === "motion_detected" ? "Motion detected" : event.type.replace(/_/g, " ") });
  }
  for (const item of allUploadFiles()) {
    if (isPhoto(item.name)) events.push({ ...publicMeta(item, "photo"), label: `${cameraFromName(item.name)} camera photo` });
    if (isAudio(item.name)) events.push({ ...publicMeta(item, "audio"), label: "Audio recorded" });
    if (isStatus(item.name)) {
      const status = parseStatus(item).data;
      events.push({
        ...publicMeta(item, "status"),
        label: status.lostMode ? "Status uploaded: Lost Mode active" : "Status uploaded",
        batteryPercent: status.batteryPercent,
        network: status.network,
      });
    }
  }
  events.sort((a, b) => query.sort === "oldest" ? a.timestamp - b.timestamp : b.timestamp - a.timestamp);
  return paginate(events, query);
}

function stats() {
  const files = allUploadFiles();
  const photos = files.filter(item => isPhoto(item.name));
  const audio = files.filter(item => isAudio(item.name));
  const statuses = statusFiles().map(parseStatus);
  return {
    totalPhotos: photos.length,
    totalAudio: audio.length,
    totalStatus: statuses.length,
    storageUsedBytes: files.reduce((sum, item) => sum + item.size, 0),
    batteryHistory: statuses
      .map(item => ({ timestamp: item.meta.timestamp, batteryPercent: item.data.batteryPercent ?? null }))
      .filter(item => item.batteryPercent !== null)
      .slice(0, 100)
      .reverse(),
    recoveryEvents: timeline({ limit: 100, offset: 0 }).total,
  };
}

function sendFile(req, res, parsed, type, name) {
  if (!authorized(req, parsed)) {
    return send(res, 401, {
      ok: false,
      error: "bad_token"
    });
  }

  // Only use the filename itself.
  const requestedName = path.basename(String(name || ""));
  const safe = safeName(requestedName);
  const file = path.join(uploads, safe);

  // Make sure the requested file really exists.
  if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    return send(res, 404, {
      ok: false,
      error: "missing_file",
      name: safe
    });
  }

  const stat = fs.statSync(file);
  const ext = path.extname(file).toLowerCase();
  const contentType = MIME[ext] || "application/octet-stream";

  const commonHeaders = {
    "Content-Type": contentType,
    "Accept-Ranges": "bytes",
    "Access-Control-Allow-Origin": "*",
    "Content-Disposition": `inline; filename="${safe}"`,
    "Cache-Control": "private, max-age=60"
  };

  // Browser audio/video requests can use Range.
  const range = req.headers.range;

  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);

    if (match) {
      let start = match[1] === "" ? 0 : Number(match[1]);
      let end = match[2] === "" ? stat.size - 1 : Number(match[2]);

      if (Number.isFinite(start) && Number.isFinite(end)) {
        end = Math.min(end, stat.size - 1);

        if (start >= 0 && start <= end && start < stat.size) {
          res.writeHead(206, {
            ...commonHeaders,
            "Content-Length": end - start + 1,
            "Content-Range": `bytes ${start}-${end}/${stat.size}`
          });

          return fs
            .createReadStream(file, { start, end })
            .pipe(res);
        }
      }
    }

    res.writeHead(416, {
      "Content-Range": `bytes */${stat.size}`,
      "Access-Control-Allow-Origin": "*",
      "Cache-Control": "no-store"
    });

    return res.end();
  }

  // Normal image/audio request.
  res.writeHead(200, {
    ...commonHeaders,
    "Content-Length": stat.size
  });

  return fs.createReadStream(file).pipe(res);
}

function serveStatic(req, res, pathname) {
  const clean = pathname === "/" ? "/index.html" : pathname;
  const target = path.normalize(path.join(root, clean));
  if (!target.startsWith(root) || !fs.existsSync(target) || fs.statSync(target).isDirectory()) return false;
  const ext = path.extname(target).toLowerCase();
  send(res, 200, fs.readFileSync(target), MIME[ext] || "application/octet-stream");
  return true;
}

const server = http.createServer(async (req, res) => {
  const parsed = url.parse(req.url, true);
  if (req.method === "OPTIONS") return send(res, 204, "");

  try {
    if (req.method === "POST" && parsed.pathname === "/recover/status") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const body = await collect(req, 256 * 1024);
      const file = path.join(uploads, `status_${Date.now()}.json`);
      fs.writeFileSync(file, body);
      return send(res, 200, { ok: true, saved: path.basename(file) });
    }

    if (req.method === "POST" && parsed.pathname === "/recover/upload") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const kind = safeName(parsed.query.type || "file");
      const name = safeName(parsed.query.name || `${kind}_${Date.now()}.bin`);
      const body = await collect(req, 25 * 1024 * 1024);
      const file = path.join(uploads, `${Date.now()}_${kind}_${name}`);
      fs.writeFileSync(file, body);
      return send(res, 200, { ok: true, saved: path.basename(file), bytes: body.length });
    }

    if (req.method === "POST" && parsed.pathname === "/recover/event") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const body = await collect(req, 64 * 1024);
      const event = saveRecoveryEvent(body);
      return send(res, 200, { ok: true, event });
    }

    if (req.method === "POST" && parsed.pathname === "/recover/live-location") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const body = await collect(req, 16 * 1024);
      const location = acceptLiveLocation(JSON.parse(body.toString("utf8")), "phone-http");
      return send(res, 200, { ok: true, location });
    }

    if (req.method === "GET" && parsed.pathname === "/recover/list") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      return send(res, 200, { ok: true, files: allUploadFiles().sort((a, b) => b.createdAt - a.createdAt).slice(0, 100).map(item => item.name) });
    }

    if (req.method === "GET" && parsed.pathname === "/recover/commands") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const commands = commandFiles().slice(0, 20).map(item => JSON.parse(fs.readFileSync(item.file, "utf8")));
      return send(res, 200, { ok: true, commands });
    }

    if (req.method === "GET" && parsed.pathname.startsWith("/recover/watch/pairing/")) {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const code = decodeURIComponent(parsed.pathname.replace("/recover/watch/pairing/", ""));
      const pairing = getWatchPairing(code);
      if (!pairing) return send(res, 404, { ok: false, error: "pairing_code_not_found_or_expired" });
      return send(res, 200, { ok: true, code: pairing.code, publicKey: pairing.publicKey, expiresAt: pairing.expiresAt });
    }

    if (req.method === "POST" && parsed.pathname.startsWith("/recover/commands/") && parsed.pathname.endsWith("/ack")) {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const body = await collect(req, 64 * 1024);
      const id = decodeURIComponent(parsed.pathname.replace("/recover/commands/", "").replace("/ack", ""));
      const result = body.length ? JSON.parse(body.toString("utf8")) : {};
      const ackRecord = ackCommand(id, result);
      return send(res, 200, { ok: true, acked: Boolean(ackRecord), latency: ackRecord?.latency || null });
    }

    if (parsed.pathname === "/api/health") return send(res, 200, {
      ok: true,
      serverTime: Date.now(),
      version: "2.1",
      websocket: { enabled: true, phoneOnline: socketOpen(phoneSocket) }
    });
    if (parsed.pathname.startsWith("/api/") && !authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });

    if (req.method === "GET" && parsed.pathname === "/api/ws-ticket") {
      const role = ["phone", "dashboard", "watch"].includes(String(parsed.query.role || "")) ? String(parsed.query.role) : "dashboard";
      return send(res, 200, { ok: true, role, ticket: issueWebSocketTicket(role), expiresInMs: 30_000 });
    }

    if (req.method === "POST" && parsed.pathname === "/api/watch/pairing") {
      const body = await collect(req, 64 * 1024);
      const pairing = saveWatchPairing(body);
      return send(res, 200, { ok: true, ...pairing });
    }

    if (req.method === "POST" && parsed.pathname === "/api/commands") {
      const body = await collect(req, 64 * 1024);
      const queued = queueCommand(body);
      return send(res, 200, { ok: true, queued: queued.id, serverReceivedAt: queued.serverReceivedAt });
    }

    if (parsed.pathname === "/api/photos") {
      const page = paginate(filteredMedia("photo", parsed.query).map(item => ({ ...publicMeta(item, "photo"), camera: cameraFromName(item.name), url: `/api/photos/${encodeURIComponent(item.name)}` })), parsed.query);
      return send(res, 200, { ok: true, ...page });
    }
    if (parsed.pathname.startsWith("/api/photos/")) return sendFile(req, res, parsed, "photo", decodeURIComponent(parsed.pathname.replace("/api/photos/", "")));

    if (parsed.pathname === "/api/audio") {
      const page = paginate(filteredMedia("audio", parsed.query).map(item => ({ ...publicMeta(item, "audio"), url: `/api/audio/${encodeURIComponent(item.name)}` })), parsed.query);
      return send(res, 200, { ok: true, ...page });
    }
    if (parsed.pathname.startsWith("/api/audio/")) return sendFile(req, res, parsed, "audio", decodeURIComponent(parsed.pathname.replace("/api/audio/", "")));

    if (parsed.pathname === "/api/status/latest") return send(res, 200, { ok: true, status: latestStatus() });
    if (parsed.pathname === "/api/location/latest") {
      const latest = latestStatus();
      return send(res, 200, { ok: true, location: liveLocationHistory.at(-1) || latest?.data?.location || latest?.data?.locationSummary || null, status: latest });
    }
    if (parsed.pathname === "/api/location/live") {
      return send(res, 200, { ok: true, active: liveLocationHistory.length > 0, latest: liveLocationHistory.at(-1) || null, path: liveLocationHistory.slice(-120) });
    }
    if (parsed.pathname === "/api/location/history") {
      const items = statusFiles()
        .map(parseStatus)
        .map(item => ({ timestamp: item.meta.timestamp, location: item.data.location || item.data.locationSummary || null, status: item.data }))
        .filter(item => item.location && item.location !== "unavailable" && item.location !== "permission_missing");
      return send(res, 200, { ok: true, ...paginate(items, parsed.query) });
    }
    if (parsed.pathname === "/api/timeline") return send(res, 200, { ok: true, ...timeline(parsed.query) });
    if (parsed.pathname === "/api/events/latest") return send(res, 200, { ok: true, event: recoveryEvents(parsed.query)[0] || null });
    if (parsed.pathname === "/api/events") return send(res, 200, { ok: true, ...paginate(recoveryEvents(parsed.query), parsed.query) });
    if (parsed.pathname === "/api/stats") return send(res, 200, { ok: true, stats: stats() });

    if (req.method === "GET" && serveStatic(req, res, parsed.pathname)) return;
    return send(res, 404, { ok: false, error: "not_found" });
  } catch (error) {
    return send(res, 500, { ok: false, error: error.message || "server_error" });
  }
});

const webSocketServer = new WebSocketServer({ noServer: true, maxPayload: 256 * 1024 });

function removeSocket(socket) {
  dashboardSockets.delete(socket);
  watchSockets.delete(socket);
  if (phoneSocket === socket) {
    phoneSocket = null;
    broadcastSocket({ type: "phone_presence", online: false, at: Date.now() });
  }
}

webSocketServer.on("connection", (socket, request, role) => {
  socket.role = role;
  if (role === "phone") {
    if (phoneSocket && phoneSocket !== socket) {
      try { phoneSocket.close(4001, "replaced"); } catch { /* best effort */ }
    }
    phoneSocket = socket;
  } else if (role === "dashboard") {
    dashboardSockets.add(socket);
  } else if (role === "watch") {
    watchSockets.add(socket);
  }

  sendSocket(socket, { type: "hello", role, transport: "websocket", serverTime: Date.now() });
  if (role === "phone") {
    sendSocket(socket, { type: "phone_presence", online: true, at: Date.now() });
    for (const item of commandFiles().slice(0, 20)) {
      const envelope = queuedCommandEnvelope(item);
      if (envelope) sendSocket(socket, envelope);
    }
  }
  if (role === "dashboard" || role === "watch") {
    sendSocket(socket, { type: "phone_presence", online: socketOpen(phoneSocket), at: Date.now() });
  }

  socket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString("utf8")); } catch { return; }
    if (message.type === "ping") return sendSocket(socket, { type: "pong", at: Date.now() });
    if (message.type === "command" && role === "dashboard") {
      try {
        const command = message.command && typeof message.command === "object" ? message.command : null;
        if (!command) throw new Error("missing_command");
        const queued = queueCommand(Buffer.from(JSON.stringify(command)));
        sendSocket(socket, {
          type: "command_accepted",
          requestId: safeName(message.requestId || "").slice(0, 120),
          id: queued.id,
          queued: true,
          serverReceivedAt: queued.serverReceivedAt,
        });
      } catch (error) {
        sendSocket(socket, {
          type: "command_accepted",
          requestId: safeName(message.requestId || "").slice(0, 120),
          ok: false,
          error: error.message || "command_queue_failed",
        });
      }
      return;
    }
    if (message.type === "ack" && role === "phone") {
      const id = safeName(message.id || "");
      const result = message.result && typeof message.result === "object" ? message.result : {};
      const ackRecord = id ? ackCommand(id, result) : false;
      const acked = Boolean(ackRecord);
      const dashboardReceivedAckAt = Date.now();
      const payload = {
        type: "command_ack",
        id,
        result,
        acked,
        executedAt: result.executionFinishedAt || dashboardReceivedAckAt,
        dashboardReceivedAckAt,
        latency: {
          ...(ackRecord?.latency || {}),
          phoneReceivedAt: result.phoneReceivedAt || null,
          executionStartedAt: result.executionStartedAt || null,
          executionFinishedAt: result.executionFinishedAt || null,
          ackSentAt: result.ackSentAt || null,
          dashboardReceivedAckAt,
        },
      };
      broadcastSocket(payload);
      return;
    }
    if (message.type === "event" && (role === "phone" || role === "watch")) {
      try {
        const event = message.event && typeof message.event === "object" ? message.event : message;
        const saved = saveRecoveryEvent(Buffer.from(JSON.stringify({ ...event, source: event.source || role })));
        broadcastSocket({ type: "event", event: saved });
      } catch { /* malformed telemetry is ignored */ }
      return;
    }
    if (message.type === "status" && (role === "phone" || role === "watch")) {
      broadcastSocket({ type: "status", status: message.status || {}, receivedAt: Date.now() });
    }
    if (message.type === "location_update" && role === "phone") {
      try { acceptLiveLocation(message.location, "phone-websocket"); } catch { /* reject malformed location */ }
    }
  });
  socket.on("close", () => removeSocket(socket));
  socket.on("error", () => removeSocket(socket));
});

server.on("upgrade", (request, socket, head) => {
  const parsed = url.parse(request.url, true);
  if (parsed.pathname !== "/ws") {
    socket.destroy();
    return;
  }
  const role = String(parsed.query.role || "dashboard");
  const suppliedToken = request.headers["x-guardian-token"] || parsed.query.token;
  const suppliedTicket = String(parsed.query.ticket || "");
  const ticketRecord = wsTickets.get(suppliedTicket);
  const ticketValid = ticketRecord && ticketRecord.expiresAt > Date.now() && ticketRecord.role === role;
  if ((!ticketValid && suppliedToken !== token) || !["phone", "dashboard", "watch"].includes(role)) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }
  if (ticketValid) wsTickets.delete(suppliedTicket);
  webSocketServer.handleUpgrade(request, socket, head, ws => {
    webSocketServer.emit("connection", ws, request, role);
  });
});

server.listen(port, () => {
  console.log(`Guardian recovery server running on http://0.0.0.0:${port}`);
  console.log("Set GUARDIAN_TOKEN to a private value before public use.");
});
