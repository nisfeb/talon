# Armillary-only AI in Talon

Status: draft for sneagan's review, 2026-10-16. Nothing built yet.

## Goal

Paying a few dollars turns every AI feature on, and that is the whole setup. sneagan, 2026-10-16: "users will always have armillary and they can configure their models in armillary if they want to do it themselves. we should include a button that goes to their own armillary settings if they want to self-manage their models. but I really think this level of complexity will prevent adoption. the easiest case needs to be paying a few bucks which should just turn everything on and call it a day."

## Decisions already made

- **Armillary is the only cloud AI.** Talon stops offering its own providers (OpenRouter, Anthropic, OpenAI, a server of your own). Anyone who wants to pick models or use their own provider does it in Armillary on their ship, where a ship can be its own vendor.
- **Payment is the opt-in for cloud reading** (sneagan, 2026-10-16). The top-up sheet says once that AI features send what you ask about to models that keep nothing. Paying turns everything on. This narrows the 2026-09-17 local-first rule for catch-up, the assistant and dictation.
- **Orrery triage stays on the device** either way. Its cloud option goes.
- **Branch order.** PR #27 (dictation) and PR #28 (ZDR on every vendor-followed feature) merge into master first. This work is built from master after that.

## What the user sees

### The AI tab

1. **The Armillary card**, first and always. With no account yet, it is today's `StartWithArmillary` pitch: what AI does in Talon, "pay as you go from $5", and **Top up**. Pressing Top up introduces the ship to the vendor (`~nisfeb` unless the ship names another) and opens the sheet. Nothing reaches the vendor before that press. With an account, it shows the balance, Top up, History, and a mode line, as today.
2. **The top-up sheet** gains one line above the amounts: "AI features send what you ask about to models that keep nothing (zero data retention). Paying turns them all on."
3. **Features**: one switch per feature, on by default, no model pickers. Catch-up, Assistant, Quick dictation (Android), Call transcripts, Search by meaning (on device, needs no balance). While the balance is empty, each cloud feature's line says "Top up to turn on", and the feature does nothing.
4. **Manage models in Armillary**: a button that opens `/apps/armillary#catalog` on the user's ship in the browser. One line under it: "Pick your own models or use your own provider key there."
5. **Advanced** keeps only the three instruction texts (Background on Urbit, Assistant instructions, Loop instructions).

Gone from the tab: the providers list and its cards, Save key, model lists, per-feature model pickers, "Not kept" badges, the default model row, "Use Armillary's pick", the Brave key field, the Whisper/Transcription provider row, and "Copy AI keys to my other devices".

### Platforms

The tab is common code, so it changes on every platform at once. On iOS outside the US storefront, `isArmillaryPurchaseSupported` is false, so there is no in-app buying. The card shows the balance, and the features turn on once the account has money from another device.

## How a feature runs

- **Model**: the Armillary row's suggested model for the feature, by the vendor's feature name, else the vendor's `default`. Dictation with no named model follows the assistant's, as today. There is no owner pick in Talon.
- **ZDR**: every cloud call sends `provider: {"zdr": true}` (#28 made this true for every vendor-followed feature, and now every feature follows the vendor).
- **Decision model**: the vendor's `decision` model on the lease key, as today. Without one, a pass reads with the reading model.
- **Web search**: the vendor's Brave proxy (`braveSearch()`), as today when the owner has no key.
- **Speech to text**: `ArmillaryTranscribe` for dictation and call transcripts. Dictation sends m4a (Android) as built in #27. Call transcripts send WAV chunks of at most 10 MB, because the vendor's route caps a body at 16 MB and base64 adds a third. Compressing call audio is a follow-up (no AAC encoder in common code).
- **Every device of a ship shares one balance.** Pay on the phone and the desktop's features turn on at its next Armillary refresh.

## What goes from the code

- Own providers: the non-Armillary, non-device branches of `AiProfile` resolution, `ModelCatalog`'s own-provider catalogs, provider cards and pickers in `AiSettingsSection`, `followsVendor`, `own`, `defaultOwn`, `followingVendorDefault`, `filledWithArmillary`. `vendorZdr` becomes always true for a cloud call.
- Keys: `apiKey`, `providerKeys`, `sttApiKey`, `braveApiKey`, `syncEnabled`, `setSyncEnabled`, `hasCredentials`, the `revokedKeys` marks, and the key push and pull in `SettingsSyncImpl`.
- Own speech to text: `CallRecordingPublisher.sttFrom`, the multipart Whisper client, `Hearing.Own`.
- Orrery's cloud triage option and the generator offer that copied a key to the ship (Orrery on the ship already follows Armillary since orrery 96).

## What stays

- `ProviderKind` keeps every value. An unknown kind drops the whole profile on decode (AiProfile.kt), so older rows must still decode. They are filtered out on load and never written.
- On-device AI: search by meaning, the triage ladder, this device's model download.
- The Armillary card, top-up sheet, history, payment watch, the 402 copy, and the Top up action on errors.
- Feature switches and the instruction texts, synced as today.

## Migration

- **Profiles on disk**: on load, drop every provider row that is not Armillary or this device, and every own pick. One-time and silent.
- **Sync** (per the schema-versioning rule): the AI bucket's `schemaVersion` goes to 3. `applyAiEntry` reads only switches and instructions from an entry of any version, and never keys, providers or picks. Bootstrap re-pushes once after applying an older entry, so the bucket loses the keys a 1.8.29 device left there. A 1.8.29 device must still decode a v3 entry without dropping its profile (tested).
- **Switch defaults**: every feature switch now defaults on. Entries and local values from before v3 are ignored once, so an install that upgrades has everything on. **Open point for sneagan:** this also turns back on a feature someone deliberately turned off before. My default is to accept that, because most offs before this build were "no key yet". The alternative is to keep stored offs, and then paying does not turn everything on for existing users.
- **Own-key users**: their keys stop being used. The release note says so in one line, and the AI tab says it once ("Your own AI keys are no longer used. Manage models in Armillary instead.").

## Asks of Armillary (handed to the armillary session, not built in Talon)

- Self-managing today means Provider mode, adding a provider, importing, enabling models, setting tiers and pointing `vendor.json` at the ship itself. That is too much for "use my own key". A customer-mode "use my own OpenRouter key" path would make the Manage button useful. Until then the button opens `#catalog`.
- A route that lands on whatever that path becomes, so Talon can link straight to it.

## Tests (the testing standard)

1. **End to end** (`AppShellTest` against `FakeShip` with an Armillary mock): no account, then the card, Top up, a mocked balance, then catch-up runs on the vendor's model with `provider.zdr`. With an empty balance the feature says "Top up to turn on" and `FakeShip.requests` holds no inference call.
2. **Every screen**: the AI tab, the catch-up dialog, the assistant, dictation's blocker line and the Orrery page all read the same state.
3. **Sync**: a legacy v2 entry (today's real shape, keys redacted) applies switches only. The re-push strips keys. A 1.8.29 peer's later push resurrects nothing. A 1.8.29 device decodes a v3 entry.
4. **Failure**: 402, the vendor down, Armillary not installed ("Install it from the Grubbery shell on your ship."), and no answer, each in words.
5. **Leaving midway**: leaving the AI tab while the hello or a top-up is in flight still finishes it.
6. **Wire**: call-transcript chunk bodies pinned against armillary 31's parser, and the chunk size under the cap.
7. **Platform gates**: iOS outside the US storefront shows no buying and still turns features on from a balance.
8. **Mutation**: PIT, sandboxed, over the new resolution, the sync strip and the migration.
9. **Gap report** before calling it done.

## Before building

- The lease-key call to OpenRouter's `/audio/transcriptions` is still unproven, and it becomes the only speech-to-text path. One real dictation take on the device settles it.
- #27 and #28 merged into master.

## As built (branch feat/armillary-only, 2026-10-16)

sneagan approved the spec and chose the reset: every paid switch turns on once, even ones turned off on purpose before.

- **Resolution** (`AiProfile.resolve`): every cloud feature runs on the vendor's model for it, else the vendor's default, else the row's first chat model. Own providers and picks left in a profile are ignored. Triage resolves to this device. With no Armillary row nothing runs. `vendorZdr` is true for every call on Armillary.
- **The reset** (`AiSettings.Config.armillaryOnly`): runs once per device at load, on all three platforms. It drops own providers, picks and the own keys in the old fields, and turns on catch-up, the assistant and call transcripts. Search by meaning runs on the device and keeps its switch. Desktop resets only a file it could read. A profile flag stops it running again.
- **Paying** (`ArmillaryRepo.afterPayment`): asks for this device's key and turns every paid feature on. It no longer picks a "best" model, offers anything, or writes Orrery's generator. Orrery on the ship follows Armillary itself.
- **Other devices connect themselves.** Once the ship's Armillary account names a vendor, any device signed in to that ship adds its own row and asks its ship for a key, with no tap. Pay on the phone and the desktop runs. A ship that never set Armillary up contacts no vendor until someone presses "Set up Armillary".
- **Where buying is not allowed** (iOS outside the US): "Connect this device" in place of the pitch, and the self-connect above.
- **Call transcripts** go through Armillary a stretch of speech at a time (`CallRecordingPublisher.speechSpans`: pauses over 0.8 s cut a stretch, 25 s at most). This differs from the planned 10 MB WAV chunks. The vendor's speech route answers words with no timestamps, and each speaker's words are placed in the call by where their stretch starts. If Armillary passes segment timestamps through some day, the splitting can go.
- **Sync** (schemaVersion 3): only the switches, the old toggles and the instructions go up. From an older entry only the instructions are taken. Bootstrap re-pushes an older config entry once, and deletes the `credentials` entry older builds left on the ship.
- **The AI tab**: the Armillary card (the Remove button is gone), "Manage models in Armillary" (opens `/apps/armillary#catalog`), a one-time line when own keys were dropped, and one switch per feature with "Top up to turn on" while there is no credit or key. The top-up sheet carries the consent line. Advanced holds only the instructions. The Orrery page's reading row is this device's model, and the decision model is the vendor's.
- **Left in place**: the `Config` fields `apiKey`, `syncEnabled`, `braveApiKey`, `sttApiKey` and the private-model fields stay as stored data (the reset blanks the keys). Their setters, the UI and the sync paths are gone. The triage cloud rung stays, but `triageInCloud` is always false now.
- **Removed**: own provider cards, model pickers and catalogs (`ModelCatalog`), the default model row, the payment offer, the Brave and Whisper key fields, key sync and revoke marks on the wire, the generator offer and `followDefault`, `ArmillarySetup.kt`, and the multipart Whisper path for dictation.
