# Trunkline sidecar

Two services next to your ship, on the machine your ship already runs
on. Neither is required — without them Talon still makes Tier 0 calls
(same LAN, public IPv6) — but together they cover the hostile-NAT
fallback and let you *host* party lines.

| Service | Gives you | Needed by |
|---|---|---|
| coturn | STUN echo (Tier 1) + TURN relay (Tier 2) | 1:1 calls across hostile NAT |
| galene | the SFU party lines run on | hosting a party line |

One sidecar anywhere between two callers covers that call, so running
one upgrades every call made *to* you. For why coturn exists at all
when Galène already ships a TURN server — and what each service is
actually on the critical path for — see "What the sidecar is actually
for" in `docs/trunkline.md`.

## 1. Generate the party-line signing key

`%trunk` signs join tickets with an HS256 secret that Galène also
holds. Generate 32 random bytes, base64url:

```bash
KEY=$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=')
echo "$KEY"
```

Write the Galène group config (one group; rooms are subgroups created
on demand):

```bash
mkdir -p galene/groups galene/data
cat > galene/groups/talon.json <<EOF
{
  "authKeys": [{"kty": "oct", "alg": "HS256", "k": "$KEY"}],
  "auto-subgroups": true,
  "public": false
}
EOF
echo '{}' > galene/data/config.json
```

Note the mixed spelling: `authKeys` is camelCase, `auto-subgroups` is
kebab — Galène wants exactly that, and rejects the file otherwise.

## 2. Run

```bash
TURN_PASS=$(openssl rand -hex 16) docker compose up -d
```

Open UDP 3478 and 49160-49200 (coturn), and TCP 8444 (Galène). Put
Galène behind TLS in any real deployment — `-insecure` here keeps the
local setup simple, and Galène hands clients its own TURN credentials
on join, so party-line media needs no extra NAT config.

## 3. Point your ship at it

From the ship's dojo, once:

```
:trunk &trunk-action [%set-ice ~[['stun:your.host:3478' '' ''] ['turn:your.host:3478' 'talon' 'THE_TURN_PASS']]]
:trunk &trunk-action [%set-sfu ['http://your.host:8444' 'talon' 'THE_KEY']]
```

Clients scry `/x/ice` at startup and hand the result to the call
engine — nothing to configure app-side. Party-line tickets are minted
on demand by whichever ship hosts the room.
