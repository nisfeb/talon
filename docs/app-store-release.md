# Releasing Talon on the iOS App Store

TestFlight and the App Store are two different things and only one of
them is routine. Pushing a TestFlight build is the thing we do every
few days; submitting for App Store review is a separate, irreversible
step we have not done yet. This file is about the second one.

Everything below marked **VERIFIED** was checked against Apple on
2026-09-01 (metadata pull + `publish` dry run + schema).

## What already works — TestFlight

```sh
# Any Node 18+ runtime. The system Node here is 16, which has no
# global fetch and makes every nomac call fail; nvm's is fine.
export PATH="$HOME/.nvm/versions/node/v25.6.1/bin:$PATH"
npx -y @nomac/cli push              # packs the working tree, runs review lint
npx -y @nomac/cli build --smoke     # FREE unsigned compile check (use for risky changes)
npx -y @nomac/cli build             # signed, uploads to TestFlight (one from quota)
npx -y @nomac/cli status <build-id>
```

The smoke flag is `--smoke` — NOT `--workflow smoke`, which the CLI
silently ignores and starts a paid release build (this burned build
#35's quota slot). Smoke logs also show xcodebuild stderr that release
logs swallow, which is how the WebRTC-151 resolution failure was
finally diagnosed.

Latest at time of writing: **build #40, live on TestFlight**
(carries wire-5 role gates + rc36), MARKETING_VERSION 0.16.0, WebRTC
pinned to 152 (upstream deleted 151's binary asset; see d61a880).
Note: the MCP `push_project` tool 413s on this repo (payload limit) —
always push with the CLI, which uploads out-of-band.

MCP-only tools (`publish`, `get_metadata`, `set_metadata`,
`upload_screenshots`) are reachable via the committed wrapper:

```sh
scripts/nomaccall get_metadata '{}'
scripts/nomaccall publish '{"confirm": false}'   # dry run, free
```

## The submission gate — VERIFIED 2026-09-01

`publish {confirm:false}` reports **26 blockers**. The tool returns
only a count; the per-item list is dashboard-only (App Store Connect →
Talon for Urbit → iOS App 1.0). The nomac API key is `56P3FUQDS7`
(its .p8 also sits in ~/Downloads, but the ASC *issuer ID* needed to
query the API directly is dashboard-only too).

### Already satisfied — VERIFIED

- **Listing copy complete and live on ASC**: name "Talon for Urbit",
  subtitle, full description, keywords, support/marketing URLs,
  promotional text. `whats_new` correctly null (not allowed on v1).
- **Privacy policy URL resolves** (PRIVACY.md on GitHub, HTTP 200).
- **Source review lint green**; Apple connection healthy.
- **Export compliance**: `ITSAppUsesNonExemptEncryption=false` already
  in Info.plist — answered automatically at submission.
- **Review notes drafted**: `docs/app-store-review-notes.md` — covers
  the no-account model (5.1.1(v)), UGC/moderation (1.2), encryption,
  and the AI-features disclosure. Needs only the demo ship values.

### Blocked on two user decisions

1. **Version number** (pick one):
   - *Store shows 1.0*: bump `MARKETING_VERSION` in
     iosApp/iosApp.xcodeproj (both configs) to 1.0 → one new build
     from quota. iOS-only; does not touch talon.versionName.
   - *Store shows 0.16.0*: edit the ASC version record 1.0 → 0.16.0
     in the dashboard → build #38 attaches as-is, no new build.

2. **The demo ship** — one asset serves both remaining hard blockers:
   a throwaway hosted ship (a moon works) with a few seeded
   conversations, reachable from the internet, whose URL + code go to
   Apple. It is the sign-in for App Review AND the content for
   screenshots. Do not use a personal ship: the code grants full
   access and the screenshots ship whatever conversations they show.

### Screenshots (required: APP_IPHONE_67)

The lazy correct path: sign into the demo ship from TestFlight build
#38 on any 6.9" iPhone (15/16/17 Pro Max class) — its native
screenshots are exactly 1320x2868, which Apple accepts as-is. Take
4–6: chat list, a channel with threads/reactions, a DM with an image,
settings/themes. Then upload:

The renderer is committed: `./gradlew :composeApp:desktopTest --tests
'*StoreScreenshots*'` writes five 1320x2868 PNGs to
`composeApp/build/store-screenshots/` (headless, staged demo data,
never a real ship). Upload takes `images: [{filename, data: base64}]`
(NOT `paths`), and the base64 set is ~1.4MB — too big for a shell
arg, so drive the MCP `upload_screenshots` tool over stdio from a
file, not via `scripts/nomaccall '<inline json>'`.

Accepted sizes (exact, one pixel off = rejected): 1320x2868, 2868x1320,
1290x2796, 2796x1290. Optional families: APP_IPHONE_65, APP_IPHONE_61,
APP_IPAD_PRO_3GEN_129 (required only if iPad support is declared).

### Dashboard-only items — recommended answers

These cannot be set through nomac (`set_metadata` covers listing text
and screenshots only — schema verified). One ASC dashboard session,
~10 minutes:

- **App Privacy (nutrition labels)**: "Data Not Collected" — truthful
  per PRIVACY.md: no analytics, no telemetry, no developer backend.
  (User-configured AI providers and the user's own ship are not
  developer collection.)
- **Category**: Social Networking.
- **Age rating questionnaire**: answer None to all content categories;
  select "Unrestricted Web Access" = NO (Talon renders links/previews
  but has no browser). Private invitation-based chat should land 4+ or
  12+; if Apple pushes back citing UGC, 17+ is the fallback Tlon-style
  rating.
- **Content rights**: does not use third-party content.
- **Pricing**: Free, all territories (or trim territories to taste).
- **App Review Information**: contact name/phone/email + the demo ship
  URL and code, plus the notes from docs/app-store-review-notes.md.

### Submission

When the dashboard items are done and screenshots uploaded:

```sh
scripts/nomaccall publish '{"confirm": false}'   # expect 0 blockers
scripts/nomaccall publish '{"confirm": true}'    # IRREVERSIBLE — 1-3 day review
```

## Tooling notes worth keeping

**iOS cannot be verified on the Linux dev box.** `compileKotlinIosArm64`
and `linkReleaseFrameworkIosArm64` both report `BUILD SUCCESSFUL` while
producing no framework at all — they are no-ops here. The `ios-compile`
job in `.github/workflows/test.yml` runs a real Xcode archive on macOS;
nomac's `build --smoke` is the other real check and the only one that
compiles the Swift in iosApp/. `release.yml` never touches iOS.

**SPM binary deps can rot upstream.** stasel/WebRTC deleted the
151.0.0 release whose asset 151.0.1's manifest referenced, which broke
every iOS build at *package resolution* — surfacing as an inscrutable
`showBuildSettings` failure in release logs. If an iOS build fails
before compiling anything, run a smoke build and read its logs.
