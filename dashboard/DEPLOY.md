# Guardian Lock Cloud Dashboard

This dashboard can run on any Node.js 18+ server. Use HTTPS in production so browser key generation and signed commands work.

## Required environment variables

```text
GUARDIAN_TOKEN=<long-private-token>
PORT=<platform-provided-port-or-8787>
```

Use the same `GUARDIAN_TOKEN` inside the Guardian Lock phone app as the Recovery Token.

## Render / Railway

Deploy the `dashboard` folder as a Node.js app.

Start command:

```text
npm start
```

Set environment variable:

```text
GUARDIAN_TOKEN=choose-a-long-random-token
```

After deployment, open:

```text
https://your-dashboard-domain
```

In Guardian Lock phone app, set:

```text
Recovery Server URL = https://your-dashboard-domain
Recovery Token = same GUARDIAN_TOKEN
```

## VPS

```powershell
cd dashboard
$env:GUARDIAN_TOKEN="choose-a-long-random-token"
npm start
```

Put the server behind HTTPS using a reverse proxy such as Caddy, Nginx, or Cloudflare Tunnel.

## Instant command transport

The browser signs commands locally. The cloud server only queues the signed command. When the phone is online it also keeps an authenticated WebSocket open at `/ws`; queued envelopes are pushed immediately and acknowledged after the phone verifies and applies them. The phone reconnects with exponential backoff and falls back to the existing HTTP queue whenever WebSocket is unavailable.

This means:

- The server does not need the laptop private key.
- The server cannot create valid commands by itself.
- If the phone is offline, commands stay queued until the phone checks in.
- The phone remains the only command verifier; duplicate delivery is rejected by the existing nonce/replay protection.
- The dashboard uses WebSocket updates for presence, status, events, and command acknowledgements, while its normal refresh interval remains a recovery path.

`ws` is installed from `dashboard/package.json` during deployment. Use HTTPS in production; the browser will then connect using `wss://` automatically. The phone authenticates with the `X-Guardian-Token` handshake header. Browser WebSocket clients use the token query parameter because browsers cannot set custom handshake headers; do not log request URLs on the reverse proxy.

## Live location

`Locate Now` queues a signed `locate_now` command. The phone captures status and uploads location to the server. The dashboard refreshes location history.

Do not expect millisecond GPS. Android background location, battery policy, and network latency make 1-10 second updates the practical range.

`Start Live Tracking` queues the signed `live_tracking_start` command. After phone-side verification, the phone shows an ongoing location notification and sends bounded location fixes through the authenticated WebSocket (with `/recover/live-location` as fallback). The server forwards only validated coordinates to the dashboard as `location_update` messages. `Stop Tracking` sends `live_tracking_stop` and ends the foreground location service; one-time Locate remains independent.

The dashboard map uses Leaflet with OpenStreetMap tiles and does not require an API key. Live points are kept in a bounded in-memory path (240 fixes) and are marked stale after 30 seconds without a fresh fix.
