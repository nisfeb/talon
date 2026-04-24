# Fakezod harness

A local Urbit ship you can point Talon at without touching real planet
data. Driven by the `urbit-mcp-server` so desk operations go through a
clean HTTP API instead of the fragile `conn.sock` shell.

Useful for:

- Catching schema regressions before they hit users (every time Tlon
  renames something — `fleet→seats`, `cordon→admissions` — it broke us
  silently until a real-device repro).
- Reproducing bugs with a fresh, known-good state.
- Running the app in airplane mode against a LAN ship.

## Prerequisites

- **peru** package manager — `pip install peru` (or whatever your OS
  uses). Required by the MCP server's build.
- **jq** — used by the MCP helpers for JSON assembly / inspection.
- A checkout of
  [`urbit-mcp-server`](https://github.com/groundwire-urbit/urbit-mcp-server)
  (or whichever fork you use). Default path is
  `~/software/groundwire/urbit-mcp-server`; override with
  `MCP_SRC=…`.

## One-time setup

```sh
cd scripts/fakezod

# Step 1 — boot a fresh fakezod. Leave this terminal open; it's where
# you'll type the handful of dojo commands below.
./boot.sh                 # downloads urbit (first time), boots ~zod

# In dojo, note +code for later:
# ~zod:dojo> +code

# Step 2 — install the MCP server (one pause mid-script for dojo).
./install-mcp.sh          # pauses twice; follow the on-screen dojo hints

# Step 3 — install Tlon's desks via MCP. No more dojo required.
CODE=<+code> ./install-tlon.sh

# Step 4 — smoke the install.
CODE=<+code> ./ping-mcp.sh
CODE=<+code> ./seed.sh    # prints the app-side steps to cover
```

`boot.sh` writes the pier at `./pier/zod` and the urbit binary cache at
`./.urbit/`. Both are gitignored. First boot takes ~30 s; re-attaching
is ~5 s.

`install-mcp.sh` requires you to type two pairs of dojo commands (create
+ mount, then commit + install). There's no way around that first one —
the ship has nothing to drive itself with until MCP is installed.

`install-tlon.sh` is fully automated after login. It does: new-desk,
mount-desk, rsync sources, commit-desk, install-app for each of
`%groups`, `%chat`, `%channels`, `%activity`, `%contacts`, `%storage`,
`%groups-ui`. Every call goes through MCP tool dispatch — no
`conn.sock`, no dojo piping.

## Re-booting / re-seeding

```sh
./boot.sh --resume        # re-attach to existing pier
CODE=<+code> ./install-tlon.sh   # pulls latest tlon-apps and re-commits
```

`+code` stays the same across boots. Jot it down the first time.

## Pointing Talon at the fakezod

1. Phone connected over adb.
2. Forward localhost:8080 into the device:
   ```sh
   adb reverse tcp:8080 tcp:8080
   ```
3. Talon login screen:
   - **Ship URL:** `http://localhost:8080`
   - **+code:** the one dojo prints for `+code`

## Critical-path checklist

Run before every release tag:

- Create a group (admin screen → + → pick type).
- Create a chat, a notebook, a gallery channel.
- Post in each; edit; delete; react.
- Invite a ghost ship (`~bud`), verify the Invited list, revoke it.
- Open a shut group's Requests, approve + deny one.
- Switch ships (if you have two logged in) and confirm no preview
  bleed.

Watch `adb logcat -s TlonChatRepo` while you go — any `poke nack` line
is a regression.

## Files

```
scripts/fakezod/
├── boot.sh             # download + boot the fakezod
├── install-mcp.sh      # one-time MCP-server install on a fresh pier
├── install-tlon.sh     # install tlon-apps desks via MCP
├── seed.sh             # print the manual Talon steps + verify MCP
├── ping-mcp.sh         # smoke the MCP endpoint
├── lib-mcp.sh          # shared mcp_call / mcp_login helpers
└── README.md           # this file
```

## Teardown

```sh
rm -rf pier/ .tlon-apps/ .urbit/
```

Everything is self-contained — no system state touched outside these
directories.
