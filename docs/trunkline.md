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

## Next (per design roadmap)

v1: Galène sidecar (STUN echo + TURN + auth tokens), tiers wired into
the engine's RTCConfiguration, Android engine, real call UI. At v1 the
desk + sidecar split into their own repo (`trunkline`) so other Urbit
clients can adopt the protocol.
