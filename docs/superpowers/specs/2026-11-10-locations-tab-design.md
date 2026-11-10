# Locations tab and indefinite sharing

Design agreed with sneagan on 2026-11-10. Not built.

## Goal

A "Find My Friends" for Groundwire: one place that shows everyone sharing their location with you, and a way to share with someone indefinitely without draining the battery when nobody is looking.

## Decisions (sneagan, 2026-11-10)

1. An indefinite share is exact or area, chosen per person.
2. Accepting an indefinite share offers to share back. It does not require it.
3. No fresh position on demand in this version. The viewer sees the last known position with its age.
4. Talon reminds the owner now and then that indefinite shares are running.

## Locations tab (Talon only)

- A drawer section, declared with `sections.flag()` in both shells. Shown only when the Assistant is on (`LocalLiveLocation` not null). No greyed-out entry.
- Phones: one map with a pin per person sharing with you (`isLiveMapSupported`). Desktop has no map, so it shows the list, and each row opens the position in a maps app or OpenStreetMap.
- The list: each person, when their position was last updated, how long the share runs ("until 3:30 PM", "until they're home", "always"), and "area only" where it applies. Tapping a row centres the map.
- Requests waiting for Accept at the top (`AskRow`). Your own shares below, with Change and Stop.
- Data: `GET /api/location`, as the DM strip reads it. Re-read every 60 s while the tab is open, whether or not a share shows yet (the DM strip's loop only re-reads once one is known).

## Indefinite sharing (Orrery release, then Talon)

Orrery today: a grant needs `until` or `home`, and `+loc-plan` ends a grant with neither. A received share with no `until` expires 24 h after its last fix (`+loc-in-live`).

Orrery changes:

- A grant may be `always`: no `until`, not `home`. `+loc-live` keeps it, `+loc-plan` does not end it.
- The `position` poke carries `always`. The receiver keeps an `always` entry past 24 h of quiet, until a `position-end`.
- `POST /api/location/share` takes `"always": true`. `GET /api/location` returns it on `out` and `in`.
- Rehearse on ~tyr. A ricsul deploy needs sneagan's go.

Talon changes:

- The share dialog gains "Always" beside 1 hour, 8 hours and until home, shown only when the ship's Orrery is new enough.
- An `always` share never turns on the once-a-minute mode: `sendFastUntil` ignores it. The phone sends on ordinary significant moves (a few hundred metres, woken by the OS, no timer), so a phone that stays put sends nothing.
- A position still goes up when the share starts (PR #105).
- Accepting an `always` share from someone offers "Share yours with them too?", with the same exact or area choice. Declining the offer still accepts theirs.
- A reminder: a notification every 30 days listing who has an indefinite share, opening the Locations tab.

## Later

- A fresh position on demand: opening the tab asks each sharer's ship, which pushes their phone for one fix.

## Tests

Per the Testing standard in CLAUDE.md: the tab through `AppShellTest` (opens, back leaves it, drawer switches away), the wire bodies for `always` pinned against Orrery's parser, an `always` share never asking the phone for fast fixes, the share-back offer, and the reminder's timing in commonTest.
