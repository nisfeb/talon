# iOS background calls (CallKit + PushKit VoIP)

How incoming 1:1 calls ring an iPhone like a normal phone call, even
when Talon is backgrounded or killed. This is the runbook to finish
and turn it on; the code lives on branch `feat/ios-voip-calls`.

## How it works

1. The callee's iPhone registers a **PushKit VoIP token** and hands it
   to the relay (Settings → relay panel) with platform `ios-voip`.
2. A caller pokes `%ring`; the callee's ship gives it on `%trunk`
   `/calls`. The **relay** hears that (it subscribes to `/calls`) and,
   for an `ios-voip` device, sends an **APNs VoIP push** instead of a
   UnifiedPush POST.
3. iOS wakes the app (even if killed) and delivers the push to
   `CallPush.swift`, which **must** report the call to CallKit
   immediately — the phone rings on the lock screen.
4. Answering routes through `IosVoipBridge` → `CallController`.

Everything downstream of a delivered ring already existed; this branch
added the relay APNs leg and the iOS native front half.

## What's built (branch `feat/ios-voip-calls`)

- **Relay**: `Apns.kt` (ES256 `.p8` JWT, `apns-push-type: voip`),
  routed by device platform. Tested (`ApnsTest`). Off until the
  `APNS_*` env vars are set.
- **iOS**: `CallPush.swift` (PushKit + CallKit), `Talon.entitlements`
  (`aps-environment`), `voip` background mode, `IosVoipBridge` +
  `IosPushTokenProvider`, and the `bindNativeCallActions` seam into
  `CallController`.
- **CallKit both ways** (2026-09-06): incoming calls were already
  reported from the push; now every outgoing call and party line is
  reported too (`IosCallKitCalls` in `NativeCallActions.ios.kt` →
  `IosVoipBridge.callKit` → `CallPush.swift` as `IosCallKit`), an
  in-app answer or hang-up updates CallKit, and the system's
  mute/hold actions land on the call or line (hold ⇄ mute, since
  `%trunk` has no hold). The iOS half of Android's `TelecomCalls`.
  Device checks: outgoing 1:1 shows in the system UI and on a watch;
  a party line does; a cellular call arriving mid-call mutes us and
  resume unmutes; the CallKit mute button and ours stay in step.
- **Recents**: every call is in the Phone app's Recents (CallKit's
  default). A ring nobody answered ends as `.unanswered`, which
  Recents files as Missed. The reported handle is the ship and the
  shown name the nickname, so a tap on an entry comes back as an
  `INStartCallIntent` (declared in Info.plist, handled in
  `iOSApp.swift` → `AppDelegate.callBack`) and places the call.

## What remains — in order

### 1. Apple Developer portal (you)
- Enable **Push Notifications** on App ID `io.nisfeb.talon`.
- Create an **APNs auth key** (Keys → +): download the `.p8` once,
  record the **Key ID** and your **Team ID**. The same key signs VoIP,
  so no separate VoIP Services certificate is needed.

### 2. Prove the signed build (you + a build)
- Cut a signed release build of the branch. This is also the test of
  whether nomac's managed signing accepts the push entitlement — the
  one gating unknown. If it signs, the capability is supported.

### 3. Relay (asimov)
- Put the `.p8` on the host and set `APNS_TEAM_ID`, `APNS_KEY_ID`,
  `APNS_P8_FILE`, `APNS_BUNDLE_ID`, `APNS_PRODUCTION=true`, then
  redeploy. See `relay/README.md` → "APNs VoIP".
- Startup logs `APNs VoIP enabled …` when it's picked up.

### 4. Device test
- Install the build, open **Settings → relay panel**, register (sends
  the VoIP token as `ios-voip`).
- From another ship, call the iPhone. Confirm it rings backgrounded,
  then killed. Confirm answering connects.

## The one code gap: cold-launch answer

The warm path (app alive, controller already holds the ring) is wired:
`accept`/`reject` act on it. A **killed** app woken purely by the push
has no controller state for the call — the ring came over APNs, not
the suspended `/calls` channel, and the caller's one-shot `%offer` may
already have passed. Joining from cold needs the caller to **re-offer
when the callee answers**, a small `%trunk` protocol addition. Until
that lands, a cold answer rings and connects the CallKit UI but does
not join media. This is device-validation work — see
`bindNativeCallActions` in `NativeCallActions.ios.kt`.

## APNs environment

`APNS_PRODUCTION=true` for TestFlight and the App Store (they use the
production APNs host and a production `aps-environment`). A development
build signed with a development profile uses sandbox — set it `false`
and match the entitlement.
