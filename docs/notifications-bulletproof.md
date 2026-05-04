# Bulletproof notifications — design doc

**Branch**: `notifications-bulletproof`  
**Status**: Phase 2 + 4 partially landed in this branch (uncommitted to master). Phase 1 blocked on user decisions. Phases 3 & 5 not started.

---

## Goal

A user should never miss a notification, regardless of:
- App lifecycle state (foreground / background / killed / force-stopped)
- OEM background-restriction policy (Xiaomi, Huawei, OnePlus, Samsung, …)
- Doze mode / Battery Saver / App Standby Buckets
- Network transitions (Wi-Fi ↔ cell, VPN, captive portal, brief outage)
- Process OOM
- Phone reboot
- Server-side eventbus drops

Cost is not a constraint.

## Non-goals (this iteration)

- iOS support (relay design accommodates it; client code doesn't yet target iOS).
- Per-message E2EE for relay-borne pushes (we send hint-only payloads or trust FCM/APNS).
- Generalized "any-app" relay — this is purpose-built for Tlon's `%activity` agent.

---

## Threat model — the real failure list

| # | Failure | Notifications lost? | Layer that catches it |
|---|---|---|---|
| 1 | OEM kills the foreground service | Until next app open | **L2 (relay)** |
| 2 | Doze mode + Battery Saver throttles `AlarmManager` | Delayed/lost while idle | **L2 (relay)** + L3 (WorkManager) |
| 3 | SSE socket dies silently — ship still emits | Until next reconnect | L1 watchdog (90s), **L2 (relay)** |
| 4 | Phone rebooted, `BootReceiver` not granted / disabled by OEM | Until next launch | **L2 (relay)** |
| 5 | Process OOM-killed; restart never happens | Until next launch | **L2 (relay)** |
| 6 | App force-stopped by user or OS | Until next launch | **L2 (relay)** |
| 7 | Network transition, SSE doesn't reconnect cleanly | Window of missed events | L3 NetworkCallback ✅ |
| 8 | Notification posted but DND / Focus / Quiet Hours buries it | Silently buried | L5 critical-alerts tier |
| 9 | Notification posted but Android groups & summarizes it | User scrolls past | L5 channel tuning |
| 10 | Desktop app is not running | Always lost while closed | L2 (relay) → tray helper |
| 11 | Desktop notification daemon absent / tray unsupported | Lost on this session | L4 health panel surfaces it |
| 12 | Server-side: ship not running / unreachable | Cannot deliver | L4 health panel surfaces it |

The honest summary: **reliable real-time push to a backgrounded mobile app, when the source-of-truth is a self-hosted Urbit ship that doesn't speak FCM, is not solvable with client-only logic**. Phase 1 (relay) is the only path that closes #1, #2, #5, #6, #10.

---

## Architecture: three independent delivery layers

An event makes it to the user as long as any one survives.

```
┌──────────────────────────────────────────────────────────────┐
│ Layer 1 — Real-time client SSE   (current behavior)         │
│    Talon connects to ship; SSE pushes events as they happen.│
│    Foreground service keeps the socket alive while app runs.│
│    Watchdog (90s idle) force-reconnects zombie sockets.     │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│ Layer 2 — Push relay   (Phase 1, NEW — biggest leverage)    │
│    Server connects to ship's SSE 24×7, fires FCM/APNS to    │
│    device on each event. Survives app death, OOM, OEM       │
│    aggression, reboot. The "never miss" guarantor.          │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│ Layer 3 — Reconcile-on-everything   (Phase 2, EXTENDS L1)   │
│    On boot, foreground, network change, screen on, periodic │
│    poll: scry %activity, diff against local, fire missed    │
│    notifications. Belt-and-suspenders for whatever 1+2 miss.│
└──────────────────────────────────────────────────────────────┘
```

Dedup: every push and every reconciled event carries the Urbit message id. The notifier checks "have I already posted a notification for this id?" before re-posting. Fire-once is enforced at the post layer; multi-source delivery is fine.

---

## Phase 2 — reconcile-on-everything

### What's in this branch

- New `commonMain/notify/NotificationHealth.kt` — observable diagnostics holder. Flows:
  - `lastSseEventMs` — last SSE event timestamp
  - `lastReconcileMs` — last successful `bootstrapActivity`
  - `sseConnected` — true while collect-loop is consuming
  - `forceReconnects` — cumulative watchdog-triggered reconnects (this session)
  - `recoveredEvents` — cumulative events surfaced by reconcile that SSE hadn't (placeholder; not wired yet)
- `TlonChatRepo` writes to `notificationHealth` on:
  - SSE event arrival → `markSseEvent`
  - Successful `bootstrapActivity` (start + catchUp) → `markReconcileSuccess`
  - Channel open → `markSseConnected(true)`
  - Watchdog timeout / `stop()` → `markSseConnected(false)`
  - Watchdog timeout → `incrementForceReconnects`
- `TalonSyncService` registers two new triggers (production-Android only):
  - `ConnectivityManager.NetworkCallback` → `repo.forceReconnect()` when an internet-validated network arrives. Catches Wi-Fi ↔ cell handoff that would otherwise wait 90s for the watchdog.
  - `ACTION_SCREEN_ON` BroadcastReceiver → `repo.catchUp()`. Cheap activity re-scry every screen wake.

### Still to land in Phase 2

- **WorkManager periodic catch-up** (15-min cadence). Survives force-stop / app standby. Adds `androidx.work:work-runtime-ktx`. Worker resolves the active `TalonApplication.repo` and calls `catchUp()` if reachable; no-op when no ship is signed in. **Not yet implemented.**
- **`recoveredEvents` counter wiring**. Currently records "reconcile happened" but not "reconcile saved you from a missed event." Need to tag each `applyActivityUpdate` invocation with whether it came from SSE-live or scry, so the diff can be counted. **Not yet implemented.**
- **`composeApp` Android path**. `App.kt` now plumbs `notificationHealth`, but the network-callback / screen-on triggers are only in `TalonSyncService` (production app/ path). When the composeApp Android path becomes the production app, we'll need an equivalent — likely fold the receivers into a service the composeApp Android path runs.

---

## Phase 1 — push relay (THE big one)

A small server we (Talon) operate that opens its own SSE connection to each registered user's ship and fires FCM/APNS pushes when activity events arrive. This is what closes #1, #2, #4, #5, #6, #10 in the threat-model table.

### Server-side

- **Tech**: Go or Kotlin/JVM. Single-tenant friendly. Stateless event handlers, stateful per-user SSE connection pool.
- **Storage**: Postgres or SQLite. Tables: `users`, `devices`, `last_event_id`. Per-user encrypted ship cookie (encryption key derived from a registration secret the device holds).
- **Eventbus**: subscribes to each registered ship's `%activity` stream. On new event, looks up that user's registered devices, sends FCM/APNS push.
- **Dedup**: tracks `last_event_id` per device. Resumes from there on relay restart.
- **Idle kick**: if a user's ship hasn't sent any event in N minutes, do a cheap scry to verify the channel is still alive; reconnect on failure.
- **Health endpoints**: `GET /health/<deviceId>` returns relay state + last event timestamp; the device polls this in the Settings → Notification Health panel.

### Client-side

- New `RelayClient` in `commonMain` owns:
  - FCM/APNS/desktop-webhook token registration with the relay
  - Re-registration on token rotation
  - "Connected to relay" health flow
- Android: `firebase-messaging` dependency. `MessagingService` receives the push and posts a notification using the same `Notifier` plumbing the in-app SSE path uses.
- Desktop: relay uses a long-poll endpoint or WebSocket; a small headless tray helper consumes it. Makes desktop notifications work even when the main app is closed.
- Notification dedup: each push payload carries the event id; when SSE later delivers the same event, the notifier dedup-checks by id.

### Hosting model

Three options. Recommendation: **C** (centralized default, self-hosted opt-in).

| | A. Centralized | B. Self-hosted only | C. Both |
|---|---|---|---|
| Ops cost | Single VPS (~$5/mo) | Zero | Single VPS + Docker image |
| User UX | One-tap | Compose deploy + DNS | One-tap, escape hatch |
| Trust | Trust talon-dev with ship cookie | Trust nobody | Either |
| Failure | Single point of failure | None | Self-host fallback |

### Payload privacy

- **(default) Hint-only**: push body is `{"event": "new-message", "whom": "<chat>"}`. Client wakes, pulls content via SSE. Content never transits FCM.
- **Preview**: push body includes sender + first 100 chars. Faster (no second round-trip), but content goes through Google's servers.

User-configurable per-conversation. Default to hint-only.

---

## Phase 3 — OEM-aware setup

Single biggest cause of "missed mobile notification" reports in the wild is OEM background-restriction policy. Doze + Battery Saver are documented; Xiaomi's "auto-start," Samsung's "Sleeping apps," OnePlus's "Don't put to sleep" are vendor-specific and undocumented.

- Detect manufacturer via `Build.MANUFACTURER` + `Build.BRAND`.
- For known-aggressive OEMs (Xiaomi/Huawei/Honor/OnePlus/Oppo/Vivo/Samsung One UI), surface a guided setup screen on first run, listing the specific toggles to flip.
- Self-check after setup: schedule a delayed alarm 60s out and verify it actually fires. If not, re-surface the warning.
- Service-killed detector: every minute the foreground service writes a heartbeat to `SharedPreferences`. On app launch, if the gap between `now` and the last heartbeat is much larger than expected uptime, surface "Your phone killed Talon at <time> — here's how to stop that" with the OEM-specific deeplink.

Status: **not started**.

---

## Phase 4 — observability & self-test

### What's in this branch

- New "Notification health" panel in Settings (above Composer). Shows:
  - Live channel: connected / reconnecting…
  - Last event: relative ("12s ago", "3m ago", "2h ago", "3d ago", "never")
  - Last sync: same format
  - Force-reconnects (when > 0): red when ≥ 3
  - Events recovered by sync (when > 0)
- Wired through both code paths (composeApp `App.kt` + production `TalonApp.kt`).

### Still to land in Phase 4

- **"Send test notification" button**. Pokes the ship to fire a known-shape activity event; the panel times the round-trip and reports which path delivered first (SSE vs relay). **Not yet implemented.**
- **Battery-optimization status**. `PowerManager.isIgnoringBatteryOptimizations` → "exempt" / "restricted (tap to fix)". Deep-links to `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. **Not yet implemented.**
- **Background-restriction status**. `ActivityManager.isBackgroundRestricted` → if true, surface the OEM-specific advice. **Not yet implemented.**
- **Notification permission status**. `POST_NOTIFICATIONS` (Android 13+). **Not yet implemented.**

---

## Phase 5 — UX polish (last)

Once delivery is bulletproof, polish what reaches the user.

- **Channel taxonomy**: separate `IMPORTANCE_HIGH` channels for DM / mention / watchword / channel post / digest. Per-category tuning.
- **Re-alert on dismiss-without-read**: if user swipes a DM notification away without tapping it, re-post 10 minutes later. Configurable; off by default; on for an "important contacts" list.
- **Persistent unread tally**: a single ongoing low-priority notification showing total unread, surviving dismiss. Tap → opens the unread tab. Goes away when all read.
- **Lock-screen previews**: respect OS, allow per-conversation override.
- **Critical alerts tier**: bypass DND. Both Android (`SOURCE_PRIORITY` channel + `setBypassDnd`) and macOS (Critical Alerts entitlement, Apple-approval-gated).

Status: **not started**.

---

## Implementation order recommendation

| Phase | Status | Effort | Eliminates |
|---|---|---|---|
| 2 (reconcile triggers) | **Partial — in this branch** | Low | #3, #7 |
| 4 (health panel) | **Partial — in this branch** | Low | (exposes the rest) |
| 3 (OEM setup) | Not started | Med | #1 (best client-only mitigation) |
| 1 (relay) | **Blocked on user decisions** | High | #1, #2, #4, #5, #6, #10 |
| 5 (UX polish) | Not started | Med | #8, #9 |

Phase 1 is where "bulletproof" actually lives. 2/3/4 cap out at "very reliable while the app is alive." Without 1, the goal is unreachable.

---

## User action items (Phase 1 prerequisites)

These block code on Phase 1; we can ship 2/3/4/5 without them.

### 1. Hosting model

Pick A, B, or C from the table above. Recommendation: **C**.

### 2. Firebase project

- Create project at [console.firebase.google.com](https://console.firebase.google.com) named "Talon."
- Enable Cloud Messaging.
- Add an Android app with package name `io.nisfeb.talon`.
- Download `google-services.json` and add it to the repo (gitignored — checked-in path is `composeApp/google-services.json` per Firebase plugin defaults).
- Generate a Firebase Admin SDK service-account JSON (Project Settings → Service Accounts → Generate new private key). The relay needs this to send pushes. Store as a secret on the relay host; do NOT commit it.

### 3. Push payload privacy

Default to **hint-only** unless there's a reason not to.

### 4. Domain (only if A or C)

Pick a subdomain you control. Suggestion: `relay.<your-domain>`.

### 5. Trust model confirmation

The relay needs each user's ship cookie (or `+code` from which it derives a cookie). We encrypt at rest with a per-user key derived from a registration secret only the device holds — relay-host compromise exposes ciphertext only, IF the device's secret isn't also compromised. This is the same trust model as Talon-the-app. Confirm.

---

## Open design questions

- **Multi-ship users**: relay subscribes to N ships per user, sends pushes tagged with the ship. Client-side notifier surfaces which ship. Already handled by the per-ship pip in v0.7.18+.
- **Network egress on relay**: SSE per ship is small but constant. ~1KB/min idle, spikes to ~10KB/event. 1000 active users ≈ 1GB/day egress. VPS-tier bandwidth swallows that easily.
- **APNS for iOS**: APNS auth is a `.p8` cert + team ID + key ID. Trivial to add when iOS lands. Same data path.
- **Relay restart hygiene**: on restart, replay missed events to each device by scrying `%activity` since the per-device `last_event_id`. Without this a deploy could lose events for connections that were mid-reconnect.

---

## Files touched in this branch

```
A  composeApp/src/commonMain/kotlin/io/nisfeb/talon/notify/NotificationHealth.kt
M  composeApp/src/commonMain/kotlin/io/nisfeb/talon/urbit/TlonChatRepo.kt
M  composeApp/src/commonMain/kotlin/io/nisfeb/talon/ui/screens/SettingsScreen.kt
M  composeApp/src/commonMain/kotlin/io/nisfeb/talon/compose/App.kt
M  composeApp/src/androidMain/kotlin/io/nisfeb/talon/TalonApplication.kt
M  composeApp/src/androidMain/kotlin/io/nisfeb/talon/TalonSyncService.kt
M  composeApp/src/androidMain/kotlin/io/nisfeb/talon/ui/TalonApp.kt
A  docs/notifications-bulletproof.md
```

Both targets compile clean; full desktop test suite green.
