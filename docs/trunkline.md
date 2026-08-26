# Trunkline v0 spike — runbook

Design: see the Trunkline design doc (calls + party lines over Urbit).
This spike is 1:1 desktop↔desktop, Tier 0 (host candidates only, no
STUN/TURN), and exists to measure two numbers on real ships:

- signaling RTT (offer→answer) — grep logs for `Trunk metric`
- time to live media (place→live)

## Pieces

- `urbit/trunk/` — the %trunk desk: stateless signaling router.
  Local `%trunk-action` pokes relay to the peer's %trunk as
  `%trunk-signal`; inbound signals surface as `%trunk-update` facts
  on `/calls`. **Not yet compiled on a ship** — written blind; expect
  a syntax pass on first `|commit`. Check `sys.kelvin` matches your
  ships' zuse.
- `call/TrunkWire.kt` — JSON wire (source of truth: `lib/trunk-json.hoon`).
- `call/CallController.kt` — signaling state machine + metrics.
- `call/CallEngine.kt` + `call/DesktopCallEngine.kt` — media half;
  desktop uses webrtc-java 0.14.0 (0.15.0 lacks Linux natives).
- `/call` slash command in a DM + a top-banner call overlay.

## Two-ship test

1. Boot two fake ships (e.g. ~zod and ~nec — never reuse a rebuilt
   fake's name for cross-ship work) with distinct HTTP ports.
2. On each: `|mount %base`, copy `urbit/trunk/*` into a new desk (or
   overlay onto %base for the quick version), `|commit`, then
   `|rein %trunk-desk [& %trunk]` / `|start %trunk`.
3. Run two Talon desktops with separate config dirs:
   `XDG_CONFIG_HOME=/tmp/talon-a ./gradlew :composeApp:run` (and -b).
4. Log each into its ship, open a DM between them, type `/call`.
5. Answer on the other side; both machines on the same LAN should go
   live over host candidates. Grep both logs for `Trunk metric`.

## What failure teaches

- Ring arrives but no offer → check eyre poke of `%trunk-action`
  (mark file json grab) on the caller's ship.
- Offer arrives, never live → Tier 0 insufficiency on this network;
  that's a *finding*, not a bug — note the NAT shapes involved.
- `unknown/unreachable` reject → peer has no %trunk running.

## v0 results (2026-08-25, ~nec + ~feb on localhost)

The E2E harness (`TrunkCallE2ETest`, opt-in via `TRUNK_E2E=1`) passed:
ring→incoming **110ms**, gather **286ms**, media live ~**300ms** after
accept, hangup propagated. Warm-ames localhost numbers — the WAN rerun
against real ships is the next measurement. Two wire bugs found and
fixed on the way: enjs `+ship` drops the leading `~` (agent now emits
`(scot %p)`), and eyre poke nacks are async + easy to silently drop
(controller now logs channel errors).

## v1 (2026-08-25, this branch)

- **ICE distribution**: `%trunk` stores an advertised server list
  (`[%set-ice …]` poke, `/x/ice` scry). Clients fetch it at startup
  and hand it to the engine — no app configuration.
- **Sidecar**: `sidecar/docker-compose.yml` — coturn for STUN (Tier 1)
  + TURN relay (Tier 2). Galène joins at v2 for party-line SFU rooms.
- **Android engine**: libwebrtc (getstream build), mic-permission
  gate, MODE_IN_COMMUNICATION routing. `isCallsSupported` now true on
  Android + desktop.
- **Real call UI**: full-screen ring (answer/decline), in-call top
  banner with mute + duration, call button in the DM header, `/call`.
- **Resilience, E2E-proven**: a dead STUN server degrades (8s gather
  cap, partial candidates) instead of breaking; answering before the
  offer lands now waits for it instead of no-oping. The E2E runs the
  dead-STUN chaos path: ~nec advertises `stun:localhost:3478` with
  nothing listening — leave it that way, it's a regression test.

## v2 — party lines (2026-08-26, this branch)

Multi-party audio, host-centered per design D5. Validated end to end
against two fake ships plus a real Galène: `PartyLineE2ETest` has both
ships publishing to the SFU ~250ms after the host opens the line, with
a correct roster on both sides and clean leave propagation.

- **Tickets minted in Hoon.** `lib/trunk-jwt.hoon` signs Galène's
  HS256 JWTs on-ship, so the host authorizes members without any
  server round trip. The two byte-order conventions bite here and are
  documented in that file: `base64:mimes:html` reads octs LSB-first,
  `hmac-sha256l:hmac:crypto` reads them MSB-first and returns a
  big-endian atom. Get it backwards and you get a perfect-looking
  token that fails every signature check.
- **One Galène group, rooms as subgroups.** The sidecar configures a
  single `talon` group with `auto-subgroups`; each room is
  `talon/<host>-<room>`, created on first join. Opening a party line
  needs no server-side change, and each ticket's `aud` scopes it to
  exactly one room.
- **Membership is the whole check.** `[%ask]` from a non-member or for
  an unknown room is denied by the host's agent, never by the SFU.
- **Discovery.** Opening a room announces it to every member, so
  joining is an invitation rather than a guess (`/x/lines`).
- **Client.** `PartyLine.kt` speaks Galène's WebSocket protocol over
  the shared Ktor client; `PeerLink` is the trickling, one-directional
  per-stream media primitive (desktop + Android impls).

Galène hands every client its own TURN credentials on join, so party
lines need no ICE config from the ship at all — coturn stays for 1:1.

## Known gaps

- `CallEngine` (1:1) and `PeerLink` (SFU) overlap ~60% per platform.
  Folding the former onto the latter is the obvious cleanup, deferred
  deliberately while the 1:1 path is in an RC under test; its E2E is
  the regression net for that refactor.
- No party-line UI on iOS (`isCallsSupported` is false there).
- The SFU sees plaintext audio, exactly as the host's ship already
  sees the group's messages. Host-blind party lines would need
  insertable-streams E2EE — a v3+ concern with real key-rotation
  complexity on member leave.

## Next

Split desk + sidecar into the `trunkline` repo. WAN metrics against
real ships. Then v3 telephony polish: CallKit / ConnectionService,
APNs VoIP via user relays, ICE restart on network change.
