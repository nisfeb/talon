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
/+  default-agent, dbug, trunk-jwt, trunk-json
|%
::  Rooms as they were before state-6. Old state versions must pin the
::  shape they were actually saved with — referencing the evolving
::  +$ room:trunk alias silently rewrites history, and every migration
::  then reads the new shape out of an old noun.
+$  old-room  [title=@t members=(set ship)]
::  Lines before %7 were just [host name] -> title.
+$  old-lines  (map [=ship name=@t] @t)
::  ...and as they were in %6, before the per-room SFU.
+$  old-room-6
  [title=@t members=(set ship) admins=(set ship) listen=?]
+$  versioned-state
  $%  state-0
      state-1
      state-2
      state-3
      state-4
      state-5
      state-6
      state-7
  ==
+$  state-0  [%0 ~]
+$  state-1  [%1 ice=(list ice-server:trunk)]
::  %2 predates room announcements
+$  state-2
  $:  %2
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t old-room)
  ==
::  %3 accepted a %grant from any ship — see +on-poke
+$  state-3
  $:  %3
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t old-room)
      known=old-lines
  ==
+$  state-4
  $:  %4
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t old-room)
      ::  lines other ships have invited us to
      known=old-lines
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
      hosted=(map @t old-room)
      known=old-lines
      asked=(set [=ship name=@t])
      ::  who may ring us. Enforced here rather than in the client:
      ::  a client-side filter still lets the poke land, still rings
      ::  a ship's other clients, and stops nothing for an app that
      ::  shares this agent.
      pol=policy:trunk
  ==
::  %5 rooms had no admins and no listen flag
+$  state-6
  $:  %6
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t old-room-6)
      known=old-lines
      asked=(set [=ship name=@t])
      pol=policy:trunk
  ==
::  %6 rooms had no per-room SFU; lines carried only a title
+$  state-7
  $:  %7
      ice=(list ice-server:trunk)
      sfu=sfu-config:trunk
      hosted=(map @t room:trunk)
      known=lines:trunk
      asked=(set [=ship name=@t])
      pol=policy:trunk
  ==
+$  card  card:agent:gall
::  how long a minted ticket stays valid. Long enough for a call that
::  outlasts a conversation, short enough that a removed member loses
::  access without a key rotation.
++  ticket-ttl  ^~((div ~h6 ~s1))
::  how many party-line invitations we will remember from the network.
++  invite-cap  256
::  how many lines one ship will host. A remote admin can open one, so
::  this is the brake on that.
++  room-cap  64
::  the longest a listen link may live. Galène's tokens are stateless,
::  so nothing can revoke one early — a short cap is the only brake.
++  listen-ttl-cap  ^~((div ~h1 ~s1))
::  +upgrade-rooms: rooms before state-6 had only [title members].
::  Pure, so it lives out here rather than in the agent core — that
::  core admits exactly its ten arms.
++  upgrade-rooms
  |=  old=(map @t old-room)
  ^-  (map @t room:trunk)
  %-  ~(run by old)
  |=  r=old-room
  ^-  room:trunk
  [title.r members.r ~ %.n ~]
::
::  +upgrade-rooms-6: %6 rooms gain the per-room SFU, unset — every
::  existing room keeps running on its host ship's own sidecar.
++  upgrade-rooms-6
  |=  old=(map @t old-room-6)
  ^-  (map @t room:trunk)
  %-  ~(run by old)
  |=  r=old-room-6
  ^-  room:trunk
  [title.r members.r admins.r listen.r ~]
::
::  +upgrade-lines: a remembered invitation used to be just a title.
::  Nothing is known about its settings until the host announces
::  again, so assume the conservative values.
++  upgrade-lines
  |=  old=old-lines
  ^-  lines:trunk
  %-  ~(run by old)
  |=  t=@t
  ^-  line:trunk
  [t %.n '']
::
::  The policy a ship starts with: ring for anyone, block nobody.
::  Always assign this explicitly — never lean on the bunt of
::  +$ policy, which forks to %allow and locks the ship down.
++  open-policy  `policy:trunk`[%open ~ ~]
--
%-  agent:dbug
=|  state-7
=*  state  -
^-  agent:gall
=<
|_  =bowl:gall
+*  this  .
    def   ~(. (default-agent this %.n) bowl)
    hc    ~(. +> bowl)
::
::  A fresh install must start OPEN. Not decorative: `on-init` used to
::  be a bare ``this`, which leaves the bunt of the state — and the
::  bunt of ?(%open %allow) is %allow, the last case, not the first.
::  Every newly installed ship therefore came up in allow-mode with an
::  empty allow set and silently refused every caller, while the
::  migration paths below set the policy explicitly and looked fine.
::  Nothing caught it because every test ship was an upgrade.
++  on-init  `this(pol.state open-policy)
++  on-save  !>(state)
++  on-load
  |=  old-vase=vase
  ^-  (quip card _this)
  =/  old  !<(versioned-state old-vase)
  ?-  -.old
    %0  `this(state [%7 ~ ['' '' ''] ~ ~ ~ open-policy])
    %1  `this(state [%7 ice.old ['' '' ''] ~ ~ ~ open-policy])
    %2  `this(state [%7 ice.old sfu.old (upgrade-rooms hosted.old) ~ ~ open-policy])
    %3
  :-  ~
  %=  this
    state
  :*  %7  ice.old  sfu.old  (upgrade-rooms hosted.old)
      (upgrade-lines known.old)  ~  open-policy
  ==  ==
  ::  upgrading must not silently start refusing calls, so an existing
  ::  ship keeps ringing for anyone until its owner says otherwise.
    %4
  :-  ~
  %=  this
    state
  :*  %7  ice.old  sfu.old  (upgrade-rooms hosted.old)
      (upgrade-lines known.old)  asked.old  open-policy
  ==  ==
  ::  existing rooms gain no admins and no anonymous listening: both
  ::  are things you opt into, never things an upgrade turns on.
    %5
  :-  ~
  %=  this
    state
  :*  %7  ice.old  sfu.old  (upgrade-rooms hosted.old)
      (upgrade-lines known.old)  asked.old  pol.old
  ==  ==
  ::  %6 already had admins and the listen flag; it gains only the
  ::  per-room SFU, unset.
    %6
  :-  ~
  %=  this
    state
  :*  %7  ice.old  sfu.old  (upgrade-rooms-6 hosted.old)
      (upgrade-lines known.old)  asked.old  pol.old
  ==  ==
    %7  `this(state old)
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
      ::  Reopening keeps the room's existing listen setting: an admin
      ::  turning anonymous listening on shouldn't be undone by the
      ::  host's client re-announcing the line.
      =/  had  (~(get by hosted.state) name.act)
      =/  listen  ?~(had %.n listen.u.had)
      =/  room-sfu  ?~(had ~ sfu.u.had)
      =/  new=room:trunk  [title.act members.act admins.act listen room-sfu]
      =.  hosted.state  (~(put by hosted.state) name.act new)
      :_  this
      (announce:hc name.act new %.y)
    ::
        %set-room-listen
      =/  got  (~(get by hosted.state) name.act)
      ?~  got  `this
      ::  Announce, like every other room change. Without this the
      ::  flag moved on the ship and nothing was told about it —
      ::  members kept showing the old state, and our own client only
      ::  noticed if some other call happened to re-scry.
      =/  new  u.got(listen listen.act)
      =.  hosted.state  (~(put by hosted.state) name.act new)
      :_  this
      (announce:hc name.act new %.y)
    ::
    ::  A listen link is a bearer token that Galène cannot revoke, so
    ::  the ttl is the whole security model — and it only exists at all
    ::  when the room's admins have asked for it.
        %share-room
      ::  Only the host holds the signing key, so a line hosted
      ::  elsewhere has to be asked. Without this an admin of someone
      ::  else's group clicked the button and nothing happened at all.
      ?.  =(host.act our.bowl)
        :_  this
        :~  :*  %pass  /room/(scot %p host.act)
                %agent  [host.act %trunk]
                %poke  %trunk-room
                !>(`room-sig:trunk`[%share name.act ttl.act])
        ==  ==
      =/  got  (~(get by hosted.state) name.act)
      ?~  got  `this
      ?.  listen.u.got  `this
      ?:  =('' key:(room-sfu:hc name.act))  `this
      =/  now-secs  (unix-secs:trunk-jwt now.bowl)
      =/  ttl  (min ttl.act listen-ttl-cap)
      =/  exp  (add now-secs ttl)
      =/  loc=@t  (room-location:hc name.act)
      =/  tok=@t
        %:  mint-listen:trunk-jwt
          key:(room-sfu:hc name.act)
          'listener'
          loc
          now-secs
          exp
        ==
      :_  this
      ~[(fact:hc [%listen-link [name.act (listen-url:hc name.act tok) exp]])]
    ::
        %close-room
      =/  got  (~(get by hosted.state) name.act)
      :-  ?~(got ~ (announce:hc name.act u.got %.n))
      this(hosted.state (~(del by hosted.state) name.act))
    ::
        %send
      :_  this
      :~  :*  %pass  /relay/(scot %p ship.act)
              %agent  [ship.act %trunk]
              %poke  %trunk-signal  !>(sig.act)
      ==  ==
    ::
        %configure-room
      ::  Hosting it ourselves? Apply directly. Creating is allowed
      ::  here without further checks — this action is local-only, so
      ::  it is already our own ship's owner asking.
      ?:  =(host.act our.bowl)
        =/  got  (~(get by hosted.state) name.act)
        ?.  open.act
          ?~  got  `this
          :-  (announce:hc name.act u.got %.n)
          this(hosted.state (~(del by hosted.state) name.act))
        =/  new=room:trunk
          ?~  got
            [title.act members.act admins.act listen.act sfu.act]
          =/  new-sfu  ?:(keep-sfu.act sfu.u.got sfu.act)
          u.got(listen listen.act, sfu new-sfu, members members.act, admins admins.act)
        =.  hosted.state  (~(put by hosted.state) name.act new)
        :_  this
        (announce:hc name.act new %.y)
      :_  this
      :~  :*  %pass  /room/(scot %p host.act)
              %agent  [host.act %trunk]
              %poke  %trunk-room
              !>  ^-  room-sig:trunk
              :*  %configure  name.act  open.act  listen.act  sfu.act
                  keep-sfu.act  title.act  members.act  admins.act
              ==
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
    ::  A room admin, over ames, turning the line on or off. %trunk
    ::  does not know what a Tlon group is — admins are simply the
    ::  ships the host listed when it opened the room.
    ::  An admin asking us to mint a listen link for a line we host.
        %share
      =/  got  (~(get by hosted.state) name.msg)
      ?~  got  `this
      ?.  (~(has in admins.u.got) src.bowl)  `this
      ?.  listen.u.got  `this
      ::  :hc, not bare. The agent core sits =< over the helper core,
      ::  so these arms resolve without it — but against the helper's
      ::  BUNTED bowl, where our.bowl is ~zod. Every link minted here
      ::  pointed at ~zod's subgroup, which no member is ever in, and
      ::  nothing errored.
      ?:  =('' key:(room-sfu:hc name.msg))  `this
      =/  now-secs  (unix-secs:trunk-jwt now.bowl)
      =/  ttl  (min ttl.msg listen-ttl-cap)
      =/  exp  (add now-secs ttl)
      =/  loc=@t  (room-location:hc name.msg)
      =/  tok=@t
        (mint-listen:trunk-jwt key:(room-sfu:hc name.msg) 'listener' loc now-secs exp)
      :_  this
      (reply:hc src.bowl [%link [name.msg (listen-url:hc name.msg tok) exp]])
    ::
    ::  the host answering our %share.
        %link
      :_  this
      ~[(fact:hc [%listen-link listen-link.msg])]
    ::
        %configure
      =/  got  (~(get by hosted.state) name.msg)
      ::  Creating a line the host has never hosted — a group admin
      ::  turning it on for the first time. There is no admin list to
      ::  check yet, so this reuses the dial that already decides who
      ::  may make this ship do work for them: whoever may ring us.
      ::  Lock your ship down and only those ships can open a line on
      ::  it; leave it open and it is the same exposure as a call.
      ?~  got
        ?.  open.msg  `this
        ?.  (may-ring:hc src.bowl)
          %-  (slog leaf+"trunk: {<src.bowl>} may not open a line here" ~)
          `this
        ?:  (gth ~(wyt by hosted.state) room-cap)
          %-  (slog leaf+"trunk: too many rooms; refusing {<name.msg>}" ~)
          `this
        =/  new=room:trunk
          [title.msg members.msg admins.msg listen.msg sfu.msg]
        =.  hosted.state  (~(put by hosted.state) name.msg new)
        :_  this
        (announce:hc name.msg new %.y)
      ?.  (~(has in admins.u.got) src.bowl)
        %-  (slog leaf+"trunk: {<src.bowl>} is not an admin of {<name.msg>}" ~)
        `this
      ?.  open.msg
        =.  hosted.state  (~(del by hosted.state) name.msg)
        :_  this
        (announce:hc name.msg u.got %.n)
      ::  State FIRST, announce second. +announce reads the room back
      ::  out of hosted.state, so announcing before the write told
      ::  everyone the OLD flag — the host changed and no client ever
      ::  heard about it, which is a switch that does nothing.
      =/  new-sfu  ?:(keep-sfu.msg sfu.u.got sfu.msg)
      =/  new  u.got(listen listen.msg, sfu new-sfu)
      =.  hosted.state  (~(put by hosted.state) name.msg new)
      :_  this
      (announce:hc name.msg new %.y)
    ::
        %announce
      ::  an invitation from anyone is fine (it is just a name), but
      ::  the list is remote-controlled, so it does not grow forever.
      ?:  (~(has in block.pol.state) src.bowl)  `this
      ?:  (gth ~(wyt by known.state) invite-cap)  `this
      =/  =line:trunk  [title.msg listen.msg sfu-base.msg]
      :-  ~[(fact:hc [%open src.bowl name.msg line])]
      this(known.state (~(put by known.state) [src.bowl name.msg] line))
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
    [%x %rooms ~]   ``trunk-rooms+!>(hosted.state)
    [%x %lines ~]   ``trunk-lines+!>(known.state)
    [%x %policy ~]  ``trunk-policy+!>(pol.state)
    ::  base + group only. The key is write-only by design.
    [%x %sfu ~]     ``json+!>((sfu-to-json:trunk-json sfu.state))
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
  ?:  =('' key:(room-sfu name))
    (reply who [%deny name 'no sfu configured'])
  =/  loc=@t  (room-location name)
  =/  now-secs  (unix-secs:trunk-jwt now.bowl)
  =/  tok=@t
    %:  mint:trunk-jwt
      key:(room-sfu name)
      (scot %p who)
      loc
      now-secs
      (add now-secs ticket-ttl)
    ==
  (reply who [%grant name loc tok])
::
::  +room-location: the Galène URL for a room we host.
::
::  Every room is a subgroup of the one configured group, so opening a
::  room needs no server-side config. The subgroup name is
::  host-qualified to keep two ships' rooms distinct on a shared SFU.
::  Shared by the member ticket and the anonymous listen link, so the
::  two can never disagree about which room they point at.
::
++  room-sfu
  |=  name=@t
  ^-  sfu-config:trunk
  =/  got  (~(get by hosted.state) name)
  ?~  got  sfu.state
  ?~(sfu.u.got sfu.state u.sfu.u.got)
::
::  +room-subgroup: the Galène group name for a room we host.
++  room-subgroup
  |=  name=@t
  ^-  @t
  =/  cfg  (room-sfu name)
  (rap 3 ~[group.cfg '/' (rsh [3 1] (scot %p our.bowl)) '-' name])
::
::  +listen-url: where a listener points a browser. Not the Galène
::  conference UI — that hides audio-only publishers behind a setting
::  and cannot autoplay in a tab nobody clicked, which made a link
::  useless to anyone who hadn't been told the trick. Trunk serves its
::  own one-button page at /listen.
++  listen-url
  |=  [name=@t tok=@t]
  ^-  @t
  =/  cfg  (room-sfu name)
  %+  rap  3
  :~  base.cfg  '/listen/?group='  (room-subgroup name)  '&token='  tok
  ==
::
++  room-location
  |=  name=@t
  ^-  @t
  =/  cfg  (room-sfu name)
  =/  sub=@t
    (rap 3 ~[(rsh [3 1] (scot %p our.bowl)) '-' name])
  (rap 3 ~[base.cfg '/group/' group.cfg '/' sub '/'])
::
::  +announce: tell every member a line opened (or closed). The host
::  is always a member of its own line for this purpose; we skip
::  ourselves since our client already knows.
::
::  Takes the room BY VALUE, deliberately. `hc` is ~(. +> bowl), so
::  the helper core closes over the state as it was when the arm was
::  entered — reading hosted.state back in here returned the value
::  from before the caller's own `=.`, and every change announced the
::  flag it had just replaced. That is one bug wearing several hats:
::  a switch that does nothing, a listen link refused after enabling.
++  announce
  |=  [name=@t =room:trunk open=?]
  =/  title  title.room
  =/  members  members.room
  =/  listen  listen.room
  =/  base  base:?~(sfu.room sfu.state u.sfu.room)
  ^-  (list card)
  ::  The host tells itself too. It is excluded from the ames pokes
  ::  below — it is not a peer of its own room — but its client still
  ::  has to see the change, and it has no other way to: without this
  ::  the only path was a re-scry racing the poke, so an admin's first
  ::  click on a switch appeared to do nothing.
  :-  ?:  open
        (fact [%open our.bowl name [title listen base]])
      (fact [%shut our.bowl name])
  %+  turn  ~(tap in (~(del in members) our.bowl))
  |=  who=ship
  ^-  card
  :*  %pass  /room/(scot %p who)
      %agent  [who %trunk]
      %poke  %trunk-room
      !>(`room-sig:trunk`?:(open [%announce name title listen base] [%shut name]))
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
      %announce
    ~[(fact [%open our.bowl name.msg [title.msg listen.msg sfu-base.msg]])]
      %shut      ~[(fact [%shut our.bowl name.msg])]
      ::  neither is ever addressed to ourselves; the ?- must still
      ::  be total.
      %ask        ~
      %configure  ~
      %share      ~
      %link       ~[(fact [%listen-link listen-link.msg])]
    ==
  :~  :*  %pass  /room/(scot %p who)
          %agent  [who %trunk]
          %poke  %trunk-room  !>(msg)
  ==  ==
--
