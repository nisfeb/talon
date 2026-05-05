# Talon notification push relay

Server-side companion to the Talon client that maintains a 24×7
SSE connection to each registered user's Urbit ship and dispatches
FCM pushes when `%activity` events arrive.

This is the layer that survives OEM background-killing, app force-
stop, and process death — the things client-side code can't recover
from on its own. See `../docs/notifications-bulletproof.md` for the
full architecture.

## Status

- ✅ HTTP API (`/register`, `DELETE /devices/{id}`, `/health/{id}`)
- ✅ Per-user encrypted credential storage (SQLite + AES-GCM)
- ✅ Per-ship SSE consumer with exponential-backoff reconnect
- ✅ Last-event-id cursor for restart-safe dedup
- ⚠️ FCM dispatch — stubbed (logged) until `FIREBASE_CREDENTIALS_PATH`
   is supplied and the firebase-admin reflective-init lights up.
- ⏳ APNS dispatch — design accommodates it; not coded.
- ⏳ Self-test endpoint — design doc Phase 4 item.

## Quick start (local)

```bash
export RELAY_MASTER_SECRET="$(openssl rand -hex 32)"
./gradlew :relay:installDist
./relay/build/install/relay/bin/relay
```

The relay listens on `:8080` by default. Override with `RELAY_PORT`.

## Quick start (Docker)

```bash
mkdir -p data
docker build -t talon-relay relay/
docker run -d \
  --name talon-relay \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  -e RELAY_MASTER_SECRET=$(openssl rand -hex 32) \
  -e RELAY_DB=/data/relay.db \
  -e FIREBASE_CREDENTIALS_PATH=/data/firebase-admin.json \
  -v $(pwd)/firebase-admin.json:/data/firebase-admin.json:ro \
  talon-relay
```

`firebase-admin.json` is the service-account JSON downloaded from
Firebase Console → Project Settings → Service Accounts. **Treat as
secret** — anyone with this file can send pushes to your users.

## Required env

| Var | Purpose |
|---|---|
| `RELAY_MASTER_SECRET` | AES-GCM encryption key derivation source. Must be set, must be ≥32 random chars. **Rotating this invalidates every stored cookie** — all users will need to re-register. |

## Optional env

| Var | Default | Purpose |
|---|---|---|
| `RELAY_PORT` | `8080` | HTTP port. |
| `RELAY_DB` | `./relay.db` | SQLite file path. |
| `FIREBASE_CREDENTIALS_PATH` | (unset) | Path to firebase-admin service-account JSON. When unset, push calls log instead of dispatching. |

## API

### `POST /register`

```json
{
  "platform": "android",
  "pushToken": "<FCM token>",
  "deviceId": "",
  "shipUrl": "https://my-ship.example.com",
  "patp": "~sampel-palnet",
  "code": "lidlut-tabwed-pillex-ridrup"
}
```

`deviceId` is `""` on first registration; the relay mints one and
returns it. Caller persists it locally and reuses it on
re-registration (e.g. when the FCM token rotates).

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

The Talon client lets users point at any relay URL (including
`localhost:8080` for local testing) — see `Settings → Notification
Health → Relay endpoint` once the client surfaces the field.
