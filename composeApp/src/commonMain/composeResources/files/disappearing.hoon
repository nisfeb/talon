::  Talon: disappearing messages
::
::    Deletes the channel's posts once they are older than the config's
::    'delay', a @dr. A thread's replies go with its post. Each new post
::    sets a timer for when it falls due. A cron sweeps what no timer
::    covers: posts from before the setting, and posts left over when
::    the delay grows. One run deletes at most 100 posts and wakes
::    again in 15 seconds while more are due, so turning this on over a
::    long history does not flood the members' ships. With no 'delay'
::    it does nothing.
::
|=  [=event:h bowl:h]
^-  outcome:h
=-  &+[[[%allowed event] -] state.hook]
^-  (list effect:h)
=/  delay  (~(get by config) 'delay')
?.  ?=([~ @] delay)  ~
?~  channel  ~
?:  |(=(0 u.delay) (gth u.delay now))  ~
?:  ?=([%on-post %add *] event)
  :_  ~
  :*  %wait
      (sham nest.u.channel id.post.event)
      id.hook
      !>(~)
      (add id.post.event u.delay)
  ==
?.  ?=(?(%cron %wake) -.event)  ~
::  ponytail: walks every post and tombstone older than the cutoff on
::  each run. Keep a swept-up-to mark in the hook's state if channels
::  grow big enough for that to show.
=/  due=(list id-post:c)
  %+  murn
    %-  tap:on-v-posts:c
    (lot:on-v-posts:c posts.u.channel ~ `(sub now u.delay))
  |=  [=id-post:c post=(may:c v-post:c)]
  ?:(?=(%| -.post) ~ `id-post)
=/  dels=(list effect:h)
  %+  turn  (scag 100 due)
  |=(=id-post:c [%channels %channel nest.u.channel %post %del id-post])
?:  (lte (lent due) 100)  dels
:_  dels
[%wait (sham nest.u.channel %more) id.hook !>(~) (add now ~s15)]
