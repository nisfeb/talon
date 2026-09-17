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
- The best model a device can run is the one it uses. The floor exists so that every supported device works, not so that every device gets the floor. A newer system with better on-device model access uses that access, and the experience is never lowered to what the oldest supported version can do.

## 3. The structural pipe

| Talon data | Body | Observations | source kind and id |
|---|---|---|---|
| A contact in the person's own book (never a ship merely seen; the profile cache holds thousands) | `person/<ship slug>`. Name is the nickname, else the word name. Aliases: nickname, @p, word names | `status` from the status line, `at` = statusUpdatedMs | `contacts`, `<ship>` |
| A DM or group DM from anyone; a channel post only from someone in the book | none new | `person/x.last-contact` = the day, `at` = the start of that day | `talon-dm` for a DM, `talon-chat` for a channel post, id `talon://chat/<whom>?id=<post>` |
| A mail message | `person/<ship>` for from and to | `person/x.last-contact`, `at` = sent capped to now, since the author's clock is untrusted | `mail`, `talon://mail/<thread>` |
| A timed calendar event | `situation/cal-<cal>-<id>`, name from the event, aliases from its tags | `status = "scheduled"` at now until the start; `status = "under way"` at the start until the end; `location`; `started`; `ended`; `participants` = me | `calendar`, `<cal>/<id>` |
| A calendar event with a location | none | `person/me.location = <place>` at the start until the end, conf 60 | `calendar`, `<cal>/<id>` |
| A call whose transcript was published, 1:1 or a party line | `situation/call-<time>`, name from the transcript's title, and a `person/<ship>` for each speaker | `started`, `participants` = me and every speaker, `transcript` = the Lattice address; `person/x.last-contact` for each speaker | `talon-call`, the transcript's `urb://` address |

Calendar tasks stay in the calendar. Orrery's `task` is its own list, and orrery's `calendar` action kind runs the other way: the analyst proposes, Talon creates the event. Groups get no bodies in v1.

A body slug for a person is the @p without its sig, with `--` for a comet's separators. The ship is identity, so resolve matches it exactly, and a share is addressed by it.

Every observation carries `by = talon/<platform>` (the key's identity, forced by the ship anyway), `conf` 100 for a structural fact, and `at` from the event, never from the clock of submission.

## 4. The triage funnel

Most of what Talon sees is group chatter about nothing in the user's world. The funnel drops it cheaply before the model runs, and the model sees only candidates.

1. **Scope.** DMs, threads the user is in, mentions, mail addressed to the user, and channels on a user allow-list. The transcripts of the user's own calls, 1:1 and party lines, enter too, read back from Lattice by the address the call's situation carries. Nothing else enters.
2. **Names.** The scoped state view, cached by `rev` and refetched when the beacon moves, gives every body with its aliases and ship. A message that names a body, by alias or by ship (the contact map already resolves nicknames and word names), is a candidate. This is deterministic and instant, and it uses orrery's own model as the prior: the more bodies the user has, the better it gates.
3. **Pattern.** Where the platform has an embedder (Android and desktop today), a nearest-centroid test between a few "claim about the world" prototypes and chit-chat prototypes, with the observations the user confirmed as a growing positive set, the way `Highlights.kt` scores against bookmarks. The gate learns the user's pattern with no training. A platform without an embedder runs gates 1 and 2 only.
4. **Extraction.** The candidate and the scoped body list (names, aliases, current attributes; sensitive ones are already stripped by the key) go to the local model with a grammar that admits only the answer shape: observations with subject, attr, value, at, until, conf, and any new bodies. Talon validates before submitting: attr charset and length, refs resolve to known bodies, conf capped at 80 for anything model-asserted. A refused attr is dropped and remembered as sensitive.
5. **The person.** A Noticed tray in Talon, like the Requests section, shows each proposed observation with its source message: confirm or discard. A per-kind threshold lets confirmed kinds submit on their own once the user trusts them. Retract from the same tray. New places, things and situations are created only through the tray at first; people are created freely, because a ship is identity.

## 5. Local extraction: a ladder, best rung first

Every device runs the best local model it has access to, chosen at runtime, and falls to the next rung only when the one above is absent. The contract is the same on every rung: the same prompt, the same answer shape, the same validation, the same tray. What differs is the model behind it. The bottom rung is a shared runtime that works everywhere, and it is there so that no device is left out, not as the target.

| Platform | Top rung | Middle | Floor |
|---|---|---|---|
| iOS | Apple Foundation Models on iOS 26 and an Apple Intelligence device: the system model, guided generation as the grammar, no download | llama.cpp with the largest model the device's memory allows, Metal | llama.cpp, a 1B to 2B model at 4-bit |
| Android | Gemini Nano through AICore where the device has it (the ML Kit GenAI prompt API; verify its status at build time, it has been pre-release) | MediaPipe LLM Inference running Gemma 3n on the GPU or NPU on devices that carry it (Gemma's bundles are gated behind Google's terms, so this needs a signed download, not an in-app fetch) | MediaPipe LLM Inference with LiteRT's ungated Qwen2.5 1.5B bundle, GPU where the phone has it, else CPU |
| macOS | Apple Foundation Models on macOS 26 and Apple silicon, through a small Swift helper the JVM app talks to over stdio | llama.cpp with Metal and a 4B to 8B model, sized to memory | llama.cpp, 1B to 2B |
| Linux and Windows | A local server already running on the machine (Ollama or LM Studio at their default ports), which is local by definition and may hold a model far larger than anything Talon would download | llama.cpp with Vulkan or CUDA where a GPU is present, and a 4B to 8B model sized to memory | llama.cpp, 1B to 2B on the CPU |

Rules of the ladder:

- A rung is used only after it has passed the extraction fixtures at least as well as the rung below it, on that platform, in CI or on the device the first time it is enabled. A faster model that answers worse is not a better experience.
- The floor's model is sized to the device too: the shared runtime picks the largest of a short list that fits memory with headroom, so a flagship phone on the floor still runs a better model than a budget one.
- Detection is per device and per launch, and Settings shows which rung is in use, by name, with the reason the higher rungs are not (no Apple Intelligence, no AICore, not enough memory). A user who wants a rung the device could run but has not downloaded gets the download offered there.
- Models are downloaded on first use, never bundled, and the download is offered rather than started silently on mobile data.
- Desktop rungs that load native code run in a probed child process like `EmbedderProbe`, so a runtime that crashes takes nothing with it.
- Every rung the device lists is local. The cloud extractor is a separate switch beside the pipe on the Apps page, off, with its consequence written beside it, and needs a key set under AI. While it is on it is the top of the ladder, because the person chose it knowing what it costs.

The grammar guarantees valid JSON on the llama.cpp rungs and Apple's guided generation does the same; on rungs without constrained decoding the answer is validated and retried once, then dropped. A bad answer is therefore a wrong claim that meets validation and the tray, never a parse failure.

A capability flag, `isLocalTriageSupported`, gates the funnel's model stage per platform, and the copy says when it is off. Nothing in gates 1 to 3 needs a model.

Extraction runs in the background: on Android under WorkManager when charging or idle, on desktop and iOS while the app is open, with a per-day budget so a busy channel cannot run a phone flat.

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
2. **The funnel and the tray.** Scope, the names gate against the keyed state view, the Noticed tray with its trust rule (a kind of claim confirmed three times and never discarded goes up on its own), a per-channel switch, and a rule-only extractor for the simplest shapes (a status line, "I'm at <place>", a named body's whereabouts) so the tray is useful before the model lands. The embedding pattern gate waits for phase 3: its positive set is the claims the tray has confirmed, and the rules are their own gate until then.
3. **The model ladder.** The floor first, since it is one runtime and one grammar on all three platforms, then each platform's top rung, because that is where most current devices sit: Apple Foundation Models on iOS and macOS, AICore on Android, the local server on desktop. The fixtures, the download, the rung display in Settings, the budget.
4. **Actions back.** Open actions in the New widget, execution of `message` and `calendar`, done and failed.
5. **The middle rungs and the opt-in.** MediaPipe on Android, GPU offload on desktop, and the cloud extractor behind its switch.

## 9. Gates

- Unit: the structural mappers are pure functions from Talon rows to observation JSON, tested against fixtures, including the id alignment with what the ship-side pushes would produce.
- The funnel: a labelled set of messages (claims and chatter) with the expected gate outcome, run on every platform's embedder where one exists.
- Extraction: a fixture set of candidates and expected observations, judged by field match, not by text. It runs in CI against the floor on desktop with a pinned model file, and it is the gate every higher rung must pass, on its platform, before the ladder may select it.
- Against `~wex`: the phase 1 pipe writes, the state view shows it, a resend answers `existing` on every item, and a revoked key answers 403.

## 10. Not in v1

Bodies for groups. Mirroring calendar tasks into orrery actions. Embedding resolve. A second identity on one install. The ship pushing to Talon; the beacon is enough while the app is open.

## 11. Open

- Which models, per rung. Floor candidates at 1B to 2B: Gemma 3 1B, Qwen2.5 1.5B, Llama 3.2 1B; middle candidates at 4B to 8B for desktops with the memory. Decided by the fixture set in phase 3, not by reputation.
- Android's AICore prompt API has been pre-release; if it is not usable when phase 3 starts, MediaPipe with Gemma 3n is Android's top rung until it is.
- Whether mail `last-contact` alone is worth a mail source before auspex pushes its own facts, or whether mail subjects with dates should feed the funnel from the start.
