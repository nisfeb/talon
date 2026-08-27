::  %trunk: call signaling router, ICE advertisement, party-line rooms.
::
::  1:1 (v1): local client pokes [%send ship sig]; we relay to the
::  peer's %trunk as a %trunk-signal poke. Inbound signals become
::  [%recv from sig] facts on /calls. Call state lives in the clients.
::
::  Party lines (v2): the host's ship owns the room. A member's client
::  pokes [%join-room host name]; that relays to the host's %trunk as
::  %trunk-room [%ask name]; the host checks membership and mints a
::  short-lived, room-scoped Galène ticket, which comes back as
::  [%grant ticket] and surfaces to the member's client on /calls.
::
::  The agent never sees media and never parses SDP. Its whole job is
::  the trust boundary: local-only actions, and a signal's `from` is
::  the cryptographic ames src, never a claim in the payload.
/-  trunk
/+  default-agent, dbug, trunk-jwt
|%
+$  versioned-state
  $%  state-0
      state-1
      state-2
      state-3
      state-4
      state-5
  ==
+$  state-0  [%0 ~]
+$  state-1  [%1 ice=(list ice-server:trunk)]
::  %2 predates room announcements
+$  state-2
  $:  %2
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t room:trunk)
  ==
::  %3 accepted a %grant from any ship — see +on-poke
+$  state-3
  $:  %3
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t room:trunk)
      known=lines:trunk
  ==
+$  state-4
  $:  %4
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t room:trunk)
      ::  lines other ships have invited us to
      known=lines:trunk
      ::  rooms we have an outstanding %ask for. A ticket names an SFU
      ::  our client will publish its microphone to, so we only accept
      ::  one that answers a request we actually made.
      asked=(set [=ship name=@t])
  ==
::  %4 rang for any ship that asked — see +may-ring
+$  state-5
  $:  %5
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t room:trunk)
      known=lines:trunk
      asked=(set [=ship name=@t])
      ::  who may ring us. Enforced here rather than in the client:
      ::  a client-side filter still lets the poke land, still rings
      ::  a ship's other clients, and stops nothing for an app that
      ::  shares this agent.
      pol=policy:trunk
  ==
+$  card  card:agent:gall
::  how long a minted ticket stays valid. Long enough for a call that
::  outlasts a conversation, short enough that a removed member loses
::  access without a key rotation.
++  ticket-ttl  ^~((div ~h6 ~s1))
::  how many party-line invitations we will remember from the network.
++  invite-cap  256
::  the policy a ship starts with: ring for anyone, block nobody.
++  open-policy  `policy:trunk`[%open ~ ~]
--
%-  agent:dbug
=|  state-5
=*  state  -
^-  agent:gall
=<
|_  =bowl:gall
+*  this  .
    def   ~(. (default-agent this %.n) bowl)
    hc    ~(. +> bowl)
::
++  on-init  `this
++  on-save  !>(state)
++  on-load
  |=  old-vase=vase
  ^-  (quip card _this)
  =/  old  !<(versioned-state old-vase)
  ?-  -.old
    %0  `this(state [%5 ~ ['' '' ''] ~ ~ ~ open-policy])
    %1  `this(state [%5 ice.old ['' '' ''] ~ ~ ~ open-policy])
    %2  `this(state [%5 ice.old sfu.old hosted.old ~ ~ open-policy])
    %3  `this(state [%5 ice.old sfu.old hosted.old known.old ~ open-policy])
  ::  upgrading must not silently start refusing calls, so an existing
  ::  ship keeps ringing for anyone until its owner says otherwise.
    %4  `this(state [%5 ice.old sfu.old hosted.old known.old asked.old open-policy])
    %5  `this(state old)
  ==
::
++  on-poke
  |=  [=mark =vase]
  ^-  (quip card _this)
  ?+    mark  (on-poke:def mark vase)
  ::
  ::  local client actions
      %trunk-action
    ?>  =(src.bowl our.bowl)
    =/  act  !<(action:trunk vase)
    ?-    -.act
        %set-ice  `this(ice.state servers.act)
        %set-sfu  `this(sfu.state sfu-config.act)
    ::
    ::  policy edits. Each echoes the whole policy back on /calls so a
    ::  ship's other devices converge without re-scrying.
        %set-call-mode
      =/  new  pol.state(mode mode.act)
      :-  ~[(fact:hc [%policy new])]  this(pol.state new)
    ::
        %allow
      =/  new  pol.state(allow (~(put in allow.pol.state) ship.act))
      :-  ~[(fact:hc [%policy new])]  this(pol.state new)
    ::
        %unallow
      =/  new  pol.state(allow (~(del in allow.pol.state) ship.act))
      :-  ~[(fact:hc [%policy new])]  this(pol.state new)
    ::
    ::  blocking also drops any allow entry, so the two lists can never
    ::  disagree about one ship.
        %block
      =/  new
        %=  pol.state
          block  (~(put in block.pol.state) ship.act)
          allow  (~(del in allow.pol.state) ship.act)
        ==
      :-  ~[(fact:hc [%policy new])]  this(pol.state new)
    ::
        %unblock
      =/  new  pol.state(block (~(del in block.pol.state) ship.act))
      :-  ~[(fact:hc [%policy new])]  this(pol.state new)
    ::
        %open-room
      :-  (announce:hc name.act title.act members.act %.y)
      this(hosted.state (~(put by hosted.state) name.act [title.act members.act]))
    ::
        %close-room
      =/  got  (~(get by hosted.state) name.act)
      :-  ?~(got ~ (announce:hc name.act title.u.got members.u.got %.n))
      this(hosted.state (~(del by hosted.state) name.act))
    ::
        %send
      :_  this
      :~  :*  %pass  /relay/(scot %p ship.act)
              %agent  [ship.act %trunk]
              %poke  %trunk-signal  !>(sig.act)
      ==  ==
    ::
        %join-room
      ::  hosting it ourselves? mint straight away, no round trip.
      ?:  =(host.act our.bowl)
        :_  this  (grant-cards:hc our.bowl name.act)
      :-  :~  :*  %pass  /room/(scot %p host.act)
                  %agent  [host.act %trunk]
                  %poke  %trunk-room  !>(`room-sig:trunk`[%ask name.act])
          ==  ==
      this(asked.state (~(put in asked.state) [host.act name.act]))
    ==
  ::
  ::  1:1 signal from a peer ship
      %trunk-signal
    =/  =sig:trunk  !<(sig:trunk vase)
    ::  A block is total: nothing from that ship reaches our clients.
    ?:  (~(has in block.pol.state) src.bowl)
      %-  (slog leaf+"trunk: dropped {<-.sig>} from blocked {<src.bowl>}" ~)
      `this
    ::  The mode gates rings only. The rest of an exchange — offer,
    ::  accept, hangup — has to pass even from a ship the mode would
    ::  refuse, because WE may have called THEM: gating those on the
    ::  allow list would break every outgoing call to someone not
    ::  already on it, by dropping our own callee's answer. Safe
    ::  because a client ignores any signal whose call id it does not
    ::  recognise, so a stranger's stray offer goes nowhere.
    ::
    ::  A refused caller is answered with silence, not a rejection: a
    ::  rejection confirms the ship is live and filtering, and tells a
    ::  blocked caller they were blocked. Their ring watchdog gives up
    ::  on its own, which looks the same as an offline ship.
    ?:  ?&  ?=(%ring -.sig)
            !(may-ring:hc src.bowl)
        ==
      %-  (slog leaf+"trunk: refused ring from {<src.bowl>}" ~)
      `this
    :_  this
    :~  [%give %fact ~[/calls] %trunk-update !>(`update:trunk`[%recv src.bowl sig])]
    ==
  ::
  ::  room negotiation with a peer ship
      %trunk-room
    =/  msg  !<(room-sig:trunk vase)
    ?-    -.msg
        %ask    :_(this (grant-cards:hc src.bowl name.msg))
    ::
        %announce
      ::  an invitation from anyone is fine (it is just a name), but
      ::  the list is remote-controlled, so it does not grow forever.
      ?:  (~(has in block.pol.state) src.bowl)  `this
      ?:  (gth ~(wyt by known.state) invite-cap)  `this
      :-  ~[(fact:hc [%open src.bowl name.msg title.msg])]
      this(known.state (~(put by known.state) [src.bowl name.msg] title.msg))
    ::
        %shut
      :-  ~[(fact:hc [%shut src.bowl name.msg])]
      this(known.state (~(del by known.state) [src.bowl name.msg]))
    ::
    ::  A ticket names an SFU our client will publish its microphone
    ::  to, so an unsolicited one is a microphone-hijack attempt: only
    ::  accept an answer to a request we actually made.
        %grant
      ?.  (~(has in asked.state) [src.bowl name.ticket.msg])
        %-  (slog leaf+"trunk: unsolicited grant from {<src.bowl>}" ~)
        `this
      :-  ~[(fact:hc [%ticket src.bowl ticket.msg])]
      this(asked.state (~(del in asked.state) [src.bowl name.ticket.msg]))
    ::
        %deny
      ?.  (~(has in asked.state) [src.bowl name.msg])  `this
      :-  ~[(fact:hc [%denied src.bowl name.msg why.msg])]
      this(asked.state (~(del in asked.state) [src.bowl name.msg]))
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
    [%x %ice ~]    ``trunk-ice+!>(ice.state)
    [%x %rooms ~]  ``noun+!>(hosted.state)
    [%x %lines ~]   ``trunk-lines+!>(known.state)
    [%x %policy ~]  ``trunk-policy+!>(pol.state)
  ==
::
++  on-agent
  |=  [=wire =sign:agent:gall]
  ^-  (quip card _this)
  ?+    -.sign  (on-agent:def wire sign)
      %poke-ack
    ?~  p.sign  `this
    ?+    wire  `this
        ::  a nacked relay means the peer has no %trunk (or rejected
        ::  us). surface it so the caller's UI stops ringing.
        [%relay @ ~]
      =/  peer  (slav %p i.t.wire)
      :_  this
      ~[(fact:hc [%recv peer [%reject 'unknown' 'unreachable']])]
    ::
        [%room @ ~]
      =/  peer  (slav %p i.t.wire)
      :_  this
      ~[(fact:hc [%denied peer '' 'host unreachable'])]
    ==
  ==
::
++  on-arvo   on-arvo:def
++  on-leave  |=(path `this)
++  on-fail   on-fail:def
--
::  helper core: cards the agent hands back. Kept out of the agent
::  core because agent:gall admits exactly its ten arms.
::
|_  =bowl:gall
::
::  +fact: one update to our local client.
::
++  fact
  |=  =update:trunk
  ^-  card
  [%give %fact ~[/calls] %trunk-update !>(update)]
::
::  +may-ring: may `who` ring us 1:1? Our own ship always may — that
::  is our other devices, not a stranger.
::
++  may-ring
  |=  who=ship
  ^-  ?
  ?:  =(who our.bowl)  %.y
  ?:  (~(has in block.pol.state) who)  %.n
  ?-  mode.pol.state
    %open   %.y
    %allow  (~(has in allow.pol.state) who)
  ==
::
::  +grant-cards: authorize (or refuse) `who` for the room `name` we
::  host. Membership is the whole check — a ticket is only ever minted
::  for a ship the host explicitly listed.
::
++  grant-cards
  |=  [who=ship name=@t]
  ^-  (list card)
  ::  a block outranks membership: being on the list is not a way
  ::  around having been blocked.
  ?:  (~(has in block.pol.state) who)
    (reply who [%deny name 'not a member'])
  =/  got  (~(get by hosted.state) name)
  ?~  got
    (reply who [%deny name 'no such room'])
  ?.  ?|  =(who our.bowl)
          (~(has in members.u.got) who)
      ==
    (reply who [%deny name 'not a member'])
  ?:  =('' key.sfu.state)
    (reply who [%deny name 'no sfu configured'])
  ::  every room is a subgroup of the one configured Galène group, so
  ::  opening a room needs no server-side config. The subgroup name is
  ::  host-qualified to keep two ships' rooms distinct on a shared SFU.
  =/  sub=@t
    (rap 3 ~[(rsh [3 1] (scot %p our.bowl)) '-' name])
  =/  loc=@t
    (rap 3 ~[base.sfu.state '/group/' group.sfu.state '/' sub '/'])
  =/  now-secs  (unix-secs:trunk-jwt now.bowl)
  =/  tok=@t
    %:  mint:trunk-jwt
      key.sfu.state
      (scot %p who)
      loc
      now-secs
      (add now-secs ticket-ttl)
    ==
  (reply who [%grant name loc tok])
::
::  +announce: tell every member a line opened (or closed). The host
::  is always a member of its own line for this purpose; we skip
::  ourselves since our client already knows.
::
++  announce
  |=  [name=@t title=@t members=(set ship) open=?]
  ^-  (list card)
  %+  turn  ~(tap in (~(del in members) our.bowl))
  |=  who=ship
  ^-  card
  :*  %pass  /room/(scot %p who)
      %agent  [who %trunk]
      %poke  %trunk-room
      !>(`room-sig:trunk`?:(open [%announce name title] [%shut name]))
  ==
::
::  +reply: deliver a room-sig to `who` — as a local fact when that's
::  us, over ames otherwise.
::
++  reply
  |=  [who=ship msg=room-sig:trunk]
  ^-  (list card)
  ?:  =(who our.bowl)
    ?-  -.msg
      %grant     ~[(fact [%ticket our.bowl ticket.msg])]
      %deny      ~[(fact [%denied our.bowl name.msg why.msg])]
      %announce  ~[(fact [%open our.bowl name.msg title.msg])]
      %shut      ~[(fact [%shut our.bowl name.msg])]
      %ask       ~
    ==
  :~  :*  %pass  /room/(scot %p who)
          %agent  [who %trunk]
          %poke  %trunk-room  !>(msg)
  ==  ==
--
