# The orrery client in Talon: observations from chat, mail and the calendar

Status: drafted 2026-09-17 against orrery's design spec of 2026-09-16 (`nisfeb/orrery`, `docs/superpowers/specs/2026-09-16-orrery-design.md`). Decisions in section 2 are the owner's.

## 1. What this is

Orrery keeps a model of one person's world on their ship: bodies, immutable observations about them, and actions. The ship runs no AI and never sees text. Clients do the triage. Talon is the client with the most context, since it holds the user's chat, mail, calendar and contacts on one device, so Talon pushes into orrery whatever it can derive, and a larger model reads the state back on the ship's side.

Two pipes feed orrery from Talon. The structural pipe turns facts Talon already knows for certain into observations, with no model involved. The triage pipe turns what people say into claims about the world, and that needs a language model, which runs on the device.

## 2. Decisions

- Talon writes with a minted key, one per install, never with the owner cookie. The audit trail names the client, and policy's sensitive attributes never reach it.
- Talon pushes every structural fact it can, from contacts, chat, mail and the calendar. When auspex and the calendar push their own facts from the ship, the matching sources leave Talon. Source ids are chosen so that handoff is a no-op, not a duplicate.
- Triage runs locally by default on every platform. A cloud provider may be used for triage only when the user opts into it explicitly, in its own switch, with the consequence stated. Sending every message to a cloud provider is against the privacy principles of Urbit. A frontier model reading the orrery state on the ship's behalf is not, because that state is claims and pointers, never the text.
- Text never leaves the device for the ship. An observation's source is a pointer that Talon can open, and its value is a claim.

## 3. The structural pipe

| Talon data | Body | Observations | source kind and id |
|---|---|---|---|
| A contact: ship, nickname, word name, status line | `person/<ship slug>` with `ship` set. Name is the nickname, else the word name. Aliases: nickname, @p, word names | `status` from the status line, `at` = statusUpdatedMs | `contacts`, `<ship>` |
| A DM or group post from a contact | none new | `person/x.last-contact` = ISO time, `at` = sentMs | `talon-dm` for a DM, `talon-chat` for a channel post, id `talon://chat/<whom>?id=<post>` |
| A mail message | `person/<ship>` for from and to | `person/x.last-contact`, `at` = sent capped to now, since the author's clock is untrusted | `mail`, `talon://mail/<thread>` |
| A timed calendar event | `situation/cal-<cal>-<id>`, name from the event, aliases from its tags | `status = "scheduled"` at now until the start; `status = "under way"` at the start until the end; `location`; `started`; `ended`; `participants` = me | `calendar`, `<cal>/<id>` |
| A calendar event with a location | none | `person/me.location = <place>` at the start until the end, conf 60 | `calendar`, `<cal>/<id>` |

Calendar tasks stay in the calendar. Orrery's `task` is its own list, and orrery's `calendar` action kind runs the other way: the analyst proposes, Talon creates the event. Groups get no bodies in v1.

A body slug for a person is the @p without its sig, with `--` for a comet's separators. The ship is identity, so resolve matches it exactly, and a share is addressed by it.

Every observation carries `by = talon/<platform>` (the key's identity, forced by the ship anyway), `conf` 100 for a structural fact, and `at` from the event, never from the clock of submission.

## 4. The triage funnel

Most of what Talon sees is group chatter about nothing in the user's world. The funnel drops it cheaply before the model runs, and the model sees only candidates.

1. **Scope.** DMs, threads the user is in, mentions, mail addressed to the user, and channels on a user allow-list. Nothing else enters.
2. **Names.** The scoped state view, cached by `rev` and refetched when the beacon moves, gives every body with its aliases and ship. A message that names a body, by alias or by ship (the contact map already resolves nicknames and word names), is a candidate. This is deterministic and instant, and it uses orrery's own model as the prior: the more bodies the user has, the better it gates.
3. **Pattern.** Where the platform has an embedder (Android and desktop today), a nearest-centroid test between a few "claim about the world" prototypes and chit-chat prototypes, with the observations the user confirmed as a growing positive set, the way `Highlights.kt` scores against bookmarks. The gate learns the user's pattern with no training. A platform without an embedder runs gates 1 and 2 only.
4. **Extraction.** The candidate and the scoped body list (names, aliases, current attributes; sensitive ones are already stripped by the key) go to the local model with a grammar that admits only the answer shape: observations with subject, attr, value, at, until, conf, and any new bodies. Talon validates before submitting: attr charset and length, refs resolve to known bodies, conf capped at 80 for anything model-asserted. A refused attr is dropped and remembered as sensitive.
5. **The person.** A Noticed tray in Talon, like the Requests section, shows each proposed observation with its source message: confirm or discard. A per-kind threshold lets confirmed kinds submit on their own once the user trusts them. Retract from the same tray. New places, things and situations are created only through the tray at first; people are created freely, because a ship is identity.

## 5. Local extraction

One runtime on every platform: llama.cpp, with one GGUF model and one GBNF grammar, so the extraction behaves the same everywhere and one fixture set tests it. Bindings exist for Android (JNI), iOS (the Swift package) and the JVM desktop. The model is a 1B to 2B instruction-tuned model at 4-bit, under a gigabyte, downloaded on first use the way the desktop embedder is, never bundled in the app. The grammar guarantees valid JSON, so a bad answer is a wrong claim, never a parse failure, and a wrong claim meets the validation and the tray.

Per-platform notes:

- iOS ships at 15.0, so Apple's Foundation Models (iOS 26, Apple Intelligence devices) cannot be the default. It is an upgrade where present, with guided generation as the grammar.
- Android's MediaPipe LLM Inference API (Gemma 3 1B, GPU and NPU delegates) is an acceleration to consider once the shared runtime works, not a second default.
- Desktop runs the model in a probed child process like `EmbedderProbe`, since a native runtime that crashes must not take the app with it.

A capability flag, `isLocalTriageSupported`, gates the funnel's model stage per platform, and the copy says when it is off. The cloud extractor is a separate switch under the AI settings, off, with its consequence written beside it. Nothing in gates 1 to 3 needs a model.

Extraction runs in the background: on Android under WorkManager when charging or idle, on desktop and iOS while the app is open, with a per-day budget so a busy channel cannot run the phone flat.

## 6. Auth and transport

- **Key.** At setup Talon mints its own key with the owner cookie: `POST /apps/orrery/api/clients` with name "Talon on <platform>", `by: talon/<platform>`, scope kinds person, place, thing, situation, org, actions task, note, message, calendar, write true. The token is stored beside the session, and every orrery request carries it as a bearer, never the cookie. Revoking it on the ship stops Talon within a second.
- **Availability.** `OrreryApi` probes like `AuspexApi` and `CalendarApi`: no Grubbery, no orrery, and orrery present are three different states, none an error. The Apps page gets an orrery row.
- **Batches.** 50 bodies and 200 observations per observe, with per-item answers. Talon keeps an `orrery_sync` table: a cursor per source (last sentMs per whom, last mail thread time, calendar rev) and the ids it submitted. Observation ids are content hashes, so a resend is a no-op and the cursor can be conservative.
- **Backfill.** Opt-in, over a chosen window, after the switch is turned on. Incremental thereafter from the live feeds Talon already runs: chat facts over the channel, the mail poll, the calendar poll.
- **Reads.** The scoped state view for the funnel and for showing state in Talon, refetched on the beacon.

## 7. Surfaces in Talon

- Settings: an Orrery section with the switch, the window for backfill, the channel allow-list, the tray thresholds, the local model's download state, and the cloud switch with its warning.
- The Apps page: an orrery row, install and permits like the others.
- The Noticed tray, in the chat list above Requests, and the same rows reachable from a message's menu.
- The New widget: open orrery actions, so a proposal the analyst made shows beside the mentions. Talon executes `message` (a DM) and `calendar` (an event) kinds with a confirm, reports done or failed, and shows `task` and `note` as the ship's list.

## 8. Phases

1. **Plumbing and the structural pipe.** `OrreryApi`, the probe, the Apps row, key minting, the sync table, contacts, chat and mail last-contact, and calendar situations, batched, backfilled over the window. The switch, off by default.
2. **The funnel and the tray.** Gates 1 to 3 and the Noticed tray, with a rule-only extractor for the simplest shapes (a status line, "I'm at <place>") so the tray is useful before the model lands.
3. **The local model.** llama.cpp on all three platforms, the grammar, the prompt, the fixtures, the download, the budget.
4. **Actions back.** Open actions in the New widget, execution of `message` and `calendar`, done and failed.
5. **Accelerations and the opt-in.** Apple Foundation Models where present, MediaPipe on Android if it is measurably better, and the cloud extractor behind its switch.

## 9. Gates

- Unit: the structural mappers are pure functions from Talon rows to observation JSON, tested against fixtures, including the id alignment with what the ship-side pushes would produce.
- The funnel: a labelled set of messages (claims and chatter) with the expected gate outcome, run on every platform's embedder where one exists.
- Extraction: a fixture set of candidates and expected observations, run against the local model in CI on desktop with a pinned model file, judged by field match, not by text.
- Against `~wex`: the phase 1 pipe writes, the state view shows it, a resend answers `existing` on every item, and a revoked key answers 403.

## 10. Not in v1

Bodies for groups. Mirroring calendar tasks into orrery actions. Embedding resolve. A second identity on one install. The ship pushing to Talon; the beacon is enough while the app is open.

## 11. Open

- Which model. Candidates at 1B to 2B: Gemma 3 1B, Qwen2.5 1.5B, Llama 3.2 1B. Decided by the fixture set in phase 3, not by reputation.
- Whether mail `last-contact` alone is worth a mail source before auspex pushes its own facts, or whether mail subjects with dates should feed the funnel from the start.
