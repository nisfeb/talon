::  +trunk/policy: print who may ring this ship. Run it from the desk:
::  `=dir /=trunk=` then `+trunk/policy`.
::
::  %trunk has no UI by design, so this is the dojo's read side. The
::  write side is a plain poke, no generator needed:
::
::    :trunk &trunk-action [%set-call-mode %allow]
::    :trunk &trunk-action [%allow ~zod]
::    :trunk &trunk-action [%block ~bus]
::    :trunk &trunk-action [%unblock ~bus]
::
/-  trunk
:-  %say
|=  [[now=@da * bec=beak] ~ ~]
:-  %tang
=/  pol=policy:trunk
  .^(policy:trunk %gx /(scot %p p.bec)/trunk/(scot %da now)/policy/noun)
=/  names
  |=  who=(set @p)
  ^-  tape
  ?:  =(~ who)  "(none)"
  %+  roll  ~(tap in who)
  |=  [w=@p acc=tape]
  ?:  =("" acc)  (scow %p w)
  :(weld acc ", " (scow %p w))
^-  (list tank)
:~  leaf+"mode:  {?:(=(%open mode.pol) "open — anyone may ring" "allow — only the allow list may ring")}"
    leaf+"allow: {(names allow.pol)}"
    leaf+"block: {(names block.pol)}"
==
