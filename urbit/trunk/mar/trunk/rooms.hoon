::  party lines we host, scried by clients at /x/rooms. Drives the
::  admin switches and whether a group shows a call button at all.
/-  trunk
/+  trunk-json
|_  rooms=(map @t room:trunk)
++  grab
  |%
  ++  noun  (map @t room:trunk)
  --
++  grow
  |%
  ++  noun  rooms
  ++  json  (rooms-to-json:trunk-json rooms)
  --
++  grad  %noun
--
