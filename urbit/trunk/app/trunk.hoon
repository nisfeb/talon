::  %trunk: call signaling router + ICE advertisement. See sur/trunk.
::
::  Local client (eyre) pokes %trunk-action: [%send ship sig] relays
::  the sig to the peer ship's %trunk as a %trunk-signal poke;
::  [%set-ice servers] stores the ICE servers this ship advertises
::  (its sidecar's STUN/TURN endpoints). Inbound %trunk-signal pokes
::  from remote ships become [%recv from sig] facts on /calls.
::  Clients read ICE config at /x/ice (mark %trunk-ice → json).
::
::  Signaling stays stateless: call state lives in the clients. The
::  agent enforces the trust boundary (local-only actions; a signal's
::  `from` is the cryptographic src, never a claim).
/-  trunk
/+  default-agent, dbug
|%
+$  versioned-state
  $%  state-0
      state-1
  ==
+$  state-0  [%0 ~]
+$  state-1  [%1 ice=(list ice-server:trunk)]
+$  card  card:agent:gall
--
%-  agent:dbug
=|  state-1
=*  state  -
^-  agent:gall
|_  =bowl:gall
+*  this  .
    def   ~(. (default-agent this %.n) bowl)
::
++  on-init  `this
++  on-save  !>(state)
++  on-load
  |=  old-vase=vase
  ^-  (quip card _this)
  =/  old  !<(versioned-state old-vase)
  ?-  -.old
    %0  `this(state [%1 ~])
    %1  `this(state old)
  ==
::
++  on-poke
  |=  [=mark =vase]
  ^-  (quip card _this)
  ?+    mark  (on-poke:def mark vase)
      %trunk-action
    ?>  =(src.bowl our.bowl)
    =/  act  !<(action:trunk vase)
    ?-    -.act
        %set-ice
      `this(ice.state servers.act)
    ::
        %send
      :_  this
      :~  :*  %pass  /relay/(scot %p ship.act)
              %agent  [ship.act %trunk]
              %poke  %trunk-signal  !>(sig.act)
      ==  ==
    ==
  ::
      %trunk-signal
    =/  =sig:trunk  !<(sig:trunk vase)
    :_  this
    :~  [%give %fact ~[/calls] %trunk-update !>(`update:trunk`[%recv src.bowl sig])]
    ==
  ==
::
++  on-watch
  |=  =path
  ^-  (quip card _this)
  ?>  =(src.bowl our.bowl)
  ?+  path  (on-watch:def path)
    [%calls ~]  `this
  ==
::
++  on-peek
  |=  =path
  ^-  (unit (unit cage))
  ?+  path  (on-peek:def path)
    [%x %ice ~]  ``trunk-ice+!>(ice.state)
  ==
::
++  on-agent
  |=  [=wire =sign:agent:gall]
  ^-  (quip card _this)
  ?+    -.sign  (on-agent:def wire sign)
      %poke-ack
    ::  a nacked relay means the peer has no %trunk (or rejected us).
    ::  surface it so the caller's UI can stop ringing.
    ?~  p.sign  `this
    ?+    wire  `this
        [%relay @ ~]
      =/  peer  (slav %p i.t.wire)
      :_  this
      :~  :*  %give  %fact  ~[/calls]  %trunk-update
              !>(`update:trunk`[%recv peer [%reject 'unknown' 'unreachable']])
      ==  ==
    ==
  ==
::
++  on-arvo   on-arvo:def
++  on-leave  |=(path `this)
++  on-fail   on-fail:def
--
