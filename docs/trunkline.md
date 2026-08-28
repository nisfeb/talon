# Trunkline — calls and party lines over Urbit

**The `%trunk` desk and the sidecar now live in their own repository:
https://github.com/gwbtc/trunk** — the agent does signalling and
nothing else, so it has no reason to sit inside one client's tree.
Protocol, install and operating notes are there.

This file keeps the half that is Talon's: the client, and the things
that only make sense with the app in front of you.

## The wire is mirrored by hand

`TrunkWire.kt` mirrors `lib/trunk-json.hoon` in the trunk repo. There
is no generator and no shared schema, so the two can drift — and every
time they have, the symptom was a silent no-op rather than an error: a
poke rejected with `[%key 'keep-sfu']`, an action gall could not cast,
a switch that did nothing. The E2E suites are what catch it, because
they run against real ships.

If you change one side, change the other in the same sitting, and run
`TRUNK_E2E=1 ./gradlew :composeApp:desktopTest`.

## Deploying a desk change

The tests do not install the desk; a ship keeps running whatever it was
last given. A client newer than the ship fails as a dead control, which
is exactly as confusing as it sounds — check the deployed desk matches
before concluding the client is broken:

```bash
ssh <host> 'md5sum $PIER/trunk/app/trunk.hoon'
md5sum <trunk-repo>/app/trunk.hoon
```

## Automated checks

Faster than driving the UI, and they run against real ships:

```
TRUNK_E2E=1 TRUNK_SFU_KEY=<key> TRUNK_SFU=http://<lan-ip>:8444 \
  ./gradlew :composeApp:desktopTest --tests '*E2E*'
```

- `TrunkCallE2ETest` — a 1:1 call end to end, with metrics.
- `PartyLineE2ETest` — two ships on one line via a real Galène.
- `PartyLineUiPathTest` — the path the UI takes (needs `TRUNK_CHANNEL`).
- `StuckRingE2ETest` — a caller that vanishes mid-ring must not leave
  the callee wedged.
- `CallPolicyE2ETest` — open rings, blocked is silent, allow-mode
  refuses a ship not on the list and rings one that is, and a block
  outranks an allow entry. Rings land on ship A only, so pointing A at
  an unused ship keeps the noise off a real device.
- `UiPrefSyncE2ETest` — a preference set on one device reaches another.
- `TrunkFixtureTest` (`TRUNK_FIXTURE=1`) — one-shot: creates a group
  with a chat channel, invites the second ship, and points the host at
  its SFU. Prints the channel to open in the app.

## Two-ship test by hand

1. Boot two fake ships (never reuse a rebuilt fake's name for
   cross-ship work) with distinct HTTP ports.
2. Install the desk on **both** — a peer without it nacks the relay.
3. Run two Talon desktops with separate config dirs:
   `XDG_CONFIG_HOME=/tmp/talon-a ./gradlew :composeApp:run` (and -b).
4. Log each into its ship, open a DM between them, tap the call icon
   (or type `/call`).
5. Answer on the other side; both machines on the same LAN should go
   live over host candidates. Grep both logs for `Trunk metric`.

## What failure teaches

- Ring arrives but no offer → check the eyre poke of `%trunk-action`
  (mark file json grab) on the caller's ship.
- Offer arrives, never live → Tier 0 insufficiency on this network;
  that's a *finding*, not a bug — note the NAT shapes involved.
- `unknown`/`unreachable` reject → the peer has no `%trunk` running.
- **"busy" on every call** → first: is another client logged into the
  same ship? A ship is one identity across many devices and they all
  receive the ring. A busy device used to reply "busy" for the whole
  ship, cancelling a call another device was ringing for — a test
  harness left connected did exactly this for a day. Busy devices now
  stay silent. Then: the far device is stuck in a ringing or
  active state. It used to stick forever; a ring now expires after 45s.
  The state is in memory, so a device wedged by an older build stays
  wedged until the process restarts.
- **Party line fails to connect** → almost always the SFU address:
  check what `location` a ticket carries (`join-room` and read the
  fact) rather than what you think you configured.
- **"duplicate client id"** in Galène's log → a client reused its id
  across connections; each connection needs a fresh one.
- Denials are deliberate and specific: `no such room` (host never
  opened it), `not a member` (not on the group roster), `no sfu
  configured` (host never ran `%set-sfu`).

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

## Security review of the room path (2026-08-26)

Auditing the new trust boundaries turned up one real vulnerability,
fixed in the same pass:

- **Unsolicited `%grant` = microphone hijack.** The agent accepted a
  ticket from *any* ship, and the client auto-joins on ticket — so a
  hostile ship could push a grant naming its own SFU and the victim
  would publish its microphone there. The agent now records outstanding
  `%ask`s and accepts a `%grant`/`%deny` only as the answer to one.
  Verified: after a completed join `+dbug %state` shows `asked={}`,
  so any later grant fails the check.
- **Invitation list is remote-controlled**, so `%announce` is capped
  (`invite-cap`) rather than growing without bound.
- Membership is checked host-side at mint time, and `sub` is always the
  *asking* ship — a member cannot mint a ticket for anyone else.
- Ticket TTL is 6h with no revocation: a member removed from a group
  keeps access until expiry. Rotating the SFU key is the only immediate
  revocation. Documented ceiling, not a v2 fix.

## Known gaps

- `CallEngine` (1:1) and `PeerLink` (SFU) overlap ~60% per platform.
  Folding the former onto the latter is the obvious cleanup, deferred
  deliberately while the 1:1 path is in an RC under test; its E2E is
  the regression net for that refactor.
- Galène keeps a subgroup's roster after the last client drops, so a
  member who left can linger in everyone's list for a while. Talon now
  closes the socket cleanly (and before the slow native media
  teardown, which used to leave Galène with an abrupt EOF), but the
  reaping delay is the server's. `PartyLineE2ETest` sidesteps it by
  using a fresh room name per run.
- No party-line UI on iOS (`isCallsSupported` is false there), which
  also means no call-policy editor there — an iOS-only user has to set
  it from another device or the dojo.
- The SFU sees plaintext audio, exactly as the host's ship already
  sees the group's messages. Host-blind party lines would need
  insertable-streams E2EE — a v3+ concern with real key-rotation
  complexity on member leave.

## Testing on real hardware

The fake ships already have `%trunk`, so a phone + desktop session
needs no ship work. Ports are on the LAN, so use the machine's LAN
address (not localhost) from the phone.

**1:1 calls (no sidecar needed).**

1. Desktop Talon: log into `~feb` at `http://<lan-ip>:8082`.
2. Phone (rc2 APK): log into `~nec` at `http://<lan-ip>:8081`.
3. Open the DM between them, tap the call icon in the header.
4. Expect Tier 0 (host candidates) on the same LAN. Grep the desktop
   log (`~/.config/talon/log/talon.log`) and Android logcat for
   `Trunk metric` — those are the first real cross-device numbers.
5. Then put the phone on cellular and repeat. That is the first
   genuine Tier 2 test, and it needs the sidecar's TURN
   (`sidecar/README.md`) plus a `%set-ice` poke pointing at a
   publicly-reachable address.

**Party lines (needs a Galène).** Bring one up per `sidecar/README.md`,
then from each ship's dojo point it at the SFU with `%set-sfu`. Open a
group channel on a group `~nec` hosts and tap the party-line icon:
the host opens the line, everyone else joins it. The strip under the
channel header shows who is on.

Both flows are already covered headlessly by `TrunkCallE2ETest` and
`PartyLineE2ETest`, so a failure on device is a platform/network
finding rather than a protocol one — worth capturing the log either
way.

## Next

Split desk + sidecar into the `trunkline` repo. WAN metrics against
real ships. Then v3 telephony polish: CallKit / ConnectionService,
APNs VoIP via user relays, ICE restart on network change.
