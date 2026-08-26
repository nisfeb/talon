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
::  a party line we host. members may join; anyone else is denied.
+$  room  [title=@t members=(set ship)]
::  authorization to join one room: where it is, and a short-lived
::  token scoped to exactly that room.
+$  ticket  [name=@t location=@t token=@t]
::  local client -> own agent
+$  action
  $%  [%send =ship =sig]
      [%set-ice servers=(list ice-server)]
      [%set-sfu =sfu-config]
      [%open-room name=@t title=@t members=(set ship)]
      [%close-room name=@t]
      [%join-room host=ship name=@t]
  ==
::  ship-to-ship room negotiation
+$  room-sig
  $%  [%ask name=@t]
      [%grant =ticket]
      [%deny name=@t why=@t]
      ::  the host telling a member a line is open / gone, so joining
      ::  is an invitation rather than a guess
      [%announce name=@t title=@t]
      [%shut name=@t]
  ==
::  party lines other ships have invited us to: [host name] -> title
+$  lines  (map [=ship name=@t] @t)
::  agent -> local client, on /calls
+$  update
  $%  [%recv from=ship =sig]
      [%ticket from=ship =ticket]
      [%denied from=ship name=@t why=@t]
      [%open from=ship name=@t title=@t]
      [%shut from=ship name=@t]
  ==
--
