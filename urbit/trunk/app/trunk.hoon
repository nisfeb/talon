::  %trunk: call signaling router. See sur/trunk.hoon for the shapes.
::
::  Local client (eyre) pokes %trunk-action [%send ship sig]; we relay
::  the sig to the peer ship's %trunk as a %trunk-signal poke. Inbound
::  %trunk-signal pokes from remote ships become [%recv from sig]
::  facts on /calls, which the local client subscribes to.
::
::  Deliberately stateless: call state lives in the clients. The agent
::  only enforces the trust boundary (a %send must come from our own
::  ship; a signal's `from` is the cryptographic src, never a claim).
/-  trunk
/+  default-agent, dbug
|%
+$  versioned-state  [%0 ~]
+$  card  card:agent:gall
--
%-  agent:dbug
=|  versioned-state
=*  state  -
^-  agent:gall
|_  =bowl:gall
+*  this  .
    def   ~(. (default-agent this %.n) bowl)
::
++  on-init   `this
++  on-save   !>(state)
++  on-load   |=(vase `this)
::
++  on-poke
  |=  [=mark =vase]
  ^-  (quip card _this)
  ?+    mark  (on-poke:def mark vase)
      %trunk-action
    ?>  =(src.bowl our.bowl)
    =/  act  !<(action:trunk vase)
    :_  this
    :~  :*  %pass  /relay/(scot %p ship.act)
            %agent  [ship.act %trunk]
            %poke  %trunk-signal  !>(sig.act)
    ==  ==
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
++  on-peek   on-peek:def
++  on-arvo   on-arvo:def
++  on-leave  |=(path `this)
++  on-fail   on-fail:def
--
