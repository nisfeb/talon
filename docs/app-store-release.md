# Releasing Talon on the iOS App Store

TestFlight and the App Store are two different things and only one of
them is routine. Pushing a TestFlight build is the thing we do every
few days; submitting for App Store review is a separate, irreversible
step we have not done yet. This file is about the second one.

Everything marked **CONFIRMED** was checked against Apple on
2026-08-30 via a `publish` dry run. Everything marked **UNVERIFIED**
is inference and needs checking before it is trusted.

## What already works — TestFlight

This is the routine path and nothing below blocks it.

```sh
# Any Node 18+ runtime. The system Node here is 16, which has no
# global fetch and makes every nomac call fail; nvm's is fine.
export PATH="$HOME/.nvm/versions/node/v25.6.1/bin:$PATH"
npx -y @nomac/cli push      # packs the working tree, runs review lint
npx -y @nomac/cli build     # signed archive, uploads to App Store Connect
npx -y @nomac/cli status <build-id>
```

State machine: `queued → mirrored → dispatched → building → uploading
→ processing → ready`. A release build costs one from the plan quota
(~13 minutes for build 30). Latest at time of writing: **build 30,
ready**, asc `869c54f2-e535-4973-9685-bcc58371e16a`, MARKETING_VERSION
0.16.0.

## The submission gate

`publish` runs Apple's submission validation. With `confirm: false` it
costs nothing, submits nothing, and reports how many blockers Apple
found. With `confirm: true` it is **irreversible** and starts a 1–3 day
review.

As of the last dry run: **26 blockers**.

The tool returns only a count — no list, no deep links. That is the
single most annoying thing about this process: the per-item detail
lives in the App Store Connect dashboard, so someone has to open it and
read them off. Do that first, and replace the guesswork below with the
real list.

### CONFIRMED outstanding

- [ ] **Screenshots — none uploaded at all.** `APP_IPHONE_67` is the
      only one Apple marks `required: true`. Accepted sizes are
      `1320x2868`, `2868x1320`, `1290x2796`, `2796x1290`, and the
      schema is explicit that dimensions must match **exactly** — one
      pixel off and the upload is rejected. Optional families:
      `APP_IPHONE_65`, `APP_IPHONE_61`, `APP_IPAD_PRO_3GEN_129`.

      `ios-sim.yml` is not a shortcut for this. It boots whatever
      iPhone simulator happens to be first in the list and captures the
      launch screen, so it is neither the right device size nor a
      usable store image. Real screenshots mean pinning a 6.9" device,
      signing into a ship, and navigating to representative screens.

- [ ] **Version mismatch.** App Store Connect's version record says
      `1.0`; the build says `0.16.0`. These must match or the build
      never attaches to the version. Decide which moves — bumping
      `MARKETING_VERSION` to 1.0 means another build from quota.

### UNVERIFIED — likely part of the 26, not yet checked

Standard App Store Connect requirements that we have never filled in
and that plausibly account for the remaining ~24. **None of these were
confirmed against Apple's actual response** — treat as leads.

- [ ] Age rating questionnaire
- [ ] Export compliance (`ITSAppUsesNonExemptEncryption` is already
      `false` in Info.plist, so this may already be satisfied)
- [ ] Content rights declaration
- [ ] App Review contact details and demo account

The demo account one deserves thought before it is a surprise: Talon
cannot be used without an Urbit ship, and App Review will need working
credentials. A throwaway ship with seeded conversations is probably the
answer, and it is the same asset the screenshots need.

### Already satisfied

- **Source review lint: green**, zero findings.
- **Apple connection healthy**, API key valid (`56P3FUQDS7`), 2 apps.
  No cert or webhook configured, which has not mattered so far.
- **Listing copy complete**: name (`Talon for Urbit`), subtitle,
  privacy policy URL, description, keywords, support URL, marketing
  URL, promotional text.
- **`whats_new` is null**, which is correct — the schema says release
  notes are not allowed on a first version.

## Two calls that are not the agent's to make

1. **The version number.** 1.0 or 0.16.0. Version bumps here have
   consequences beyond aesthetics — see RELEASE.md "Version bumping".
2. **What the screenshots show.** They are public marketing assets of a
   chat client, so whatever conversation is on screen ships with them.

## Tooling notes worth keeping

**`publish` is MCP-only.** The CLI has `login`, `push`, `build`,
`status`, `logs`, `mcp`, `whoami` — and no submit command. So
submission requires the MCP tool.

**When the MCP client is unhealthy, drive the server directly.** The
nomac MCP server failed all session with `fetch is not defined` while
the same code worked fine from the CLI. `nomac mcp` is a plain stdio
JSON-RPC server, so it can be spoken to by hand:

```python
# initialize → notifications/initialized → tools/call
subprocess.Popen([NODE_BIN + "/npx", "-y", "@nomac/cli", "mcp"], ...)
```

Run it under the same Node 18+ runtime as above, not the system Node
16, which has no global `fetch`. The whole `publish` dry run was done
this way.

**iOS cannot be verified on the Linux dev box.** `compileKotlinIosArm64`
and `linkReleaseFrameworkIosArm64` both report `BUILD SUCCESSFUL` while
producing no framework at all — they are no-ops here. The `ios-compile`
job in `.github/workflows/test.yml` runs a real Xcode archive on macOS
and is the **only** thing in this repo that catches iOS breakage. It is
what caught `export(project(":core"))` missing after the `:core`
extraction, which had made the iOS app unbuildable for four commits
while every local check reported success.

`release.yml` builds Android and desktop on ubuntu and never touches
iOS, so a green release run says nothing about the iOS app.
