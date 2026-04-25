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

Talon has a `/loc` slash command that lets the user share their
current location as a chat message. Location is read once per command
invocation and embedded in the message body as latitude/longitude
plus an OpenStreetMap link. The app does not poll, log, or otherwise
retain location data, and never reads location outside of an explicit
`/loc` invocation.

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
