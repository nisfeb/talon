# Party Manager

The Party Manager is a desktop window that runs the Talon bridge and
manages a party: which app is the X Space, what the party and the
Space hear, how loud each source is, ducking under speech, presets,
a soundboard, a show recording, and a host microphone. It lives in
the `bridge/` module and is Linux only, because everything audio is
PulseAudio (or PipeWire's Pulse layer) driven through `pactl`.

## Requirements

- Linux with PulseAudio or PipeWire-Pulse: `pactl`, `paplay` and
  `parec` on the path.
- JDK 21 to build and run (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`).
- A ship of its own for the bridge, in the group that hosts the
  party line. The bridge joins as that ship; do not run it as a ship
  that is also on the line from Talon, or you will hear yourself.
- Optional: `gdbus` for now playing, `notify-send` for watchdog
  notifications. Both come with a normal desktop.

## Running it

```
./gradlew :bridge:installDist
scripts/talon-party-manager            # ~/.config/talon/bridge.properties
scripts/talon-party-manager other.properties
```

`talon-bridge --ui [config]` is the same thing. Without `--ui` the
bridge runs headless from the file, which is what `bridge/README.md`
describes.

The config file holds `talon.bridge.ship.url`, `talon.bridge.ship.code`,
`talon.bridge.host` and `talon.bridge.room`. The window writes the
first two on Connect and the last two on Join. Keep the file outside
the repo: it holds a `+code`.

## The window

**Header.** The party line (title once known, else host/room) with a
status pill, the X Space with its app and a pill (Not wired, One-way,
Two-way, or Wired with the bridge off), what a media player is
playing right now, and red watchdog banners.

**Bridge.** Ship URL and `+code`. With saved values it connects at
launch. Connected, a dropdown lists every party line the ship can
reach, hosted or invited, by title. Join the line, Leave the line
(stays logged in), Disconnect. Live, it shows the member list with a
Mute chip per member (host moderation, live and persisted on the
host), a chip for the bridge's own mute, and a Listen link button.

**X Space.** Apps using a microphone. Mark the browser running the
Space as the Space app: its microphone becomes the party and its
sound goes to the party. Two meters show audio moving Party → Space
(everything the Space app's microphone gets) and Space → Party (the
Space's own voice), each with a slider for that direction's level.
Ducking dips every other app on the party or the Space while a
member speaks or the Space talks.

**Presets.** The whole routing by name: the Space app, each app's
target and volume, the direction levels and ducking. Apply, Delete,
or save the current routing under a name. The active preset is
re-applied to an app that comes back with new streams, so a
restarted browser gets rewired on its own.

**Apps playing audio.** Every playing app with a volume slider and
chips: Party (the line hears it), Space (the Space hears it), Both,
Normal (back on your speakers). Paused apps are marked.

**Soundboard.** Clips from `~/.config/talon/soundboard` (wav, ogg,
flac), each with Party, Space and Both buttons, a clip level and Stop
all. Seven synthesized clips are written there on first run while
the folder is empty.

**Record the show.** A stereo WAV in `~/.config/talon/recordings`:
left is what the Space hears, right is what the party hears from
this machine.

**Host mic.** A real microphone straight into the Space, the party,
or both, through a muted loopback. Hold F8 in the window to talk or
switch Talk on.

## Setting up a Space, step by step

1. Start the manager. It connects with the saved ship.
2. Pick the party line and click Join the line.
3. Open the Space in a browser and join it with the microphone on.
4. In the X Space panel, click Space app next to that browser. The
   header pill goes Two-way once the bridge is on the line.
5. Talk on the party: the top meter moves and the Space hears it.
   Have the Space talk: the bottom meter moves and the party hears
   it.
6. Save the routing as a preset so it comes back next time.

Music: play it in any app, click Party, Space or Both on its row,
set its slider, and turn on ducking.

## What it writes

| Path | What |
|---|---|
| `~/.config/talon/bridge.properties` | ship URL and `+code`, last line |
| `~/.config/talon/party-presets.json` | presets and the active one |
| `~/.config/talon/soundboard/` | clips |
| `~/.config/talon/recordings/` | show recordings |

Pulse devices it loads: `TalonBridgeMic` (bridge playout, the Space
app's microphone is its monitor), `TalonBridgeSpace` (the bridge
captures its monitor), `TalonBridgeSpaceIn` (the Space app plays
here; a loopback feeds it into `TalonBridgeSpace`), `TalonBridgeBoth`
(a combine sink of Mic and Space). They stay loaded until the Pulse
server restarts; that is harmless.

## Troubleshooting

- **"the host didn't answer" on Join.** The bridge asks only once its
  calls subscription is up, so this now means the host really did
  not answer within fifteen seconds. Check the ship is in the group
  and try again.
- **Not wired / One-way in the header.** Click Space app on the
  browser again; a restarted browser has new streams. With an active
  preset this happens by itself.
- **The Space sounds processed.** The browser applies its own
  microphone processing to what the party sends it. That leg is the
  browser's. Music into the party is sent at 128 kbps with no speech
  processing.
- **Put a browser back to normal.** Click Normal on its rows. Pulse
  remembers the last device per app, so do this rather than just
  closing the manager.
- **Nothing in Apps playing audio.** Only apps with an open stream
  show. Press play in the app first.

## Limits

Desktop Linux only. Phones cannot rewire another app's audio, so a
mobile version could at most remote-control this one. Voice effects,
soundboard hotkeys outside the window, and live captions are not
built.
