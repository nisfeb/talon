::  ICE server list scried by clients at /x/ice — the ship's
::  advertised STUN/TURN endpoints (its sidecar, or its sponsor's).
/-  trunk
/+  trunk-json
|_  servers=(list ice-server:trunk)
++  grab
  |%
  ++  noun  (list ice-server:trunk)
  --
++  grow
  |%
  ++  noun  servers
  ++  json  (ice-to-json:trunk-json servers)
  --
++  grad  %noun
--
