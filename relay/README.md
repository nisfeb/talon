# Talon notification push relay

Server-side companion to the Talon client that maintains a 24×7
SSE connection to each registered user's Urbit ship and POSTs to
their UnifiedPush distributor endpoint when `%activity` events
arrive.

This is the layer that survives OEM background-killing, app force-
stop, and process death — the things client-side code can't recover
from on its own. See `../docs/notifications-bulletproof.md` for the
full architecture.

## Status

- ✅ HTTP API (`/register`, `DELETE /devices/{id}`, `/health/{id}`)
- ✅ Per-user encrypted credential storage (SQLite + AES-GCM)
- ✅ Per-ship SSE consumer with exponential-backoff reconnect
- ✅ Last-event-id cursor for restart-safe dedup
- ✅ UnifiedPush dispatch — plain HTTP POST to the device's
   distributor endpoint URL.
- ⏳ Self-test endpoint — design-doc Phase 4 item.
- ⏳ Per-row "transport" column for future non-UnifiedPush
   delivery (e.g. desktop webhook). Schema reserves the field.

## Push transport: UnifiedPush

[UnifiedPush](https://unifiedpush.org) is a vendor-neutral push
protocol. The user installs a *distributor* app once (ntfy,
NextPush, Conversations, etc.); the distributor holds one
persistent connection to its server, and apps register with it
over local IPC. Every UnifiedPush-capable app on the device
shares that one connection.

For this relay, that means: the device hands us an HTTPS endpoint
URL the distributor minted. We POST a JSON body to it; the
distributor's server pushes it to the device.

**Zero Google dependency.** No FCM, no Play Services. Self-hosters
can run their own ntfy server next to the relay; users can pick
any distributor on F-Droid.

## Quick start (local)

```bash
export RELAY_MASTER_SECRET="$(openssl rand -hex 32)"
./gradlew :relay:installDist
./relay/build/install/relay/bin/relay
```

The relay listens on `:8080` by default. Override with `RELAY_PORT`.

## Quick start (Docker)

Build from the repo root — the Gradle wrapper, `settings.gradle.kts`,
and `gradle/libs.versions.toml` all live above `relay/` and are
needed for the build.

```bash
mkdir -p data
docker build -t talon-relay -f relay/Dockerfile .
docker run -d \
  --name talon-relay \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  -e RELAY_MASTER_SECRET=$(openssl rand -hex 32) \
  -e RELAY_DB=/data/relay.db \
  talon-relay
```

That's the whole deploy for UnifiedPush. iOS VoIP adds an APNs key — see below.

## Required env

| Var | Purpose |
|---|---|
| `RELAY_MASTER_SECRET` | AES-GCM encryption key derivation source. Must be set, must be ≥32 random chars. **Rotating this invalidates every stored cookie** — all users will need to re-register. |

## Optional env

| Var | Default | Purpose |
|---|---|---|
| `RELAY_PORT` | `8080` | HTTP port. |
| `RELAY_DB` | `./relay.db` | SQLite file path. |

## APNs VoIP (iOS native call ringing)

Optional. Set these to push ring / ring-cancel to iOS devices as APNs
VoIP so a backgrounded or killed iPhone rings like a phone call.
Absent, the relay stays UnifiedPush-only and an `ios-voip` device's
rings are dropped with a warning — nothing else is affected.

| Var | Default | Purpose |
|---|---|---|
| `APNS_TEAM_ID` | — | Apple team id (10 chars). |
| `APNS_KEY_ID` | — | The APNs auth key's id (10 chars). |
| `APNS_P8` | — | The `.p8` key's PEM contents, inline. |
| `APNS_P8_FILE` | — | Or a path to the `.p8` (use this with a mount). |
| `APNS_BUNDLE_ID` | `io.nisfeb.talon` | Topic is `<bundle>.voip`. |
| `APNS_PRODUCTION` | `true` | `true` = api.push.apple.com (TestFlight / App Store); `false` = sandbox (a development build). |

All three of team id, key id, and a key (`APNS_P8` or `APNS_P8_FILE`)
must be present or APNs stays off. The same `.p8` signs VoIP — no
separate VoIP Services certificate is needed. Mount the key rather
than inlining the PEM:

```bash
docker run -d --name talon-relay -p 8080:8080 \
  -v $(pwd)/data:/data \
  -v $(pwd)/AuthKey_XXXXXXXXXX.p8:/secrets/apns.p8:ro \
  -e RELAY_MASTER_SECRET=... -e RELAY_DB=/data/relay.db \
  -e APNS_TEAM_ID=XXXXXXXXXX \
  -e APNS_KEY_ID=XXXXXXXXXX \
  -e APNS_P8_FILE=/secrets/apns.p8 \
  talon-relay
```

## API

### `POST /register`

```json
{
  "platform": "unifiedpush",
  "pushEndpoint": "https://ntfy.sh/upXXXXXXXX",
  "deviceId": "",
  "shipUrl": "https://my-ship.example.com",
  "patp": "~sampel-palnet",
  "code": "lidlut-tabwed-pillex-ridrup"
}
```

`deviceId` is `""` on first registration; the relay mints one and
returns it. Caller persists it locally and reuses it on
re-registration (e.g. when the distributor endpoint rotates).

The `code` is consumed once during the login call and never
persisted — only the resulting urbauth cookie is stored, encrypted
with the master secret.

Response:

```json
{ "deviceId": "<uuid>", "ok": true }
```

### `DELETE /devices/{deviceId}`

Tears down all SSE connections for the device and removes its rows.
Returns `204 No Content`.

### `GET /health/{deviceId}`

```json
{ "ok": true, "ships": 2 }
```

Returns the count of ships the device is registered for. Used by
the client's Settings → Notification Health panel as a "is the
relay seeing my ships?" probe.

## Trust model

See `../docs/notifications-bulletproof.md` § "Phase 1 — push relay,
Trust model." Short version: the relay encrypts each user's ship
cookie at rest with a key derived from `RELAY_MASTER_SECRET`. A
stolen sqlite file alone does not compromise users; a full relay-
host compromise (filesystem + memory) does. Same threat model as a
Tlon-hosted ship.

## Self-host vs centralized

This image is the same one Talon's centralized relay deploys. Self-
hosting users get exactly what we run, just on their own host.

The Talon client lets users point at any relay URL — see Settings →
Notification Health → Endpoint.
