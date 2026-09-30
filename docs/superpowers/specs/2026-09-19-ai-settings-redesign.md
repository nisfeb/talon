# AI settings: providers, a default model, and a feature table

Approved 2026-09-19. Replaces the frontier model, the private model, the "frontier reads messages" switch and the decision model's own settings with one structure and one screen.

## 1. The model underneath

- **Providers.** Each is a kind, a label, a key and a base URL, with its models fetched on entry and kept. Kinds: OpenRouter, Anthropic, OpenAI, OpenAI-compatible (LM Studio, Ollama, a llama.cpp server), and **On this device**, built in, the local model each platform already runs.
- **A default model**: a provider and a model id.
- **Features**, each on or off with a model, where "Default" follows the default model: channel catch-up, the assistant (loops follow it), orrery triage, orrery analysis (the generator), the orrery brief, and transcription.
- **Jev gating**: one switch.
- **Kept as they are**: the Brave search key, the assistant's editable prompts (under the assistant's Advanced), search by meaning (on-device, not an API model).

Every feature asks one resolver which model it uses. No feature reads provider settings directly again.

## 2. Models and privacy badges

| Provider | Model list | Badge |
|---|---|---|
| OpenRouter | `GET /api/v1/models` | **ZDR** when the model is on `GET /api/v1/endpoints/zdr` (310 of 447 on 2026-09-19) |
| Anthropic, OpenAI | `GET /v1/models` | none per model: "retention per your account's agreement" |
| OpenAI-compatible | `GET /v1/models` | **Private** when the URL is this machine or the owner's own network |
| On this device | the platform's local model | **Private** |

Jev is not in OpenRouter's chat model list but is on its ZDR list (`typesafe/jev-1.13`): a provider offers Jev when it is OpenRouter and that list names Jev. Otherwise the Jev switch is shown greyed, with why.

## 3. The screen, top to bottom

1. **Providers**: add one, give its key or URL, fetch its models; the row says how many and how many are ZDR. A test button per provider gives latency and whether it answers.
2. **Default model**: a searchable dropdown grouped by provider, with ZDR and Private badges.
3. **"Add a private model"**, saying why: reading messages is best done by a model on this device, on the owner's own server, or with zero retention.
4. **The feature table.** Each row: a switch, a model dropdown ("Default (…)" or a specific model), one line of what it does, and this month's cost from the usage the calls already log.
   - Channel catch-up. Reads messages.
   - Assistant, with loops. Reads messages when asked.
   - Orrery triage. Reads messages; starts on a Private model. On a phone, "leave reading to my computer" lives on this row.
   - Orrery analysis, the generator. Runs on the ship: Talon writes the ship's generator settings through the owner's `PUT /generator`, sending that provider's key to the ship, which keeps it and never gives it back. Shows the generator's last run.
   - Orrery brief. The reply is read by it.
   - Transcription, when a provider has a speech model.

   A row that reads messages warns when its model is neither Private nor ZDR. That warning replaces the "frontier reads messages" switch.

   Dropdowns flag models unsuited to the row: tool use for the assistant, a long context for the generator.
5. **Jev gating**: one switch, with what it is worth: it skips messages that say nothing, drops feelings from statuses, and picks which people and things the reader sees, for cents a day. It turns on the gate, the status check and body picks together, at the tested thresholds; the check tools and the thresholds live under Advanced.
6. **Search**: the Brave key, unchanged.

A provider at `localhost` or on this device exists only on that machine. On another device a feature assigned to one falls back to that device's own on-device model, or, for triage on a phone, leaves the reading to the computer.

## 4. Migration

| Today | Becomes |
|---|---|
| Frontier provider, key, model, base URL | provider 1, and the default model |
| Private model with a URL | an OpenAI-compatible provider, assigned to triage |
| Private model with no URL | On this device, assigned to triage |
| "Frontier reads messages" on | triage assigned to the default model |
| Catch-up, Ask Urbit and Agent switches | the catch-up and assistant rows |
| Feed Orrery on | the triage and brief rows on |
| The ship's generator settings | the generator row, read from the ship |
| Decision model on, with the gate or picks | Jev gating on, thresholds kept |
| Transcription key | the transcription row, on an OpenAI provider made from it |
| Brave key, prompts, search by meaning | kept |

Settings sync across devices, and an install on an older build would write the old fields over the new ones. For two releases the new structure is written together with the old fields derived from it, and an old install's write is read as a change to what those fields describe.

## 5. Order of work

1. The data model, the migration and the resolver, with tests on the mapping. Features read their model through the resolver; the old screen still works.
2. The new screen.
3. The old sections removed.

Each step ships on its own.
