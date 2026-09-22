# Orrery 37: a single occurrence being off

Waiting on orrery 37 reaching the ship. Read
`orrery-utils/docs/writing-a-client.md` rules 3 and 14 first: they
carry the exact wording, and this note is only the shape of the work.

## Why it is waiting

An activity's `status` is the whole series — `active`, or `cancelled`
once it has stopped for good. A reply saying "practice is cancelled
tonight" is about one occurrence. Talon wrote `status: cancelled` on
`activity/linus-pirates-practice` from one such message and the ship
then read the series as dead.

Until 37 there is nowhere for the smaller fact to go, so the reader
drops it: `ModelExtractor` writes no activity `status` but `active`.
That is the fix already shipped, and it is deliberately broader than
the bug — a series genuinely ending is dropped too, because no reader
can tell the two apart from the message alone.

## What 37 adds

**`skipped` on an activity.** Multi-valued: the start of one occurrence
that is off, ISO 8601 UTC, one observation per occurrence, the activity
staying `active`. A reader that reads "tonight is cancelled" writes
`skipped` with that occurrence's start, resolved on the owner's clock,
and nothing else.

A one-off that is cancelled keeps writing `status: cancelled` on its
situation, as it does now — `vanishedEvents` in `OrreryCalendar.kt`
already guards this to `situation/` bodies and needs no change.

**`mode` on the `calendar` action.** `add` is today's behaviour.
`cancel` names `event` (the calendar's own id) and, where the event
repeats, `starts` (the occurrence to drop); the ship's executor then
takes the event off the calendar or skips that one occurrence.

Where Talon knows which calendar event a cancellation is about — its
calendar pipe does — it may propose that action beside the fact. It
stays a proposal: taking something off a calendar is a tap, and
`calendar` is not in the auto list.

## Reading side

Anywhere Talon shows or reasons about an activity's next occurrence,
read `next` against `skipped` and show the first one not skipped. The
brief especially: an occurrence in `skipped` is not tonight's plan.
`OrreryBrief.kt` reads `next` in several places; each needs the same
filter.

## When it lands

1. Re-read rules 3 and 14 for the wording.
2. Teach the extractor `skipped` on activities, and let a genuine
   series end write `status: cancelled` again once the occurrence case
   has somewhere else to go.
3. `next` filtered by `skipped` in the brief and every other reader.
4. The `cancel` mode proposal from the calendar pipe.
