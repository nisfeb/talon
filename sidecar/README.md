# Trunkline sidecar

One compose file next to your ship gives Trunkline calls their Tier 1
(STUN) and Tier 2 (TURN relay) — infrastructure you own, on the machine
your ship already runs on. Galène joins in v2 for party-line SFU rooms.

## Run

```bash
TURN_USER=talon TURN_PASS=$(openssl rand -hex 16) docker compose up -d
```

Open UDP 3478 and UDP 49160-49200 on the host firewall.

## Advertise it from your ship

```dojo
:trunk &trunk-action [%set-ice ~[['stun:your.host:3478' '' ''] ['turn:your.host:3478' 'talon' '<TURN_PASS>']]]
```

Clients scry `/x/ice` at startup and hand these to the call engine —
no app-side configuration. Ships without a sidecar still make Tier 0
calls (same LAN / public IPv6) and can inherit a sponsor's servers
once the icepond cascade lands.
