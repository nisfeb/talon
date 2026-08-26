::  trunk-json: json <-> trunk types, shared by the eyre-facing marks.
::  This file is the wire's source of truth — the Kotlin client's
::  TrunkWire.kt mirrors it exactly.
::
::    action  {"send":{"ship":"~zod","sig":{...}}}
::            {"set-ice":{"servers":[{"url":u,"user":s,"cred":c}]}}
::            {"set-sfu":{"base":b,"group":g,"key":k}}
::            {"open-room":{"name":n,"title":t,"members":["~zod"]}}
::            {"close-room":{"name":n}}
::            {"join-room":{"host":"~zod","name":n}}
::    sig     {"ring":{"id":i}} | {"offer":{"id":i,"sdp":s,"fpr":f}}
::            {"accept":{...}}  | {"reject":{"id":i,"reason":r}}
::            {"hangup":{"id":i}}
::    update  {"recv":{"from":"~zod","sig":{...}}}
::            {"ticket":{"from":"~zod","name":n,"location":l,"token":t}}
::            {"denied":{"from":"~zod","name":n,"why":w}}
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
      [%open-room (ot ~[name+so title+so members+(as ship-from-json)])]
      [%close-room (ot ~[name+so])]
      [%join-room (ot ~[host+ship-from-json name+so])]
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
        [%title s+title.u]
    ==
  ::
      %shut
    %+  frond  %shut
    %-  pairs
    :~  [%from s+(scot %p from.u)]
        [%name s+name.u]
    ==
  ==
::
++  lines-to-json
  ::  no `=, enjs:format` here: it shadows `ship` with the json encoder
  ::  of that name, so a `who=ship` binding silently becomes a gate.
  |=  known=lines:trunk
  ^-  json
  :-  %a
  %+  turn  ~(tap by known)
  |=  [[who=@p nom=@t] title=@t]
  ^-  json
  %-  pairs:enjs:format
  :~  [%host s+(scot %p who)]
      [%name s+nom]
      [%title s+title]
  ==
--
