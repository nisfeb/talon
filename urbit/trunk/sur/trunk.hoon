::  trunk: 1:1 call signaling over ames. v0 spike — the agent is a
::  near-stateless router: local actions relay to the peer's %trunk;
::  remote signals surface as facts on /calls for the local client.
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
::  v1 is static creds; HMAC time-limited creds arrive with the
::  sponsor cascade.
+$  ice-server  [url=@t user=@t cred=@t]
::  local client -> own agent
+$  action
  $%  [%send =ship =sig]
      [%set-ice servers=(list ice-server)]
  ==
::  agent -> local client subscription fact
+$  update  [%recv from=ship =sig]
--
