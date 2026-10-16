# Dictate to orrery — least-friction voice capture

Status: approved 2026-10-15 (sneagan). Android-only feature. Built in
phases; this doc is the design of record.

## As built (2026-10-16)

All three surfaces are built on `feat/dictate-to-orrery`. The build differs from the design below in four places.

- **One activity, not three paths.** `QuickDictationActivity` is the card the widget, the tile and the assist gesture all open. It is declared `showWhenLocked` in the manifest, starts `DictationService` while it is on screen (only a visible app may open the microphone), and goes when listening ends. Code: `androidMain/.../QuickDictation.kt` and `QuickDictationActivity.kt`.
- **No VoiceInteractionService.** AOSP's `AssistantRoleBehavior` offers any app with an exported `ACTION_ASSIST` activity as the digital assistant, and SystemUI's `AssistManager` starts that activity with a plain `startActivityAsUser`. So the assist path is an intent filter, and no `RecognitionService` takes over the system's speech input. SystemUI opens a tile's activity over the lock screen when the manifest flag is set (`ActivityIntentHelper.wouldPendingShowOverLockscreen`).
- **A locked phone sends nothing to anyone.** While the device is locked, a dictation keeps only the writes the owner batches without asking (`ALLOW_ALL_TOOLS`) plus `orrery_file_text` and `orrery_instruct`: no mail, messages, shares, invites or deletes, since anyone holding the phone can speak to it. `DictationAgent.LOCKED_WRITES` is the one place to change this.
- **Failures keep what was said.** Every outcome is a notification (`DictationOutcome`). A failed transcription keeps the audio and a failed run keeps the words, each behind "Try again". Nothing is spent on a transcription when no model could act on it.

End of speech is an energy gate (`EndOfSpeech`): 1.8 s of quiet after speech, 8 s with none, 2 minutes at most. Its thresholds are the calibration knobs if the phone disagrees. Transcription is `CallRecordingPublisher.sttFrom`, the transcription key in AI settings. The run is `Loops.dictate`: the loop's tools plus orrery's (attached with `OrreryRepo.headless`), the dictation's own prompt, and the time.

Not verified on a device: the activity, service, tile, widget and assist binding compile and the APK builds, but none has run on the Pixel.

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
