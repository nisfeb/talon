# Dictate to orrery — least-friction voice capture

Status: approved 2026-10-15 (sneagan). Android-only feature. Built in
phases; this doc is the design of record.

## As built (2026-10-16)

Built on `feat/dictate-to-orrery` and run on the owner's Pixel 10 Pro (GrapheneOS, Android 17) through a side-by-side test install. The build moved on from the design below in these ways, most of them at the owner's word during the device run.

### Ways in

- **One card for every way in.** `QuickDictationActivity` is what the home-screen widget, the lock-screen widget, the Quick Settings tile and the assist gesture all open. It is `showWhenLocked` in the manifest and starts `DictationService` while it is on screen (only a visible app may open the microphone). Code: `androidMain/.../QuickDictation.kt` and `QuickDictationActivity.kt`.
- **No VoiceInteractionService.** AOSP's `AssistantRoleBehavior` offers any app with an exported `ACTION_ASSIST` activity as the digital assistant, and SystemUI's `AssistManager` starts it with a plain `startActivityAsUser`. SystemUI opens a tile's or a lock-screen widget's activity over the lock screen when the manifest flag is set (`ActivityIntentHelper.wouldPendingShowOverLockscreen`, `startPendingIntentMaybeDismissingKeyguard`).
- **The widget** is a round Talon mark with its name under it, a fixed 56 dp, for the home screen and the lock screen (`widgetCategory="home_screen|keyguard"`, the glanceable hub). The tile wears the same mark, traced from the launcher's monochrome layer, so it is not the system's mute microphone.

### Where the words go (sneagan: "can that be the default behavior unless the message is detected to be a question?")

- **A plain calendar question is answered on the phone** (`CalendarQuestion`): "what's on today / tomorrow / Friday / this week / next week / this weekend", from the calendar read while the owner talks. No model: on the phone two model calls had taken 18 s of a 21 s answer.
- **Any other question** (whisper writes it with a "?", unless it is a polite request) goes to a read-only agent with its own short prompt and the 24 tools a dictation uses (`DictationAgent.askPrompt`, `readsFor`). It runs on the Dictation AI feature: the assistant's model until one is picked on this device or named "dictation" by an Armillary vendor, under the assistant's ZDR terms while it follows. The feature never syncs, since an older install drops a profile it cannot read.
- **Everything else goes to orrery's instruct route without "do it"** (`DictationFlow`), so orrery's model tells a command from a todo and orrery's policy decides what waits. Over 2000 bytes it is read instead. With Orrery off, the agent acts on the words itself, as first built.

### Approval (sneagan: "interactive approval immediately so that I can approve a dictated message in the car")

- What orrery leaves waiting is shown on the same card, drawn as what it is (a message with whom it goes to and a bubble, an event with when and where, a task with its due), with Send and Don't send. The card asks aloud and then listens a few seconds for "send" or "cancel" (a strict list, so a stray word never sends). On a locked phone anything that reaches another person needs the fingerprint first (`requestDismissKeyguard`). When the card is not in front, a heads-up notification asks with the same two actions, its Send behind authentication.
- The owner's policy on ricsul auto-approves nothing (`"auto": []`), so a todo asks too. Adding `task` (and `fact`) to orrery's auto list makes them land with no question. Talon does not approve on the policy's behalf.

### The card

- Large type for a car. Listening shows a microphone that swells with the voice, and working says what it is doing ("Asking orrery…"). The answer renders markdown with each @p, link and looked-up name tappable, and draws what it read: a day's events with times and places, tasks, people, messages and mail, each opening where it lives in Talon. Follow up dictates again in the same context.
- **The highlights are spoken** on the phone's own text to speech (`DictationSpeech`): an answer opens with one sentence written to be heard, and only that is said. A Speak answers switch is in Settings.

### Speed

- The setup starts when listening does (`Loops.prepareDictation`): the ship's channel, mail and calendar attached without polling, the calendar read once and lighter. Only the calendar's tools wait for it.
- A turn whose writes all reported success ends without a second model call (`DictationAgent.plainly`).

### Failures

Every outcome is a notification (`DictationOutcome`) and a line on the card. A failed transcription keeps the audio and a failed run keeps the words, each behind "Try again". The line says what failed in words, the error whole behind Copy details.

End of speech is an energy gate (`EndOfSpeech`): 1.8 s of quiet after speech, 8 s with none, 2 minutes at most. On the Pixel a voice peaked at 606 to 1226 and room noise reached 244, so the 500 minimum holds.

## Goal

The fewest possible actions to get a spoken thought processed into
orrery. Ideally: wake a surface, speak, done — no confirmation, no
navigation, nothing after the words. The phone may be asleep.

## Decisions (sneagan, 2026-10-15)

- **Writes are fully autonomous.** The dictation runs the agent and it
  writes to orrery with no confirmation and no undo prompt — the same
  way a scheduled Loop writes unattended. The result notification is the
  only after-the-fact signal.
- **Dictation engine: record + transcribe, not the system recogniser.**
  Whether Android's `ACTION_RECOGNIZE_SPEECH` resolves on the owner's
  Pixel 10 Pro / GrapheneOS (no Google) is unconfirmed, so the feature
  does not depend on it. It records audio itself and transcribes with
  Talon's existing transcription (`CallTranscribe`, whisper-1 / the
  configured provider). A later optimisation may prefer the system
  recogniser when one is present, but the record-and-transcribe path is
  the floor that works without Google.
- **Three entry surfaces, phased:** home screen widget, then Quick
  Settings tile, then assist-app.

## Architecture — reuse the headless Loops runner

The autonomous, no-confirmation path already ships: `LoopRunner`
(commonMain) runs the agent with `ToolCatalog.default(...) +
actionTools(actions)` and executes writes directly (the assistant
*screen* confirms writes; the loop path does not). `Loops.kt`
(androidMain) already provides the wake-lock, foreground execution, the
one-device write `coordinator` (the %settings lease), the cold-fire
channel wait, and the result `Notifications.showLoop(...)`.

A dictation is therefore a **one-shot headless run**: take the
transcribed text as the user turn, run it through a LoopRunner-style
execution with the orrery/loop system prompt and write tools authorised,
and post the agent's summary as a notification. No new agent path, no
new confirmation model — a new *trigger* for an existing runner.

New shared piece: `DictationRun` (androidMain, beside `Loops`) — given
text, build the one-shot run and fire it on the Loops foreground/wake
infrastructure. The text→orrery behaviour is testable against `FakeShip`
(the brain), independent of any UI surface.

## Engine — record + transcribe

1. A surface opens a mic capture (foreground service with the
   `microphone` type; `RECORD_AUDIO`). Over the keyguard for the tile
   and assist paths, so no Activity is required to hold the mic.
2. On stop (silence or release), the PCM is handed to Talon's
   transcription (`CallTranscribe` / the configured whisper model).
3. The transcript becomes the `DictationRun` prompt.

Caveat: whisper-1 is a cloud call, so dictation needs network. On-device
transcription is out of scope here. If a system recogniser is present
and the owner prefers it, a fast path can skip the record+transcribe
round trip — not in the first build.

## Surfaces (Android-only; gated by `isQuickDictationSupported`)

- **Phase 1 — Home screen widget.** An `AppWidgetProvider` (model on
  `widget/ClockWidgetProvider`) whose tap launches a transparent
  trampoline that starts capture → transcribe → `DictationRun` → notify.
  Lowest risk, no new system role.
- **Phase 2 — Quick Settings tile.** A `TileService` reachable from the
  lock screen (one swipe + tap while asleep). Same capture→run→notify.
- **Phase 3 — Assist app.** Register Talon as the device's digital
  assistant (`VoiceInteractionService` / `ACTION_ASSIST`) so the assist
  gesture / long-press-power opens straight to dictation over the
  keyguard. This is the "hold a button while asleep" answer — literal
  hardware-button remapping is not an app capability. Cost: Talon
  becomes the default assistant, displacing the current one; largest of
  the three. Phased last.

## Platform discipline

- `expect val isQuickDictationSupported` in `Capabilities.kt`: Android
  true, desktop / iOS false. Any shared UI that offers "set up quick
  dictation" gates on it.
- The widget, tile, `VoiceInteractionService`, capture foreground
  service, and trampoline live in `androidMain` with the required
  "Android-only: no desktop analog" top-comment.

## Testing

1. **The brain, end to end:** `DictationRun` given a canned transcript,
   with a fake completer that calls `orrery_observe`, writes to
   `FakeShip` — assert the orrery poke lands and the notification
   summarises it. This proves text→orrery without a device.
2. **Pure glue** (transcript cleanup, empty-transcript guard) in
   commonTest.
3. **On device (owner, not CI):** does the recogniser-free capture work
   on the Pixel / GrapheneOS; does the widget tap wake+run; does the
   tile fire over the keyguard; does the assist registration bind to a
   gesture. None of this is runnable here (no device, GrapheneOS
   specifics) — say so in each phase's gap report.

## Risks / unknowns

- Recogniser availability on GrapheneOS → why we record+transcribe.
- Transcription needs network (cloud whisper); an offline dictation
  fails with a clear notification, nothing silently lost.
- Mic over the keyguard needs the microphone foreground-service type and
  may prompt for `RECORD_AUDIO` the first time (one-time, not per use).
- The assist-app role is a real commitment (default-assistant slot).
- A fully autonomous wrong write is the failure mode the owner accepted;
  the result notification is the mitigation.
