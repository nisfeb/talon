# Voice briefing: a conversation on the dictation card

Status: draft for sneagan's review, 2026-10-17. Nothing built. Builds on PR #31 (questions read orrery, the decision model picks the fast calendar answer).

## Goal

sneagan, 2026-10-17: "I'm imagining a more conversational interaction. maybe there's a button on the home screen next to "good morning" that engages the voice assistant. it gets the user and offers a few contextual choices. something like "you have 2 new emails and 32 chat messages, 2 of which are questions for you. your first meeting is in about 30 minutes and Andrea is out of town today so you're on drop off duty. shall I read you the full brief?" from the conversation can branch many ways and should retain context and read/write orrery as needed."

Then: "stick with the dictation card but maybe put the conversation into assistant history as text when it's over."

And: "this is also a good opportunity to pilot the cute owl mascot idea. let's make an owl icon for the button on the home screen and give the assistant a name that's in keeping with the talon, orrery, armillary theme of a wise bird. but colloquial and friendly."

## Archie

The voice assistant is **Archie**, a small owl. The name is short for Archimedes: his bronze sphere of the heavens, as Cicero tells it, was the first orrery, kin to the armillary sphere, and Archimedes is Merlin's wise owl in The Sword in the Stone. "Archie" keeps it friendly.

- **The mark** (`ui/icons/Archie.kt`, `ArchieMark`): an owl perched on a branch, with ear tufts, big round eyes with a sparkle in each, a beak and wing edges. It is flat and in one colour, drawn on a 24 unit grid, so it tints like any icon: amber on navy in the dark theme, ink on paper in the light. The eyes, sparkles, beak and wing edges are holes cut by the even-odd rule. `ArchieMarkTest` checks that they render as holes.
- **Where it shows in this pilot:** the Home button, and the card's header in conversation mode with the name beside it. The Quick Settings tile and the widget keep the Talon mark, and the Assistant screen keeps its name, until the pilot has been lived with.
- **Archie does not keep saying its name.** It says it once, in the button's label ("Talk to Archie") and on the card. The opener goes straight to the day.

## What the user sees

1. **Archie's owl beside "Good morning"** on the Home page, labelled "Talk to Archie". Tapping it opens the dictation card in conversation mode. It shows only where quick dictation runs (`isQuickDictationSupported`, Android today), so no new capability flag.
2. **The card speaks first.** It says the counts at once ("You have 2 new emails and 32 chat messages, 2 of them questions for you."), then the rest of the opener as soon as the model has written it ("Your first meeting is in about 30 minutes. Andrea is out of town today, so you're on drop-off. Shall I read you the full brief?"). The card shows the same words, with today's events and the new mail under them as the card already draws them.
3. **Then it listens.** The owner answers in their own words: "yes", "read me the questions", "who's asking?", "tell Andrea I'll do pick-up too", "approve A2". It answers aloud, shows the answer, and listens again.
4. **Reading in full.** "Read me the brief" speaks orrery's own text of today's brief, section by section, never a model's retelling of it. A tap stops the reading and the card listens.
5. **Writes wait for a word.** As in a dictation today: a message waits for a spoken "send" or a tap, and on a locked phone for the fingerprint too. Tasks, events and facts go ahead.
6. **It ends** on "that's all", "thanks", "stop", the close button, or eight seconds of silence after a question. Then the whole conversation is saved to Assistant history.

## The opener

Two parts, so nothing waits on a model before the first word.

- **Said at once, from the device:** the unread mail in the inbox (`MailRepo`), the unread chat total as the chat list counts it, and how many of those are questions for the owner. Questions are judged by the decision model: one call, one yes-or-no question per message ("does this wait for the owner's answer?"), over the unread DMs and group DMs and the channel messages that mention the owner, at most fifty. The decision model off, keyless or failing leaves the question count out, and nothing else changes.
- **Written by the model while that sentence is spoken:** the first timed event today, what today holds from orrery (its open situations, whether someone is away, legs that fall to the owner), and whether today's brief exists. One call to the assistant's model, under 60 words, ending in an offer. It is handed the counts and told not to restate them.
- **If the model fails,** the card says the counts sentence and "What would you like?" and listens. Nothing is lost.

## The conversation

- **One model, no routing.** Inside a conversation the "question mark means ask, otherwise tell orrery" split from dictation goes away. The model has the dictation's tools (reads, the writes a dictation may do, orrery's find, read and instruct) and decides itself when to read and when to hand orrery something. What orrery proposes waits on the card for the owner's word, as a dictation's proposals do.
- **Context.** Each turn keeps what was said and answered, as a follow-up does today. The last thing read in full (the brief) stays in context too, so "approve the second one" or "move that to Friday" means something. Twenty turns at most.
- **New tools:**
  - `read_brief`: today's brief from orrery's `GET /brief/last` (owner only, through the ship session): its day, its text, and its action tags. Asked for aloud, the card speaks the brief's text itself.
  - `brief_action`: approve, dismiss, mark done or move the action behind a brief tag ("A2"), through orrery's actions as Talon's Actions page does.
  - `list_unread`: the unread chat messages, newest first, with who sent them and where, so "read me the questions" and "read me my messages" work. Its result is read in full the same way.
- **Listening.** After speaking, the card listens with the end-of-speech gate dictation already uses. No talking over it: the microphone would hear Talon's own voice. A tap stops speech and listens.

## Saved to Assistant history

When the conversation ends, however it ends:

- One assistant conversation, titled "Archie, Friday 17 October".
- One turn per exchange: the owner's words as the question ("Opened the briefing" for the opener) and the full answer as said and shown, with `mode` "voice".
- Pushed with `pushAssistantTurn`, the same as a typed turn, so other devices show it.
- An empty centroid, so a typed question later does not join it by topic.

Opening it in the Assistant screen shows it as text.

## Orrery

No change needed. `GET /brief/last` and the brief's tags exist (orrery README, "Mail, and the daily brief"). An orrery view of "questions waiting for the owner" from its chat reader would be better than Talon judging them, and is an ask for the orrery session, not a blocker.

## Privacy and cost

- Pressing the button sends up to fifty unread message texts to the decision model, under zero data retention. Today only the owner's own words leave the phone this way.
- One model call for the opener and one per turn, through Armillary.
- Nothing runs until the button is pressed.

## Tests (the testing standard)

The conversation logic lives in common code, so it is tested in commonTest with fakes. The card's wiring is Android.

1. **End to end:** the button opens a conversation; the counts are said before the model answers; three turns keep their context; "read me the brief" speaks orrery's text; "approve A2" approves the action behind the tag.
2. **Every screen:** the saved conversation shows in the Assistant screen; an approved action is gone from Home's actions and the Actions page.
3. **Sync:** the turns reach another device and apply there.
4. **Failure:** no brief today, or an orrery older than the route; the model fails (the counts and "What would you like?"); the decision model fails (no question count); the ship does not answer. Each is said in words.
5. **Leaving midway:** closing the card mid-turn still saves what was said, and an approval in flight finishes on a repo scope.
6. **Wire:** `GET /brief/last` parsed against orrery's own fields, pinned in a test; the questions sent to the decision model pinned.
7. **Platform gates:** the Home button only where quick dictation runs, through a common function with a commonTest.
8. **Mutation:** PIT, sandboxed, over the opener, the turn handling and the history save.
9. **Gap report** before calling it done.

## Phases

1. Archie's button and card header, the opener, the conversation with context, reading the brief, and the save to history. The mark and name are done (`ArchieMark`, `ASSISTANT_NAME`).
2. `brief_action` by tag and `list_unread` read aloud.

## Open points for sneagan

1. **Opener timing.** My default speaks the counts at once and lets the model write the rest meanwhile. The other way prepares the whole opener whenever Home appears, which is instant but costs a model call per visit.
2. **What goes to history.** My default saves every card conversation, ordinary dictations with follow-ups too, since it is one card. The other way saves only briefings.
3. **Which chat messages count.** My default is the chat list's own unread total. Muted channels stay out of it, as they do there.
4. **Ending on silence** after eight seconds.
