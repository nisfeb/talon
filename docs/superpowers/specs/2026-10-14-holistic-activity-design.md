# Holistic Activity: one feed for everything that happened

Status: design, 2026-10-14. Not built.

## Why

Talon notifies about two kinds of thing. Chat events (mentions, replies, posts, reactions) come from the ship's `%activity` feed and have a home: the Activity screen. Everything else (an Orrery action proposal, a finished loop, an Armillary credit, a group invite, a DM request) fires a local notification and then has no in-app review surface, or a scattered one. The Activity screen is also carrying a fourth tab, Threads, that is not a notification at all but a followed-conversations collection.

The goal is one honest "here is what happened" feed. Activity becomes the whole notification history, chat and non-chat together, organised by domain. Threads moves to where conversations live.

## The organising rule

There is already one line that separates these, and it is the same line that decides local notification versus APNs push: a chat whom is push-covered and belongs to the `%activity` feed; everything else has no push path and is a local notification. That same line becomes the domain tag inside Activity. Chat whom goes to the Chat domain. Everything else is an Assistant or Account update. This is the rule the iOS notifier already encodes (`IosNotifier.notify`), so Activity and the notification layer agree by construction.

## Shape on screen

Activity stays a single screen, a single reverse-chronological feed, with a row of domain filter chips instead of the current four tabs.

- All: the merged timeline, newest first. Always present.
- Chat: the `%activity` events, one timeline. Always present. Mentions and Replies fold into this timeline; they do not get their own control. Today's separate Mentions and Replies focus views go away. A reader scans the Chat timeline, or All.
- Assistant, Account, and any later domain: present only when that domain currently has at least one retained (unpurged) notice. No live Assistant notice means no Assistant chip. This keeps the chip row honest: it reflects what is actually there, and a new domain lights up a chip the moment it has an event.

Chips are the right control here because two different axes were being flattened into one tab row. "What kind" (mention, reply) is one axis; "what domain" (chat, assistant, account) is another. Chips carry the domain axis and let it grow (a future Calendar or Calls domain) without another tab war. The domain axis is data-driven: the chip set is All, Chat, then one chip per domain that has a retained notice.

Invites and DM requests are chat-adjacent (they are about people and conversations), so they map to the Chat domain, not a separate bucket.

## Data

The `%activity` feed is unchanged. It remains the ship-served chat half, parsed as today (`ActivityParser`, `TlonChatRepo.parseActivityFeedBody`, `forTab`).

A new local table backs the non-chat half:

```
NoticeEntity(
  id: String,          // stable per logical event (e.g. "loop:<runId>", "orrery:<actionId>")
  domain: String,      // "assistant" | "account" | "chat" (for invites/dm-requests)
  kind: String,        // "orrery-proposal" | "loop-result" | "armillary-credit" | "group-invite" | "dm-request"
  title: String,
  body: String,
  createdMs: Long,
  read: Boolean = false,
  target: String,      // a deep-link tag, see "Deep-link routing"
)
```

Both halves map into one view-model the feed renders and merges by `createdMs`:

```
FeedItem(id, domain, kind, title, body, ts, read, target, icon)
```

A chat `%activity` item carries its domain as `chat` and its existing read state; a `NoticeEntity` maps directly. All merges both by time; Chat reads only the `%activity` side; Assistant and Account read only the notices of that domain.

## Writing notices

Wherever a non-chat `notifier.notify(...)` already fires, record a notice alongside it. These sites are few and already the chokepoint:

- `App.kt` Orrery proposal raise (`"action:"` notifications) records `orrery-proposal`.
- `ArmillaryRepo` credit notify records `armillary-credit`.
- Loop completion (the `LoopRunner` path that writes `loopRuns`) records `loop-result`.
- `App.kt` group invite and "wants to message you" record `group-invite` and `dm-request`.

A chat notification (a chat whom) records no notice; it is already in `%activity`. One helper, `NoticeRepo.record(...)`, called next to each `notify`, keeps this in one place. Mail is deliberately out: it has a strong home and its own unread, and would double-count.

## Deep-link routing

Reuse the mechanism a tapped push already uses. `OpenNoticeRequests` carries a tag that `App.kt` collects and routes to a section (it already does this for the calendar push prefix). A notice's `target` is such a tag. Tapping a feed row emits `target` to `OpenNoticeRequests`; the collector gains a branch per new prefix:

- `orrery:<id>` opens `OrreryActionsScreen` at that action.
- `loop:<id>` opens `LoopsScreen` at that run.
- `armillary` opens the Armillary screen.
- `invite:<flag>` opens the Invites screen; `dm:<whom>` opens the request.

The feature screens stay the detail view. The notice is the index entry.

## Read and unread

One concept across both halves. A notice has `read`; chat `%activity` keeps its ship-tracked read notion. The drawer's Activity badge counts unread chat activity plus unread notices, which is what a user means by "unread." Tapping a row opens its target and marks it read. "Mark all read" acts on the active chip (or All).

Notice read-state is device-local, like other local UI state. It does not sync. A note for the implementer: if cross-device read-state is wanted later it is a separate decision, not part of this.

## Threads moves to Home

Threads leaves Activity. It is a followed-conversations collection, already a self-contained module: it reads `db.followedThreads().streamThreads()` and renders `FollowedThreadsList`.

Home is already a tabbed conversation hub (`HomeTab`: All, Groups, DMs, Party Lines, persisted per ship). Add `HomeTab.Threads`, rendering the existing `FollowedThreadsList`. Show it conditionally, only when at least one thread is followed, the way Party Lines is already conditional, so it adds no chrome for people who do not use threads.

The notification half stays in Activity: a reply to a followed thread still surfaces under the Chat domain. The Home tab is the browse-the-collection surface, not the notification.

## Rollout and discipline

Additive. The `%activity` path is untouched; the chips, the notices table, and the Home tab are new. Pure commonMain plus Room, so all platforms, no capability flag. Activity stays its existing section; Threads is a Home tab, not a new drawer section, so the section-flag rule is unaffected.

## Testing

Per the testing standard:

- Each chip filters to its domain; All merges both halves newest-first.
- A chat notification creates no notice and still appears under Chat (off means off: the notices table holds nothing for a chat whom).
- A non-chat notify records a notice, it appears in All and its domain chip, and the drawer badge counts it.
- Tapping a row deep-links to the right section and marks the notice read; the badge drops.
- Mark-all-read clears the active chip's unread.
- The Threads Home tab appears only when at least one thread is followed, and renders the collection; a followed-thread reply still shows under Chat in Activity.
- Retention: notices are capped by BOTH a count and a day window, pruned on write, whichever bound is tighter. A domain's chip disappears when its last notice is pruned (see dynamic chips above), so test that pruning the last Assistant notice drops the Assistant chip.
- Gap report: note that notice read-state is local-only and untested across devices by design.

## Settled decisions (were open, resolved 2026-10-14)

- Mentions and Replies fold into the Chat timeline. No secondary chip row. The separate focus views go away.
- Retention is count AND days, whichever is tighter.
- Domain chips are data-driven: any domain with a retained (unpurged) notice gets a chip; it disappears when its notices are gone. Account is not special-cased; it appears when it has an event.
