::  trunk-jwt: mint HS256 JWTs that the party-line SFU (Galène) accepts.
::
::  Byte-order notes, learned the hard way — the two primitives disagree:
::    - base64:mimes:html reads octs data LSB-first (native cord order),
::      so a cord goes in as [(met 3 c) c] and comes back readable.
::    - hmac-sha256l:hmac:crypto reads octs data MSB-first and returns a
::      big-endian atom, so its inputs need +swp / +rev and its output
::      needs +rev before it can be base64'd.
::  Getting this backwards yields a token that looks perfect and fails
::  every signature check, so the round-trip is covered by +test-vector.
|%
++  en-b64  ~(en base64:mimes:html | &)
++  de-b64  ~(de base64:mimes:html | &)
::  +mint: a Galène-compatible JWT.
::
::    key  the group's HS256 secret, base64url — the same string that
::         appears as the "k" field of Galène's authKeys entry
::    sub  username the SFU shows for this client (we pass the @p)
::    aud  the group's location URL, e.g.
::         'http://host:8444/group/talon-zod-lounge/' (trailing slash
::         required: Galène matches the path as /group/<name>/)
::    now  current time in unix seconds
::    exp  expiry in unix seconds (Galène requires the claim)
::
++  mint
  |=  [key=@t sub=@t aud=@t now=@ud exp=@ud]
  ^-  @t
  =/  sec=octs  (fall (de-b64 key) [0 0])
  =/  hed=@t  '{"alg":"HS256","typ":"JWT"}'
  =/  pay=@t
    %-  en:json:html
    %-  pairs:enjs:format
    :~  ['sub' s+sub]
        ['aud' a+~[s+aud]]
        ['permissions' a+~[s+'present']]
        ['iat' (numb:enjs:format now)]
        ['exp' (numb:enjs:format exp)]
    ==
  =/  b-hed=@t  (en-b64 [(met 3 hed) hed])
  =/  b-pay=@t  (en-b64 [(met 3 pay) pay])
  =/  si=@t  (rap 3 ~[b-hed '.' b-pay])
  =/  mac=@
    %+  hmac-sha256l:hmac:crypto
      [p.sec (rev 3 p.sec q.sec)]
    [(met 3 si) (swp 3 si)]
  =/  b-sig=@t  (en-b64 [32 (rev 3 32 mac)])
  (rap 3 ~[si '.' b-sig])
::  +unix-secs: @da -> unix epoch seconds, for the iat/exp claims.
::
++  unix-secs
  |=  t=@da
  ^-  @ud
  (div (sub t ~1970.1.1) ~s1)
--
