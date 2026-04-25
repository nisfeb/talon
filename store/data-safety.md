# Play Console "Data Safety" form — Talon

The Play Console's Data Safety form is a series of yes/no
questionnaires. Below are the answers tailored to what Talon actually
does. Walk through the form with this open as a script.

## Does your app collect or share any of the required user data types?

**No.**

Rationale: Talon is a thin client. It talks only to (a) the user's own
Urbit ship at a URL the user provides, and (b) the user's chosen AI
provider (if and only if the user configured an API key and invoked an
AI feature). The Talon developer operates no backend and receives no
data.

If Play prompts a follow-up because of permissions:

### Does your app collect or share location data?

**Collects, not shares.**
- Type: Approximate location, Precise location.
- Optional: yes (only when the user issues `/loc` in a message).
- Used for: app functionality (sharing a one-shot location pin in a
  message).
- Sent off device: yes — to the user's Urbit ship as part of the
  message body. Not to the Talon developer.

### Does your app collect or share audio data?

**Collects, not shares.**
- Type: Voice or sound recordings.
- Optional: yes (voice messages — user-initiated push-to-record).
- Used for: app functionality.
- Sent off device: yes — uploaded to the user's Urbit ship's S3 / blob
  storage as a chat attachment. Not to the Talon developer.

### Does your app collect or share photos / videos / files?

**Collects, not shares.**
- Type: Photos, videos, files & docs.
- Optional: yes (attachment send + receive).
- Used for: app functionality.
- Sent off device: yes — uploaded to the user's Urbit ship's S3 / blob
  storage. Not to the Talon developer.

### Does your app collect personal info?

**Collects, not shares.**
- Type: Name (the user's Urbit `@p` ship name, e.g. `~sampel-palnet`).
- Optional: required (login).
- Used for: account management.
- Sent off device: only to the user's Urbit ship for authentication.
  Not to the Talon developer.

### Does your app collect messages?

**Collects, not shares.**
- Type: Other in-app messages (chat content the user types or
  receives).
- Optional: required.
- Used for: app functionality (the app IS chat).
- Sent off device: yes — to the user's Urbit ship. Not to the Talon
  developer. AI summaries: only if the user has configured a provider
  AND invoked the feature, the message text being summarized is sent
  to that provider.

### Does your app collect device or other identifiers?

**No.** No advertising ID, no Android ID query, no IMEI, no
fingerprinting.

### Does your app collect financial info / health info / contacts?

**No.**

## Security practices

- All data is encrypted in transit (HTTPS to the user's ship; HTTPS to
  AI providers if configured).
- Users can request data deletion: yes — by uninstalling the app
  (clears the local DB) and/or removing themselves from groups on
  their ship.
- Independent security review: no.
- Plays by Families Policy: no (not directed at children).
