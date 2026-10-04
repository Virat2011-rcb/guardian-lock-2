export function parseCoordinates(value) {
  if (value && typeof value === "object" && Number.isFinite(Number(value.latitude)) && Number.isFinite(Number(value.longitude))) {
    return {
      lat: Number(value.latitude),
      lon: Number(value.longitude),
      accuracy: Number.isFinite(Number(value.accuracyMeters)) ? Number(value.accuracyMeters) : null,
      sourceTime: Number(value.timestamp || 0) || null,
    };
  }
  if (!value || typeof value !== "string") return null;
  const match = value.match(/(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)/i);
  if (!match) return null;
  const timeMatch = value.match(/@\s*(\d{10,})/);
  const accuracyMatch = value.match(/(?:acc(?:uracy)?\s*[=:]\s*)(\d+(?:\.\d+)?)/i);
  return {
    lat: Number(match[1]),
    lon: Number(match[2]),
    accuracy: accuracyMatch ? Number(accuracyMatch[1]) : null,
    sourceTime: timeMatch ? Number(timeMatch[1]) : null,
  };
}

let liveMap;
let liveMarker;
let liveAccuracy;
let livePath;

export function renderLiveLocation(data = {}) {
  const latest = data.latest || null;
  const path = Array.isArray(data.path) ? data.path : (latest ? [latest] : []);
  const host = document.getElementById("liveMap");
  if (!host || !latest || typeof L === "undefined") return;
  if (!liveMap) {
    liveMap = L.map(host, { zoomControl: true }).setView([latest.latitude, latest.longitude], 16);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", { maxZoom: 19, attribution: "© OpenStreetMap contributors" }).addTo(liveMap);
    liveMarker = L.marker([latest.latitude, latest.longitude]).addTo(liveMap);
    liveAccuracy = L.circle([latest.latitude, latest.longitude], { color: "#387257", fillColor: "#77a88b", fillOpacity: 0.2, radius: latest.accuracyMeters || 20 }).addTo(liveMap);
    livePath = L.polyline([], { color: "#387257", weight: 4 }).addTo(liveMap);
  }
  const point = [latest.latitude, latest.longitude];
  liveMarker.setLatLng(point).bindPopup(`Phone location<br>${new Date(latest.timestamp).toLocaleTimeString()}`);
  liveAccuracy.setLatLng(point).setRadius(latest.accuracyMeters || 20);
  livePath.setLatLngs(path.map(item => [item.latitude, item.longitude]));
  const stale = Date.now() - Number(latest.timestamp || 0) > 30_000;
  const state = document.getElementById("liveLocationState");
  if (state) state.textContent = stale ? "LOCATION STALE" : "LIVE · ${latest.speedMps == null ? "speed unavailable" : `${(latest.speedMps * 3.6).toFixed(1)} km/h`}";
  const freshness = document.getElementById("liveLocationFreshness");
  if (freshness) freshness.textContent = `${stale ? "Last known location" : "Last update"}: ${new Date(latest.timestamp).toLocaleString()} · Accuracy ${latest.accuracyMeters == null ? "--" : `${latest.accuracyMeters.toFixed(0)} m`}`;
}

export function renderLocation(raw) {
  const parsed = parseCoordinates(raw);
  document.getElementById("locationSummary").textContent = raw || "No location yet";
  document.getElementById("latValue").textContent = parsed ? parsed.lat.toFixed(5) : "--";
  document.getElementById("lonValue").textContent = parsed ? parsed.lon.toFixed(5) : "--";
  document.getElementById("accuracyValue").textContent = parsed?.accuracy ? `${parsed.accuracy} m` : "--";
  const live = document.getElementById("liveLocationFreshness");
  if (live) live.textContent = parsed?.sourceTime ? `GPS fix ${new Date(parsed.sourceTime).toLocaleString()}` : "Waiting for GPS timestamp";

  const google = document.getElementById("googleMapsButton");
  const osm = document.getElementById("osmButton");
  if (parsed) {
    google.href = `https://maps.google.com/?q=${parsed.lat},${parsed.lon}`;
    osm.href = `https://www.openstreetmap.org/?mlat=${parsed.lat}&mlon=${parsed.lon}#map=17/${parsed.lat}/${parsed.lon}`;
    google.classList.remove("disabled");
    osm.classList.remove("disabled");
  } else {
    google.removeAttribute("href");
    osm.removeAttribute("href");
    google.classList.add("disabled");
    osm.classList.add("disabled");
  }
}

export function renderLocationHistory(items = []) {
  const host = document.getElementById("locationHistory");
  if (!host) return;
  host.innerHTML = "";
  if (!items.length) {
    host.innerHTML = `<div class="muted">No live location history yet.</div>`;
    return;
  }
  for (const item of items.slice(0, 20)) {
    const parsed = parseCoordinates(item.location);
    const row = document.createElement("div");
    row.className = "location-row";
    row.innerHTML = parsed ? `
      <time>${new Date(item.timestamp).toLocaleTimeString()}</time>
      <span>${parsed.lat.toFixed(6)}, ${parsed.lon.toFixed(6)}</span>
      <a href="https://maps.google.com/?q=${parsed.lat},${parsed.lon}" target="_blank" rel="noreferrer">Map</a>
    ` : `
      <time>${new Date(item.timestamp).toLocaleTimeString()}</time>
      <span>${item.location || "unavailable"}</span>
      <span></span>
    `;
    host.appendChild(row);
  }
}

export function copyCoordinates() {
  const lat = document.getElementById("latValue").textContent;
  const lon = document.getElementById("lonValue").textContent;
  if (lat === "--" || lon === "--") return false;
  navigator.clipboard?.writeText(`${lat},${lon}`);
  return true;
}
