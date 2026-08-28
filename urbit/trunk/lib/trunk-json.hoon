::  trunk-json: json <-> trunk types, shared by the eyre-facing marks.
::  This file is the wire's source of truth — the Kotlin client's
::  TrunkWire.kt mirrors it exactly.
::
::    action  {"send":{"ship":"~zod","sig":{...}}}
::            {"set-ice":{"servers":[{"url":u,"user":s,"cred":c}]}}
::            {"set-sfu":{"base":b,"group":g,"key":k}}
::            {"open-room":{"name":n,"title":t,"members":["~zod"],
::                           "admins":["~zod"]}}
::            {"set-room-listen":{"name":n,"listen":true}}
::            {"share-room":{"host":"~zod","name":n,"ttl":600}}
::            {"configure-room":{"host":"~zod","name":n,"open":true,
::                               "listen":false,"keep-sfu":true,
::                               "sfu":null|{"base":b,"group":g,"key":k}}}
::            {"close-room":{"name":n}}
::            {"join-room":{"host":"~zod","name":n}}
::            {"set-call-mode":"open"} | {"allow":"~zod"}
::            {"unallow":"~zod"} | {"block":"~zod"} | {"unblock":"~zod"}
::    sig     {"ring":{"id":i}} | {"offer":{"id":i,"sdp":s,"fpr":f}}
::            {"accept":{...}}  | {"reject":{"id":i,"reason":r}}
::            {"hangup":{"id":i}}
::    update  {"recv":{"from":"~zod","sig":{...}}}
::            {"ticket":{"from":"~zod","name":n,"location":l,"token":t}}
::            {"denied":{"from":"~zod","name":n,"why":w}}
::    policy  {"mode":"open","allow":["~zod"],"block":["~bus"]}
::    link    {"listen-link":{"name":n,"url":u,"expires":1787000000}}
/-  trunk
|%
++  ship-from-json  (su:dejs:format ;~(pfix sig fed:ag))
::
++  sig-from-json
  =,  dejs:format
  ^-  $-(json sig:trunk)
  %-  of
  :~  [%ring (ot ~[id+so])]
      [%offer (ot ~[id+so sdp+so fpr+so])]
      [%accept (ot ~[id+so sdp+so fpr+so])]
      [%reject (ot ~[id+so reason+so])]
      [%hangup (ot ~[id+so])]
  ==
::
++  sfu-from-json
  =,  dejs:format
  ^-  $-(json sfu-config:trunk)
  (ot ~[base+so group+so key+so])
::
++  ice-from-json
  =,  dejs:format
  ^-  $-(json ice-server:trunk)
  (ot ~[url+so user+so cred+so])
::
++  action-from-json
  =,  dejs:format
  ^-  $-(json action:trunk)
  %-  of
  :~  [%send (ot ~[ship+ship-from-json sig+sig-from-json])]
      [%set-ice (ot ~[servers+(ar ice-from-json)])]
      [%set-sfu (ot ~[base+so group+so key+so])]
      :-  %open-room
      (ot ~[name+so title+so members+(as ship-from-json) admins+(as ship-from-json)])
      [%set-room-listen (ot ~[name+so listen+bo])]
      [%share-room (ot ~[host+ship-from-json name+so ttl+ni])]
      :-  %configure-room
      %-  ot
      :~  host+ship-from-json  name+so  open+bo  listen+bo
          ::  ~ means "use the host ship's own sidecar"
          sfu+(mu sfu-from-json)  keep-sfu+bo
          ::  only used when the room does not exist yet
          title+so  members+(as ship-from-json)  admins+(as ship-from-json)
      ==
      [%close-room (ot ~[name+so])]
      [%join-room (ot ~[host+ship-from-json name+so])]
      [%set-call-mode (su (perk %open %allow ~))]
      [%allow ship-from-json]
      [%unallow ship-from-json]
      [%block ship-from-json]
      [%unblock ship-from-json]
  ==
::
++  policy-to-json
  ::  no `=, enjs:format` here either; see +lines-to-json.
  |=  pol=policy:trunk
  ^-  json
  =/  ships
    |=  who=(set @p)
    ^-  json
    :-  %a
    %+  turn  ~(tap in who)
    |=(w=@p `json`s+(scot %p w))
  %-  pairs:enjs:format
  :~  [%mode s+`@t`mode.pol]
      [%allow (ships allow.pol)]
      [%block (ships block.pol)]
  ==
::
++  ice-to-json
  |=  servers=(list ice-server:trunk)
  ^-  json
  =,  enjs:format
  :-  %a
  %+  turn  servers
  |=  s=ice-server:trunk
  (pairs ~[url+s+url.s user+s+user.s cred+s+cred.s])
::
++  sig-to-json
  |=  s=sig:trunk
  ^-  json
  =,  enjs:format
  ?-  -.s
    %ring    (frond %ring (pairs ~[id+s+id.s]))
    %offer   (frond %offer (pairs ~[id+s+id.s sdp+s+sdp.s fpr+s+fpr.s]))
    %accept  (frond %accept (pairs ~[id+s+id.s sdp+s+sdp.s fpr+s+fpr.s]))
    %reject  (frond %reject (pairs ~[id+s+id.s reason+s+reason.s]))
    %hangup  (frond %hangup (pairs ~[id+s+id.s]))
  ==
::
++  update-to-json
  |=  u=update:trunk
  ^-  json
  =,  enjs:format
  ?-    -.u
      %recv
    ::  (scot %p) keeps the leading sig; enjs's +ship drops it, which
    ::  broke the client's reply path (it poked back a sig-less ship
    ::  that the action mark's dejs refused).
    %+  frond  %recv
    %-  pairs
    :~  [%from s+(scot %p from.u)]
        [%sig (sig-to-json sig.u)]
    ==
  ::
      %ticket
    %+  frond  %ticket
    %-  pairs
    :~  [%from s+(scot %p from.u)]
        [%name s+name.ticket.u]
        [%location s+location.ticket.u]
        [%token s+token.ticket.u]
    ==
  ::
      %denied
    %+  frond  %denied
    %-  pairs
    :~  [%from s+(scot %p from.u)]
        [%name s+name.u]
        [%why s+why.u]
    ==
  ::
      %open
    %+  frond  %open
    %-  pairs
    :~  [%from s+(scot %p from.u)]
        [%name s+name.u]
        [%title s+title.line.u]
        [%listen b+listen.line.u]
        [%sfu-base s+sfu-base.line.u]
    ==
  ::
      %shut
    %+  frond  %shut
    %-  pairs
    :~  [%from s+(scot %p from.u)]
        [%name s+name.u]
    ==
  ::
      %policy  (frond %policy (policy-to-json policy.u))
  ::
      %listen-link
    %+  frond  %listen-link
    %-  pairs
    :~  [%name s+name.listen-link.u]
        [%url s+url.listen-link.u]
        [%expires (numb expires.listen-link.u)]
    ==
  ==
::
++  sfu-to-json
  ::  Never the key: a client may see WHICH sidecar its ship uses and
  ::  whether one is set, never the secret that signs its tickets.
  |=  cfg=sfu-config:trunk
  ^-  json
  %-  pairs:enjs:format
  :~  [%base s+base.cfg]
      [%group s+group.cfg]
      [%configured b+!=('' key.cfg)]
  ==
::
++  rooms-to-json
  ::  The SFU secret never leaves the ship: an admin sets it, and can
  ::  see WHICH sidecar is in use, but reading it back is not part of
  ::  the deal.
  |=  rooms=(map @t room:trunk)
  ^-  json
  :-  %a
  %+  turn  ~(tap by rooms)
  |=  [nom=@t =room:trunk]
  ^-  json
  %-  pairs:enjs:format
  :~  [%name s+nom]
      [%title s+title.room]
      [%listen b+listen.room]
      [%sfu-base s+?~(sfu.room '' base.u.sfu.room)]
      [%custom-sfu b+?=(^ sfu.room)]
      :-  %members
      [%a (turn ~(tap in members.room) |=(w=@p `json`s+(scot %p w)))]
      :-  %admins
      [%a (turn ~(tap in admins.room) |=(w=@p `json`s+(scot %p w)))]
  ==
::
++  lines-to-json
  ::  no `=, enjs:format` here: it shadows `ship` with the json encoder
  ::  of that name, so a `who=ship` binding silently becomes a gate.
  |=  known=lines:trunk
  ^-  json
  :-  %a
  %+  turn  ~(tap by known)
  |=  [[who=@p nom=@t] =line:trunk]
  ^-  json
  %-  pairs:enjs:format
  :~  [%host s+(scot %p who)]
      [%name s+nom]
      [%title s+title.line]
      [%listen b+listen.line]
      [%sfu-base s+sfu-base.line]
  ==
--
