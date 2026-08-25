::  trunk-json: json <-> trunk types, shared by the eyre-facing marks.
::  Wire shapes (client must match TrunkWire.kt):
::    action  {"send":{"ship":"~zod","sig":{...}}}
::    sig     {"ring":{"id":i}} | {"offer":{"id":i,"sdp":s,"fpr":f}}
::            {"accept":{...}}  | {"reject":{"id":i,"reason":r}}
::            {"hangup":{"id":i}}
::    update  {"recv":{"from":"~zod","sig":{...}}}
/-  trunk
|%
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
++  action-from-json
  =,  dejs:format
  ^-  $-(json action:trunk)
  %-  of
  :~  :-  %send
      (ot ~[ship+(su ;~(pfix sig fed:ag)) sig+sig-from-json])
  ==
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
  %+  frond  %recv
  %-  pairs
  ::  (scot %p) keeps the leading sig; enjs's +ship drops it, which
  ::  broke the client's reply path (it poked back a sig-less ship
  ::  that the action mark's dejs refused).
  :~  [%from s+(scot %p from.u)]
      [%sig (sig-to-json sig.u)]
  ==
--
