# bridge/ — agent guidance

The bridge is a headless party-line member (`talon-bridge [config]`)
and, with `--ui`, the Party Manager window. JVM only, Linux audio
only. User guide: `docs/party-manager.md`. Headless details:
`README.md` in this directory.

## Map

- `Main.kt` CLI entry; `--ui` hands off to `ui/PartyManager.kt`.
- `Runner.kt` `BridgeRunner`: connect (login, load lines, wait for the
  calls subscription), join, leave, stop; `status` and `lines` flows.
- `Pulse.kt` everything `pactl`: device names, JSON parsing, moves,
  volumes, loopbacks. `Meter.kt` a `parec` level meter.
- `Extras.kt` `Ducker`, `Presets`, `NowPlaying` (MPRIS over gdbus),
  `ShowRecorder`, `HostMic`, `notify`.
- `DefaultClips.kt` synthesized soundboard clips. `BridgeAudio.kt`,
  `Pcm.kt`, `Wav.kt` the file mode (play/record a WAV).
- `ui/PartyManager.kt` the whole window; one file on purpose.

## Rules that are not obvious from the code

- **Ask for a line only after `CallController.connected` is true.**
  The wire version is scried before the subscription exists. As its
  own host the grant returns in under a millisecond and a fact with
  no subscriber is dropped, which shows up as "the host didn't
  answer". Do not wait on `wire` for this.
- **Device mode uses the real `AudioDeviceModule` on the Pulse default
  devices**, then moves its own streams with `Pulse.routeOwn` matched
  by `application.process.id`. Pushing PCM into a custom audio
  device module while the ADM also runs aborts libwebrtc with
  `audio_send_stream.cc RaceDetected`. The file mode's
  `HeadlessAudioDeviceModule` is only for file mode.
- **The factory is built once per process.** `useAudioDeviceModule`
  throws after that; the runner wraps it in `runCatching` so a second
  connect in the window keeps the first ADM.
- **Device mode turns speech processing off and asks for 128 kbps**
  through `DesktopWebRtcFactory.audioProcessing` and
  `opusMaxAverageBitrate`. Music through a microphone pipeline is
  what "the audio quality goes way down" was. Real microphones in the
  Talon app keep the defaults.
- **Match a program's streams by pid, not name.** Chromium's capture is
  "Brave input" and its playback "Brave". `Stream.sameApp` does this;
  `appName` strips " input" for display.
- **Hide helpers from the app lists**: `parec`, `paplay`, the loopback
  apps (`TalonBridgeLoop`, `TalonBridgeHostMic`) and the combine sink's
  "Simultaneous output on …" feeds. Anything new that opens a Pulse
  stream needs a name in that filter.
- **Who hears what**: `SPACE` and `SPACE_IN` reach the party, `MIC`
  reaches the Space, `BOTH` reaches both. The Space app plays into
  `SPACE_IN` so its monitor is pure Space voice for the meter and
  ducking. `Pulse.partyTargets` and `virtualTargets` are the sets to
  use; do not hand-roll them.
- **Ducking keys off member speaking flags for the party** and the
  `SPACE_IN` meter for the Space. Metering `SPACE` or `MIC` would
  trigger on the music being ducked.
- **Presets are re-applied only to streams not seen before** (`seen`
  in the screen). Applying to every stream each second would fight
  the user's chips.
- **The `+code` lives only in `~/.config/talon/bridge.properties`.**
  Never print it, log it, or put it in a commit, a test, or a
  transcript. `Config.toString` omits it on purpose.
- **Do not restart the manager under a live Space unless asked.**
  Restarting drops the bridge off the line and the Space goes silent
  until someone clicks Join again.
- **The bridge shares Talon's Pulse application name.** libwebrtc names
  its streams "WEBRTC VoiceEngine" in every app and ignores the
  `PULSE_PROP_*` environment (it sets the name itself). Pulse restores
  devices per application name, so routing the bridge onto the virtual
  devices once routed the Talon desktop app there too (a silent party
  line). `Pulse.routeOwn` therefore calls `forgetDevices` after every
  move: a throwaway pacat/parec stream under that name, moved to the
  defaults, rewrites the memory. Keep that, and keep pacat/parec in
  the helper filter.
- **Pulse remembers the last device per app.** After testing, move
  the browser's streams back to `@DEFAULT_SINK@` / `@DEFAULT_SOURCE@`
  (the Normal chips) so the user's browser is not left on the party
  monitor.

## Building and checking

```
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :bridge:installDist
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :bridge:test
```

`AudioPathTest` (file mode through a real WebRTC link) fails on some
machines with the libwebrtc race above; it is not a regression signal
by itself. `PulseParseTest`, `ExtrasTest` and `DefaultClipsTest` are
the pure checks. The pactl JSON shape is `pactl -f json list
sink-inputs`; extend `PulseParseTest`'s fixture when you read a new
field.

Verify audio by hand with the same tools the code uses: `paplay
--device=TalonBridgeMic clip.wav` while `parec --device=TalonBridgeMic.monitor`
samples the monitor. Kill the bridge with a pattern that cannot match
your own shell, for example `pat="talon-bridge/li""b"; pkill -f "$pat"`.
