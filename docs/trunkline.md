# Trunkline — calls and party lines over Urbit

1:1 voice calls and multi-party "party lines" between ships. WebRTC
carries the audio; ames carries the signaling, so a call is addressed
to a `@p` and authenticated by the network rather than by a phone
number or an account. Design rationale lives in the Trunkline design
doc; this file is how it actually fits together and how to run it.

## Architecture

```
===============================================================
 1:1 CALL          signaling rides ames; media never does
===============================================================

  CALLER DEVICE                              CALLEE DEVICE
  +----------------------+           +----------------------+
  | Talon                |           | Talon                |
  |  CallController      |           |  CallController      |
  |  CallEngine(libwebrtc)           |  CallEngine          |
  +----------+-----------+           +-----------+----------+
             |                                   ^
   (0) scry /x/ice                        (3) eyre SSE fact
       -> STUN/TURN this ship                  on /calls
          advertises                        [%recv ~zod sig]
             |                                   |
   (1) eyre poke                                 |
       %trunk-action                             |
       [%send ~nec sig]                          |
             v                                   |
  +----------------------+           +-----------+----------+
  | ~zod   DESK: %trunk  |    (2)    | ~nec   DESK: %trunk  |
  |   app/trunk.hoon     |   ames    |   app/trunk.hoon     |
  |   sur/ lib/ mar/     +---------->+                      |
  +----------------------+  mark:    +----------------------+
                          %trunk-signal
                    [%ring][%offer sdp fpr]
                    [%accept][%reject][%hangup]

  MEDIA - direct, the agent never sees it
     caller <====== DTLS-SRTP (ICE) ======> callee
       Tier 0  host candidates (same LAN, public IPv6)
       Tier 1  reflexive addr via sidecar STUN
       Tier 2  relayed by coturn  <- the relay sees ciphertext
                                     only, so the call stays
                                     end-to-end encrypted
```

```
===============================================================
 PARTY LINE        host's ship authorizes; host's SFU mixes
===============================================================

  HOST DEVICE                                MEMBER DEVICE
  +----------------------+           +----------------------+
  | Talon                |           | Talon                |
  |  PartyLineHost       |           |  PartyLine           |
  +----------+-----------+           +-----------+----------+
        |    |                                   ^
   (1) scry %groups  DESK: groups                 |
       /v2/groups/<flag>                          |
       -> roster = who may join                   |
        |                                         |
   (2) eyre poke %trunk-action            (6) eyre SSE fact
       [%open-room name title members]           /calls
        v                                   [%ticket loc tok]
  +----------------------+           +-----------+----------+
  | ~zod   DESK: %trunk  |    (3)    | ~nec   DESK: %trunk  |
  |  app/trunk.hoon      |   ames    |  app/trunk.hoon      |
  |  lib/trunk-jwt.hoon  +---------->+                      |
  |                      | %trunk-room                      |
  |                      | [%announce name title]           |
  |                      |           |                      |
  |                      +<----------+ (4) [%ask name]      |
  |  checks membership   |           |                      |
  |  mints HS256 JWT     +---------->+ (5) [%grant ticket]  |
  +----------------------+           +----------------------+
     (host joining its own room skips 3-5 entirely)

  MEDIA - a star, not a mesh
     host   --- GET <loc>/.status, then wss ---> +-----------+
     member --- GET <loc>/.status, then wss ---> |  Galene   |
                one outbound conn each,          |  SFU      |
                so NAT never enters              | (sidecar) |
                                                 +-----------+
     ticket = JWT scoped to  talon/<host>-<room>, 6h expiry
     Galene issues its own TURN creds on join -> no ICE config
     SFU terminates DTLS -> host's machine hears plaintext,
     the same trust boundary as it already storing the posts
```

**Desks.** `%trunk` is the desk this project adds — agent, types, marks,
and the JWT lib — and it must be installed on **both** ships; a peer
without it nacks the relay and the caller sees "unreachable". `groups`
is Tlon's existing desk, read-only here and only on the host side, to
answer "who is allowed on this line".

**Transports.** Device to its own ship is always eyre: pokes up, SSE
facts down. Ship to ship is always ames, so `src` is cryptographically
the sending ship and a signal cannot lie about who it is from. Galène
and coturn are plain Unix daemons on the ship's host machine — not
desks, not part of Urbit.

**The asymmetry worth remembering.** In 1:1 the agent is a dumb relay
and the two devices negotiate directly, so nothing in the middle can
hear the call. In a party line the host's ship is a real authority: it
decides membership and signs the tickets, and its SFU necessarily hears
the audio in order to mix it.

## What the sidecar is actually for

Two services, needed at different times, both optional:

- **Galène (SFU)** — required to *host* a party line. There is no
  peer-to-peer fallback for group audio; without it, party lines do not
  exist. Joining someone else's line needs nothing locally.
- **coturn (STUN/TURN)** — only for 1:1 calls with no direct path.
  Same-LAN calls never touch it (Tier 0 finds a route). Phone-on-cellular
  to desktop-at-home usually does need it: carrier-grade NAT on one side,
  a home router on the other. Expect roughly 10-20% of real-world pairs,
  disproportionately mobile.

Galène ships its own TURN server and hands credentials to every joining
client, which is why party lines need no ICE configuration at all. That
raises the obvious question of why coturn exists rather than relaying
1:1 calls through Galène too — the answer is that an SFU terminates
DTLS and re-encrypts per listener, so it would hear the call. A TURN
relay only forwards packets it cannot read. Keeping coturn is what lets
1:1 calls stay end-to-end while party lines are honest about the host
hearing them.

One sidecar anywhere between two callers covers that call, so running
one upgrades every call made *to* you.

## Pieces

- `urbit/trunk/` — the `%trunk` desk. `app/trunk.hoon` routes signals
  and mints room tickets; `lib/trunk-jwt.hoon` signs the Galène JWTs;
  `lib/trunk-json.hoon` is the wire's source of truth; `mar/trunk/*`
  are the eyre-facing and ship-to-ship marks. Not self-contained yet:
  installing it needs `default-agent`, `dbug`, `skeleton` and the
  `bill`/`mime`/`json` marks copied from `%base`.
- `call/TrunkWire.kt` — JSON wire, mirrors `lib/trunk-json.hoon`.
- `call/CallController.kt` — 1:1 signaling state machine + metrics.
- `call/CallEngine.kt` + platform engines — the 1:1 media half
  (webrtc-java on desktop, libwebrtc on Android).
- `call/PartyLine.kt` — Galène's WebSocket protocol; `call/PeerLink.kt`
  + platform impls are the per-stream, trickling media primitive.
- `call/PartyLineHost.kt` — maps a channel to `(host, room)` so every
  member derives the same line with no shared state.
- UI: call button and `/call` in a DM, party-line button in a group
  channel, `CallOverlay` (ring / in-call banner) and `PartyLineBar`.
- `sidecar/` — compose file and setup for coturn + Galène.
- `gen/trunk/policy.hoon` — dojo read-out of the call policy, since
  `%trunk` has no UI of its own.

## Who may ring you

`%trunk` carries a ship-level call policy. It is enforced in the agent,
not in Talon, and that placement is the whole point: a client-side
filter still lets the poke land, still rings the ship's *other*
clients, and does nothing at all for a second app sharing the agent.

```
+$  call-mode  ?(%open %allow)
+$  policy  [mode=call-mode allow=(set ship) block=(set ship)]
```

- `%open` — anyone may ring, except ships in `block`.
- `%allow` — only ships in `allow` may ring.
- `block` always applies, and outranks `allow`: blocking a ship also
  drops it from the allow list, so the two can never disagree.
- A block also refuses party-line tickets and drops room announcements
  from that ship.

A refused caller gets **silence**, not a rejection. A rejection would
confirm to a stranger that the ship is live and filtering, and would
tell a blocked caller they were blocked; instead their ring watchdog
times out, which is what an offline ship looks like.

Upgrading never changes behaviour: an existing ship migrates to
`%open` with empty lists, exactly how it behaved before.

Deliberately, the agent knows nothing about `%contacts`. Making
"contacts only" work by scrying Tlon's agent would make shared
infrastructure depend on the Tlon suite; a client that wants that
behaviour keeps the allow set in sync itself.

### Reading and editing it

The policy has no UI in `%trunk` — the desk stays headless so it can be
shared. Talon renders it under Settings → "Who can call you". Outside
Talon:

```dojo
::  read
=dir /=trunk=
+trunk/policy

::  write
:trunk &trunk-action [%set-call-mode %allow]
:trunk &trunk-action [%allow ~zod]
:trunk &trunk-action [%block ~bus]
:trunk &trunk-action [%unblock ~bus]
```

Over HTTP the scry is `/~/scry/trunk/policy.json` (eyre supplies the
`%x` care itself — do not put it in the path).

Every edit echoes the whole policy back on `/calls` as a `%policy`
fact, so a ship's other devices converge without re-scrying.

## Installing the desk

Validated on two fake ships. `|rein` alone is not enough — gall reports
"not running %trunk yet" until the desk has been installed once.

```dojo
|new-desk %trunk
|mount %trunk
|mount %base          :: to borrow the shared libs below
```

Copy `urbit/trunk/{app,sur,lib,mar,desk.bill,sys.kelvin}` into the
mounted desk, then copy from `%base` (the desk is not self-contained):

```
lib/default-agent.hoon  lib/dbug.hoon  lib/skeleton.hoon
mar/bill.hoon  mar/mime.hoon  mar/json.hoon
```

Check `sys.kelvin` matches the ship's zuse — a fake booted from a
recent pill wants `[%zuse 408]`, and a mismatch fails the commit with
no useful message. Then:

```dojo
|commit %trunk
|install our %trunk
```

Point the ship at its sidecar once (see `sidecar/README.md` for the
key):

```dojo
:trunk &trunk-action [%set-ice ~[['stun:host:3478' '' ''] ['turn:host:3478' 'talon' 'PASS']]]
:trunk &trunk-action [%set-sfu ['http://host:8444' 'talon' 'KEY']]
```

Use an address the *other devices* can reach. `localhost` works from a
desktop on the same box and fails from a phone — that mistake costs a
testing session.

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
