# Talon — Plan

A native chat client for Urbit, designed to compete with Tlon on the
core daily-use loop (DMs + group chat channels) while being an order of
magnitude faster to open, scroll, and react.

Originally Android-only; now also ships for Linux, macOS, and Windows
via Compose Multiplatform. The Android app is the canonical
implementation; the desktop port reuses the shared `composeApp/`
module for screens, data layer, and Urbit protocol code.

Target user: someone who runs (or is hosted on) an Urbit ship and uses it
mostly for chat. If you need notebooks, galleries, boardrooms, or multi-pane
web UI, stay on Tlon.

## Why native

Tlon's ~1.5s channel-open stall on mid-tier Android is dominated by React
Native's measure/paint pass on a deep Tamagui provider tree. Discord proves
RN can be fast, but the Tlon tree specifically carries cost that is hard to
shave inside the existing architecture. Rewriting the hot paths in
Compose + LazyColumn is a direct path to sub-50ms channel open.

## Stack

- **Kotlin + Compose** — Jetpack Compose on Android, Compose
  Multiplatform on desktop. Material 3, dark mode day one.
- **Room** for SQLite (the same engine Tlon uses, just native bindings).
  KMP-aware so both Android and desktop targets share the schema.
- **OkHttp** for HTTP + Server-Sent Events
- **Coroutines + Flow** — Room exposes Flow, SSE events push into it, Compose
  collects. Pure reactive.
- **Coil** for images. **ExoPlayer** (media3) for video on Android;
  desktop hands video off to the OS default media app for now.
- **Gradle + KSP**. Manual DI, no Hilt (smaller cold start).

## Protocol layer

Urbit's channel API is JSON over HTTP — no noun parsing needed. Tlon's
%channels, %chat, %groups, %activity, and %contacts agents all speak
structured JSON. This is much easier than reimplementing Urbit itself.

1. `UrbitSession` — login with `+code`, hold session cookie, open long-poll
   SSE channel, track ack IDs.
2. `UrbitChannel` — subscribe / unsubscribe / poke / scry → returns
   `Flow<JsonElement>`.
3. `agents/` — typed wrappers for:
   - `ChatAgent` (%chat, DMs)
   - `ChannelsAgent` (%channels, group chat channels)
   - `GroupsAgent` (%groups, metadata + membership)
   - `ActivityAgent` (%activity, feed)
   - `ContactsAgent` (%contacts, profiles)

Reference implementation: the yap repo's protocol code already translates
the wire format in JavaScript. We port, not invent.

## Data layer

Tables: `posts`, `channels`, `groups`, `contacts`, `reactions`, `unreads`.

Write-through: SSE event → normalizer → Room upsert → Flow emits → UI
updates. No separate cache layer; SQLite is the cache.

All queries paginated (keyset preferred, `LIMIT ? OFFSET ?` fallback).

## UI

Three top-level screens:
- `Home` — channel + DM list
- `Chat` — one conversation
- `Profile` — basic read-only view

Chat list: `LazyColumn(reverseLayout = true)` with `@Stable` Post data
class. No `AnimatedVisibility` on rows. Composer is a plain `BasicTextField`
with attach + send; send = optimistic insert + poke.

Reactions: `ModalBottomSheet` with recent-emoji picker. Replies: inline
quote.

## Scope

**v1 (MVP, ~6 weeks):** auth, DMs, group chat channels (chat-type only),
send text + images, reactions, replies, edits, deletes, unread badges,
activity feed, read state, basic profiles.

**v2 (~+4 weeks):** push notifications (FCM + Urbit hark), message search,
group member list, image gallery view, block/mute.

**Not in v1 or v2:** notebooks, galleries, boardrooms, carousel/strobe
channels, onboarding / hosting flows, multi-ship sign-in UI.

## Milestones

| Week | Goal                                                                 |
|------|----------------------------------------------------------------------|
| 1    | Auth + SSE connection + dump %chat events to log                     |
| 2    | Room schema + Compose DM list + 1:1 DM screen (read-only)            |
| 3    | Send DMs, reactions, replies, edits, deletes                         |
| 4    | Groups + group chat channels                                         |
| 5    | Activity feed + read state + image upload                            |
| 6    | Polish, app icon, release to Play internal track                     |
| 7–8  | Dogfood + push notifications + search                                |

## Unknowns to kill week 0

1. **Image upload.** Tlon uses S3 presigned URLs (Memex). Confirm endpoint
   + format from the web client; prove one image upload from Kotlin.
2. **Push notifications.** Tlon's mobile push goes through
   `notify.tlon.io`. We likely can't reuse it — either run our own FCM
   relay that subscribes to %hark, or skip v1 push. Decide immediately.
3. **Self-hosted vs hosted ships.** Will the app authenticate against
   Tlon-hosted ships too, or only `+code`-authenticated nodes? Affects
   login flow.
4. **Send-post wire format.** Copy-paste a send from the Tlon web client
   (network tab) and confirm our JSON generator produces byte-identical
   output.

## Success criteria

- Cold-start to first rendered DM list: **under 800 ms** on a Pixel 8 Pro.
- Tap DM → rendered messages: **under 100 ms.**
- Send-to-visible latency (local echo): **under 50 ms.**
- APK size: **under 20 MB.**

If we hit those, we have something. If we don't, shelve it.
