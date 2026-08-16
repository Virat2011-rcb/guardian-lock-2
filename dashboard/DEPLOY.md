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

## Remote commands

The browser signs commands locally. The cloud server only queues the signed command. The phone pulls pending commands and verifies the signature before applying them.

This means:

- The server does not need the laptop private key.
- The server cannot create valid commands by itself.
- If the phone is offline, commands stay queued until the phone checks in.

## Live location

`Locate Now` queues a signed `locate_now` command. The phone captures status and uploads location to the server. The dashboard refreshes location history.

Do not expect millisecond GPS. Android background location, battery policy, and network latency make 1-10 second updates the practical range.
