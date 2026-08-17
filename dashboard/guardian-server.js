const http = require("http");
const fs = require("fs");
const path = require("path");
const url = require("url");

const port = Number(process.env.PORT || 8787);
const token = process.env.GUARDIAN_TOKEN || "change-me-before-use";
const root = __dirname;
const dataRoot = process.env.GUARDIAN_DATA_DIR || path.join(root, "data");
const uploads = path.join(dataRoot, "recover_uploads");
const commandDir = path.join(dataRoot, "recover_commands");
fs.mkdirSync(uploads, { recursive: true });
fs.mkdirSync(commandDir, { recursive: true });

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
  fs.writeFileSync(path.join(commandDir, id), JSON.stringify({ id, command, createdAt: Date.now() }, null, 2));
  return id;
}

function ackCommand(id, result) {
  const safe = safeName(path.basename(id));
  const file = path.join(commandDir, safe);
  if (!fs.existsSync(file)) return false;
  const ackFile = path.join(commandDir, safe.replace(/^cmd_/, "ack_"));
  const payload = JSON.parse(fs.readFileSync(file, "utf8"));
  payload.ackedAt = Date.now();
  payload.result = result || {};
  fs.writeFileSync(ackFile, JSON.stringify(payload, null, 2));
  fs.unlinkSync(file);
  return true;
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

    if (req.method === "GET" && parsed.pathname === "/recover/list") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      return send(res, 200, { ok: true, files: allUploadFiles().sort((a, b) => b.createdAt - a.createdAt).slice(0, 100).map(item => item.name) });
    }

    if (req.method === "GET" && parsed.pathname === "/recover/commands") {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const commands = commandFiles().slice(0, 20).map(item => JSON.parse(fs.readFileSync(item.file, "utf8")));
      return send(res, 200, { ok: true, commands });
    }

    if (req.method === "POST" && parsed.pathname.startsWith("/recover/commands/") && parsed.pathname.endsWith("/ack")) {
      if (!authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });
      const body = await collect(req, 64 * 1024);
      const id = decodeURIComponent(parsed.pathname.replace("/recover/commands/", "").replace("/ack", ""));
      const result = body.length ? JSON.parse(body.toString("utf8")) : {};
      return send(res, 200, { ok: true, acked: ackCommand(id, result) });
    }

    if (parsed.pathname === "/api/health") return send(res, 200, { ok: true, serverTime: Date.now(), version: "2.0" });
    if (parsed.pathname.startsWith("/api/") && !authorized(req, parsed)) return send(res, 401, { ok: false, error: "bad_token" });

    if (req.method === "POST" && parsed.pathname === "/api/commands") {
      const body = await collect(req, 64 * 1024);
      const id = queueCommand(body);
      return send(res, 200, { ok: true, queued: id });
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
      return send(res, 200, { ok: true, location: latest?.data?.location || latest?.data?.locationSummary || null, status: latest });
    }
    if (parsed.pathname === "/api/location/history") {
      const items = statusFiles()
        .map(parseStatus)
        .map(item => ({ timestamp: item.meta.timestamp, location: item.data.location || item.data.locationSummary || null, status: item.data }))
        .filter(item => item.location && item.location !== "unavailable" && item.location !== "permission_missing");
      return send(res, 200, { ok: true, ...paginate(items, parsed.query) });
    }
    if (parsed.pathname === "/api/timeline") return send(res, 200, { ok: true, ...timeline(parsed.query) });
    if (parsed.pathname === "/api/stats") return send(res, 200, { ok: true, stats: stats() });

    if (req.method === "GET" && serveStatic(req, res, parsed.pathname)) return;
    return send(res, 404, { ok: false, error: "not_found" });
  } catch (error) {
    return send(res, 500, { ok: false, error: error.message || "server_error" });
  }
});

server.listen(port, () => {
  console.log(`Guardian recovery server running on http://0.0.0.0:${port}`);
  console.log("Set GUARDIAN_TOKEN to a private value before public use.");
});
