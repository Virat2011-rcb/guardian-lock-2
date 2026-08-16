export function parseCoordinates(value) {
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
