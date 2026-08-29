# talon-bridge

A headless party-line participant. It logs into a ship, joins a
Trunkline party line, and moves audio between that line and a
pluggable PCM source/sink. No window, no sound card, nothing to click.

Today the source and sink are WAV files — play a file into a line,
record a line to disk. The recording is the point, not a stepping
stone. Anything else that produces or consumes PCM (Icecast, SIP, a
TTS feed, another party line) is a different implementation behind the
same two interfaces in `Pcm.kt`.

## It has its own ship

Tickets are short-lived and minted per member, so the bridge asks for
one the way any client does. Put its ship in the group and it is a
real member: the host's membership check works unmodified, it appears
in the roster as itself, and it can be kicked. Nothing in `%trunk`
special-cases it.

That is also the consent story. A bridge on the line is visible to
everyone on the line, the same way a listen link is. It cannot join a
line its ship was never invited to.

## Running it

```sh
./gradlew :bridge:installDist
bridge/build/install/talon-bridge/bin/talon-bridge [config.properties]
```

Configuration comes from the environment first, then a properties file
(`bridge.properties` by default, or the path given as the first
argument):

| Environment | Properties file | Meaning |
|---|---|---|
| `TALON_BRIDGE_SHIP_URL` | `talon.bridge.ship.url` | The bridge's own ship |
| `TALON_BRIDGE_SHIP_CODE` | `talon.bridge.ship.code` | That ship's `+code` |
| `TALON_BRIDGE_HOST` | `talon.bridge.host` | Who hosts the line |
| `TALON_BRIDGE_ROOM` | `talon.bridge.room` | The line's name |
| `TALON_BRIDGE_PLAY` | `talon.bridge.play` | WAV to play into the line |
| `TALON_BRIDGE_LOOP` | `talon.bridge.loop` | Repeat that file |
| `TALON_BRIDGE_RECORD` | `talon.bridge.record` | WAV to record the line to |

Set `PLAY`, `RECORD`, or both. Neither means the bridge has nothing to
do, and it says so rather than sitting there.

The `+code` is the one value worth keeping out of a shell history, so
prefer the properties file or a systemd `EnvironmentFile=`.

Stop it with Ctrl-C or `SIGTERM`. A recording's WAV header carries its
own length, which is only correct once the file is closed — the
shutdown hook is what does that, so killing it with `SIGKILL` leaves a
file most players will still play but whose length field reads zero.

## Audio

Input WAVs may be any rate and channel count as long as they are
uncompressed 16-bit PCM; they are resampled (nearest-neighbour) and
fanned to what WebRTC asks for, which in practice is 48kHz mono.
Recordings are written at whatever the line delivers.

Every remote speaker arrives as a separate stream on its own thread,
so `BridgeAudio` sums them into 10ms slabs on one clock. That is a
mixer with no jitter buffer: alignment is good to ±10ms, which a
recording does not notice and a broadcast would not either.

## The part that is not obvious

webrtc-java's `AudioDeviceModule` has `setAudioSink` and
`setAudioSource`, and they look exactly like the injection points for
a headless process. They are not: they stop firing the moment a
`PeerConnectionFactory` takes the module over, because they drive the
standalone `AudioRecorder` / `AudioPlayer` helpers instead. A bridge
wired that way connects, negotiates, reports itself healthy, and
transmits silence.

What works, and what `AudioPathTest` pins by moving real samples
across a real peer connection:

```
CustomAudioSource.pushAudio  → what we say into the line
AudioTrack.addSink → onData  ← what the line says
```

`HeadlessAudioDeviceModule` still earns its place — it stops WebRTC
opening a sound card on a machine that may not have one — and it is
also what loads the JNI library, so it has to be constructed before
anything else in the media stack.
