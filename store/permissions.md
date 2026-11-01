# Permission rationale — Play Console

For each "sensitive" permission the app declares, Play Console will
ask why. Paste these into the Permissions Declaration form.

## RECORD_AUDIO

Talon supports voice messages. Audio is recorded only after the user
explicitly taps and holds the microphone button in the composer; the
recording is uploaded to the user's Urbit ship's blob storage and
shared as a chat attachment. The app never records in the background
and never accesses the microphone outside of the active push-to-record
gesture.

## ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION

Location is read in three places, each off until the user turns it on:

- The `/loc` slash command shares the user's current location as a chat message. Location is read once per use and put in the message as latitude and longitude plus an OpenStreetMap link.
- "Send where I am" (Settings, AI, Orrery) sends the phone's position to Orrery on the user's own ship when the phone moves a few hundred metres, for leave alerts.
- While the user is on the way to an appointment, or shares their live location with another ship, the phone sends its position each minute.

The app keeps no history of positions on the phone. Orrery on the user's ship keeps the latest one only.

## ACCESS_BACKGROUND_LOCATION / FOREGROUND_SERVICE_LOCATION

"Send where I am" hears a move with the app closed, so it asks for location all the time. A trip or a live share runs as a location foreground service with an ongoing notification that says so and has a Stop button.

## REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

Talon maintains an SSE event stream to the user's Urbit ship to
deliver real-time notifications. On many devices Doze mode breaks
that stream within minutes; this permission lets the user opt the
app out of battery optimizations so message notifications arrive
promptly. The exemption is granted only after the user accepts the
system dialog the app shows on first launch.

## FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC

The app runs a foreground sync service (`TalonSyncService`) while it
is in the background, with `dataSync` type. The service exists solely
to keep the Urbit ship's HTTP/SSE channel open so push notifications
fire when new messages arrive. The user sees the standard ongoing
notification while the service is active.

## POST_NOTIFICATIONS (Android 13+)

Used to display new-message notifications for conversations the user
isn't currently viewing. Per-conversation mute controls live in
Settings.

## VIBRATE

Haptic feedback on long-press, drag, and reaction-pick interactions.
No background usage.
