::  trunk: call signaling over ames.
::
::  1:1 calls are peer-to-peer: the agent only routes opaque SDP.
::  Party lines are host-centered: the group host's sidecar runs an SFU
::  (Galène), and the host's %trunk mints per-member, per-room join
::  tickets — the trust boundary the host already has for the group's
::  messages, extended to its audio.
|%
::  one signaling message. ids are client-minted opaque strings (uuid).
::  sdp is a complete (non-trickle) session description; fpr is the
::  DTLS certificate fingerprint the client pins the media session to.
+$  sig
  $%  [%ring id=@t]
      [%offer id=@t sdp=@t fpr=@t]
      [%accept id=@t sdp=@t fpr=@t]
      [%reject id=@t reason=@t]
      [%hangup id=@t]
  ==
::  one ICE server this ship advertises to its clients (the icepond
::  role): a STUN or TURN url plus static credentials (empty for STUN).
+$  ice-server  [url=@t user=@t cred=@t]
::  our sidecar's SFU.
::    base:   origin, e.g. 'http://calls.example.com:8444'
::    group:  the ONE Galène group configured there, e.g. 'talon'.
::            It must have "auto-subgroups": true — every party line is
::            a subgroup created on first join, so hosting a new room
::            needs no server-side configuration.
::    key:    the group's HS256 secret, base64url — the same string as
::            the "k" field of Galène's authKeys entry.
+$  sfu-config  [base=@t group=@t key=@t]
::  who may ring us 1:1. The block set always applies; the mode only
::  decides what happens to everyone who isn't blocked.
::    %open   anyone may ring
::    %allow  only ships in the allow set may ring
::
::  This is ship-level on purpose: %trunk is the chokepoint every
::  client shares, so the policy holds for every device and for apps
::  other than the one that set it. It deliberately knows nothing
::  about %contacts — a client that wants "contacts only" keeps the
::  allow set in sync itself.
+$  call-mode  ?(%open %allow)
+$  policy  [mode=call-mode allow=(set ship) block=(set ship)]
::  a party line we host. members may join; anyone else is denied.
::
::    admins   ships that may reconfigure this room remotely. %trunk
::             has no idea what a Tlon group is — this is just a list
::             the host seeds, so a group's admins can turn the line
::             on or off without owning the host ship.
::    listen   may anonymous listen links be minted for this room?
::             Off by default: a party line is gated by the host's
::             membership list, and a public link deliberately punches
::             through that, so it must be asked for.
::    sfu      which sidecar this room runs on. ~ means "the ship's
::             own", which is the common case. A group that would
::             rather not route its audio through the host's sidecar
::             sets its own here — the host still mints the tickets,
::             but against the group's chosen server.
+$  room
  $:  title=@t
      members=(set ship)
      admins=(set ship)
      listen=?
      sfu=(unit sfu-config)
  ==
::  a listen-only link: where to point a browser, and until when.
+$  listen-link  [name=@t url=@t expires=@ud]
::  authorization to join one room: where it is, and a short-lived
::  token scoped to exactly that room.
+$  ticket  [name=@t location=@t token=@t]
::  local client -> own agent
+$  action
  $%  [%send =ship =sig]
      [%set-ice servers=(list ice-server)]
      [%set-sfu =sfu-config]
      [%open-room name=@t title=@t members=(set ship) admins=(set ship)]
      ::  turn anonymous listening on or off for a room we host
      [%set-room-listen name=@t listen=?]
      ::  mint a listen link for a room we host. ttl is in seconds:
      ::  the link is a bearer token and Galene cannot revoke one, so
      ::  the lifetime is the whole security model.
      [%share-room name=@t ttl=@ud]
      ::  ask a REMOTE host to reconfigure a line we are an admin of.
      ::  The host checks that we are actually on its admin list.
      [%configure-room host=ship name=@t open=? listen=? sfu=(unit sfu-config)]
      [%close-room name=@t]
      [%join-room host=ship name=@t]
      [%set-call-mode mode=call-mode]
      [%allow =ship]
      [%unallow =ship]
      [%block =ship]
      [%unblock =ship]
  ==
::  ship-to-ship room negotiation
+$  room-sig
  $%  [%ask name=@t]
      [%grant =ticket]
      [%deny name=@t why=@t]
      ::  the host telling a member a line is open / gone, so joining
      ::  is an invitation rather than a guess
      [%announce name=@t title=@t listen=? sfu-base=@t]
      [%shut name=@t]
      ::  a room admin, over ames, changing what the host hosts
      [%configure name=@t open=? listen=? sfu=(unit sfu-config)]
  ==
::  a line another ship has invited us to. Carries enough for an admin
::  to see the current settings without owning the host ship; never the
::  SFU secret, only the base URL members will connect to anyway.
+$  line  [title=@t listen=? sfu-base=@t]
+$  lines  (map [=ship name=@t] line)
::  agent -> local client, on /calls
+$  update
  $%  [%recv from=ship =sig]
      [%ticket from=ship =ticket]
      [%denied from=ship name=@t why=@t]
      [%open from=ship name=@t =line]
      [%shut from=ship name=@t]
      ::  echoed after every policy change so a ship's other devices
      ::  converge without re-scrying
      [%policy =policy]
      ::  a freshly minted listen link, for the client to share
      [%listen-link =listen-link]
  ==
--
