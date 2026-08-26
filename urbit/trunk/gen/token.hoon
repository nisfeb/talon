::  +trunk/token: mint a party-line JWT by hand, for testing the
::  signing path against a live Galène. Args: key, sub, aud, ttl-secs.
/+  trunk-jwt
:-  %say
|=  [[now=@da * *] [key=@t sub=@t aud=@t ttl=@ud ~] ~]
:-  %noun
=/  t  (unix-secs:trunk-jwt now)
(mint:trunk-jwt key sub aud t (add t ttl))
