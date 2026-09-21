# Talon — Privacy Policy

_Last updated: 2026-09-19_

Talon is a native Android client for the Urbit network. It connects to
a user-supplied Urbit ship over the network and renders chat / DM
messages produced by the `tlon-apps` agents (`%channels`, `%chat`,
`%groups`, `%activity`, `%contacts`, `%settings`). This document
describes what Talon does (and doesn't) do with user data.

## Who controls your data

- **Your Urbit ship** is the canonical store of every message,
  contact, group membership, and reaction. Talon is a thin client; it
  does not have its own server.
- **The developer of Talon** does not operate any backend, does not
  collect telemetry, and does not have access to user data of any
  kind.
- Optional AI features (catch-me-up summary, emoji suggestions) hit a cloud provider (OpenAI, Anthropic, or a custom OpenAI-compatible endpoint) **only when the user has configured an API key**. The request body, which is the message text the user is summarizing, is sent to that provider. Nothing is sent to the developer of Talon.
- The optional **Armillary** provider is a second way to reach a model, and it is off until the user adds it. Instead of a key of their own, the user buys model inference through their own Urbit ship, from a vendor ship they choose. The ship holds the account and the balance. The user pays that vendor by card through Stripe or by bitcoin through BTCPay Server, both operated by the vendor. In lease mode the vendor mints a capped key at the model provider and Talon calls that provider directly. In proxy mode the requests pass through the vendor's ship. The vendor sees the ship's name, the balance and the ledger, and in proxy mode the requests as well. Nothing else about the user leaves the device, and nothing reaches the developer of Talon.

## What Talon stores on your device

- **Urbit session cookie** (`+code` login → ship URL + cookie). Stored
  in Android's encrypted preferences via
  `androidx.security.crypto.EncryptedSharedPreferences`.
- **Chat history mirror** — messages, reactions, unread counts, group
  metadata, contacts. Cached in a local Room (SQLite) database so the
  app can render offline. Per-ship database file; cleared if the user
  removes the app or signs out.
- **AI provider settings** (provider, model, API key, base URL). An API key is stored encrypted via `EncryptedSharedPreferences`. The key is sent only to the configured provider, only when the user invokes an AI feature. An Armillary provider stores no key the user typed: the key is minted by the vendor, fetched from the user's own ship, and held for that ship and that device alone. It never travels between devices.
- **Watchwords** — terms the user wants highlighted in chat. Stored
  locally; never transmitted.
- **Daily digest history** — the rolling list of past summaries the
  app has produced for the user. Stored locally; the underlying
  message text is sent to the configured AI provider only at digest
  generation time.
- **Drafts** — unsent messages typed in the composer, kept locally so
  the app can restore them on relaunch.

## What Talon does NOT do

- No analytics. No third-party SDK collects usage / crash data.
- No advertising identifiers, no fingerprinting.
- No background sync to a Talon server (there isn't one).
- No data shared with the developer.

## Permissions Talon requests, and why

| Permission                       | Why                                            |
|----------------------------------|------------------------------------------------|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Connect to the user's Urbit ship.            |
| `POST_NOTIFICATIONS`             | Push new-message banners (when the app is in the background). |
| `VIBRATE`                        | Haptic feedback on long-press / drag.          |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep the SSE event stream alive while the app is in the background, so notifications fire promptly. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Let the user opt the app out of doze restrictions for reliable real-time message delivery. |
| `RECORD_AUDIO`                   | Voice messages (user-initiated, never recorded in the background). |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` | The `/loc` slash-command shares the user's current location as a chat message (user-initiated, single-shot fix per use). |

## Third parties

The only third-party servers Talon talks to are:

- **The user's Urbit ship**, at the URL the user enters.
- **The user's chosen AI provider** (only if AI is configured and a feature is invoked). See the provider's own privacy policy for what they do with submitted prompts: <https://openai.com/policies/privacy-policy>, <https://www.anthropic.com/privacy>.
- **The vendor ship the user named in Armillary**, and the payment page that vendor sends the browser to (Stripe, or a BTCPay Server the vendor runs). Only if the user added the Armillary provider. Removing the provider ends it.

## Children

Talon is not directed at users under 13.

## Changes

If this policy changes, the new version will be reachable at the URL
distributed in the Play Store listing.

## Contact

[ FILL ME IN — email or a link to a public issue tracker for privacy
questions before submitting to Play Store. ]
