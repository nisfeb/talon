package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import io.nisfeb.talon.ui.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import io.nisfeb.talon.ui.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.ai.AiFeature
import io.nisfeb.talon.ai.AiProfile
import io.nisfeb.talon.ai.AiProvider
import io.nisfeb.talon.ai.ARMILLARY_PROVIDER
import io.nisfeb.talon.ai.AiSettings
import io.nisfeb.talon.ai.AiSettingsRepository
import io.nisfeb.talon.ai.AiSpend
import io.nisfeb.talon.ai.FeatureSetting
import io.nisfeb.talon.ai.ModelCatalog
import io.nisfeb.talon.util.runSuspendCatching
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProfileInputs
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.defaultModel
import io.nisfeb.talon.ai.followingVendor
import io.nisfeb.talon.ai.vendorDefault
import io.nisfeb.talon.ai.migrateProfile
import io.nisfeb.talon.ai.without
import io.nisfeb.talon.armillary.Account
import io.nisfeb.talon.armillary.ArmillaryAvailability
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.armillary.Checkout
import io.nisfeb.talon.armillary.DeleteAnswer
import io.nisfeb.talon.armillary.Deletion
import io.nisfeb.talon.armillary.Inference
import io.nisfeb.talon.armillary.LEO_KEY_NOTE
import io.nisfeb.talon.armillary.LEO_STEPS
import io.nisfeb.talon.armillary.LeoSetup
import io.nisfeb.talon.armillary.leoSetup
import io.nisfeb.talon.armillary.LedgerRow
import io.nisfeb.talon.armillary.Payment
import io.nisfeb.talon.armillary.Plan
import io.nisfeb.talon.armillary.money
import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.RungStatus
import io.nisfeb.talon.orrery.generatorProblem
import io.nisfeb.talon.orrery.generatorTarget
import kotlinx.serialization.json.put
import io.nisfeb.talon.ui.icons.TalonIcons
import io.nisfeb.talon.ui.isAssistantSupported
import io.nisfeb.talon.ui.isCallRecordingSupported
import io.nisfeb.talon.ui.isOnDeviceAiFeatureSupported
import io.nisfeb.talon.ui.isOnDeviceAiSupported
import io.nisfeb.talon.ai.transcriptionRef
import io.nisfeb.talon.ui.isTouchPrimary
import io.nisfeb.talon.urbit.isValidPatp
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Settings > AI as the owner thinks of it: the providers they have, one
 * default model, and a row per feature with its switch and its model.
 * docs/superpowers/specs/2026-09-19-ai-settings-redesign.md is the plan;
 * the 2026-10-12 pass took out what the app can decide itself and moved
 * this device's own model and the decision model to the Orrery page.
 *
 * Until the owner changes something here the profile shown is the one
 * the old settings make, with what only the ship and the orrery pipe
 * know (Feed Orrery, the generator, the decision model) folded in; the
 * first change saves it.
 */
@Composable
fun AiSettingsSection(
    aiSettings: AiSettingsRepository,
    orrery: OrreryRepo?,
    armillary: ArmillaryRepo? = null,
    catalog: ModelCatalog = remember { ModelCatalog() },
) {
    val scope = rememberCoroutineScope()
    val cfg by aiSettings.state.collectAsState()
    // One collection each whether or not there is a ship, so nothing moves about.
    val noFlag = remember { MutableStateFlow(false) }
    val noGen = remember { MutableStateFlow<io.nisfeb.talon.orrery.GeneratorSettings?>(null) }
    val fed by (orrery?.enabled ?: noFlag).collectAsState()
    val gen by (orrery?.generatorSettings ?: noGen).collectAsState()
    val noInference = remember { MutableStateFlow<Inference?>(null) }
    // How an Armillary model is reached, which decides what the rows
    // that read messages warn about.
    val armillaryMode by (armillary?.inference ?: noInference).collectAsState()
    val spend by AiSpend.month.collectAsState()
    LaunchedEffect(Unit) { AiSpend.load() }
    if (orrery != null) LaunchedEffect(orrery) { orrery.loadGenerator() }

    fun starting(): AiProfile = migrateProfile(
        aiSettings.state.value,
        ProfileInputs(
            orreryFed = orrery?.enabled?.value == true,
            generatorOn = orrery?.generatorSettings?.value?.enabled == true,
            generatorUrl = orrery?.generatorSettings?.value?.url,
            generatorModel = orrery?.generatorSettings?.value?.model,
        ),
    )
    val started = remember(cfg, fed, gen) { starting() }
    val profile = cfg.savedProfile ?: started
    // Always from the settings as they are now: a fetch lands after the screen has moved on.
    // The ship's generator moves with the default where it follows it.
    fun edit(change: (AiProfile) -> AiProfile) {
        val before = aiSettings.state.value.savedProfile ?: starting()
        aiSettings.setProfile(change(before))
        aiSettings.state.value.savedProfile?.let { orrery?.followDefault(before, it) }
    }
    fun setFeature(f: AiFeature, change: (FeatureSetting) -> FeatureSetting) =
        edit { p -> p.copy(features = p.features + (f to change(p.features[f] ?: FeatureSetting()))) }
    val chat = profile.providers.filter { it.kind != ProviderKind.ThisDevice }
    // Each provider's models, fetched again on opening. They were fetched
    // only when a key was saved here and do not travel with the profile,
    // so a device given its key by sync had nothing to search, and a list
    // from before a model came out could not find it ("opus 5.5", sneagan,
    // 2026-10-07). Saved only where the list changed; a profile not yet
    // saved is left as it is, since looking saves nothing.
    LaunchedEffect(Unit) {
        val saved = cfg.savedProfile ?: return@LaunchedEffect
        saved.providers.filter { it.canFetchModels() }.forEach { p ->
            runSuspendCatching { catalog.fetch(p) }.onSuccess { c ->
                if (c.models != p.models || c.jev != p.offersJev) {
                    edit { it.copy(providers = it.providers.map { q -> if (q.id == p.id) q.withCatalog(c) else q }) }
                }
            }
        }
    }

    // ── Providers ──────────────────────────────────────────────
    Heading("Providers")
    Quiet("Where Talon gets its AI. Private means it runs on your own machines. Not kept means the service promises not to store what you send.")
    // Nothing set up at all is the one moment to say what Armillary is
    // for. Everywhere else it is a row among rows; here it is the
    // difference between having AI in Talon and going off to sign up
    // somewhere for a key first.
    // Not providers.isEmpty(): this device is always a provider, since
    // every platform has a local rung, so that is never true. What is
    // meant is nothing the owner had to go and set up.
    // Not where credit cannot be bought here: a pitch for a service paid
    // for somewhere else is what the store forbids.
    val noDeletion = remember { MutableStateFlow<Deletion?>(null) }
    val deletion by (armillary?.deletion ?: noDeletion).collectAsState()
    deletion?.let { Quiet(deletionLine(it)) }
    if (chat.isEmpty() && deletion == null && io.nisfeb.talon.ui.isArmillaryPurchaseSupported) StartWithArmillary(
        onStart = {
            // And make it the default, where there is none: the card says
            // there is nothing more to set up, and with no default model
            // nothing resolves and no feature runs. A blank model is the
            // ship's first, which is what resolve reads it as.
            edit { it.copy(providers = it.providers + AiProvider(ARMILLARY_PROVIDER, ProviderKind.Armillary, ProviderKind.Armillary.label), defaultModel = it.defaultModel ?: ModelRef(ARMILLARY_PROVIDER, "")) }
            armillary?.setUpDevice(io.nisfeb.talon.ui.platformLabel)
        },
    )
    // This device's own model is triage's alone: it is on the Orrery page.
    showOrder(chat, profile.defaultModel).forEach { p ->
        ProviderCard(
            p = p,
            catalog = catalog,
            armillary = armillary,
            onSave = { next -> edit { it.copy(providers = it.providers.map { q -> if (q.id == next.id) next else q }) } },
            onRemove = { edit { it.without(p.id) } },
        )
    }
    AddProvider(hasArmillary = profile.provider(ARMILLARY_PROVIDER) != null) { kind ->
        // One Armillary row, on the stable id the repo writes to: this
        // device buys from one ship, its own.
        val p = if (kind == ProviderKind.Armillary) {
            AiProvider(ARMILLARY_PROVIDER, kind, kind.label)
        } else {
            AiProvider("p" + nowMs().toString(36), kind, kind.label, baseUrl = if (kind == ProviderKind.OpenAiCompatible && !isTouchPrimary) "http://localhost:1234/v1" else null)
        }
        // The first one is the default model too: with none, nothing
        // resolves, and the features that need a model stayed hidden
        // with nothing saying why. A blank model is the provider's own.
        edit { it.copy(providers = it.providers + p, defaultModel = it.defaultModel ?: ModelRef(p.id, "")) }
        if (kind == ProviderKind.Armillary) armillary?.setUpDevice(io.nisfeb.talon.ui.platformLabel)
    }

    // ── Default model ──────────────────────────────────────────
    Spacer(Modifier.height(12.dp))
    Heading("Default model")
    Quiet("Used by every feature unless you pick another below.")
    ModelPicker(profile, profile.defaultModel, allowDefault = false, providers = chat) { ref -> edit { it.copy(defaultModel = ref, defaultOwn = true) } }
    profile.vendorDefault()?.takeIf { profile.defaultModel != ModelRef(ARMILLARY_PROVIDER, "") }?.let { v ->
        TextButton(onClick = { edit { it.copy(defaultModel = ModelRef(ARMILLARY_PROVIDER, ""), defaultOwn = false) } }) { Text("Use Armillary's pick, ${shortModelName(v)}") }
    }
    Quiet(
        "Catch-up and the assistant send the messages you ask about to this model. Pick one marked Private or Not kept so they are not stored. " +
            if (profile.providers.none { it.kind == ProviderKind.OpenAiCompatible }) "LM Studio or Ollama on a computer of yours is private: add it as a server of your own above." else "",
    )

    // ── Features ───────────────────────────────────────────────
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Heading("Features")
    // The models an Armillary vendor picks (armillary 19). A feature the
    // owner picked by hand, here or on the ship's orrery, has one button
    // back; orrery reads Armillary itself when told to follow.
    if (profile.armillary()?.suggested?.isNotEmpty() == true) {
        Quiet("Armillary picks a model for each feature. Choose your own to change it.")
    }
    FeatureRow(
        "Catch-up", "Offers a summary when you open a chat with unread messages. Those messages go to the model below when you tap it.",
        on = profile.isOn(AiFeature.CatchUp), spent = spend[AiFeature.CatchUp.name],
        onSwitch = { on -> setFeature(AiFeature.CatchUp) { it.copy(on = on) } },
    ) {
        FeatureModel(
            profile, AiFeature.CatchUp, chat, reads = true, armillaryMode = armillaryMode?.mode,
            onFollow = { setFeature(AiFeature.CatchUp) { it.followingVendor() } },
        ) { ref -> setFeature(AiFeature.CatchUp) { it.copy(model = ref, own = true) } }
    }
    if (isAssistantSupported) FeatureRow(
        "Assistant", "Ask about your chats, or have it send and reply for you. It asks before changing anything. Loops run on it, and only while it is on.",
        on = profile.isOn(AiFeature.Assistant), spent = spend[AiFeature.Assistant.name],
        onSwitch = { on -> setFeature(AiFeature.Assistant) { it.copy(on = on) } },
    ) {
        FeatureModel(
            profile, AiFeature.Assistant, chat, reads = true, flag = { if (it.tools == false) "no tool use" else null }, armillaryMode = armillaryMode?.mode,
            onFollow = { setFeature(AiFeature.Assistant) { it.followingVendor() } },
        ) { ref -> setFeature(AiFeature.Assistant) { it.copy(model = ref, own = true) } }
    }
    // Where a party line can be recorded: there is nothing to transcribe elsewhere.
    if (isCallRecordingSupported) {
        val speech = profile.providers.filter { it.kind == ProviderKind.OpenAi || it.kind == ProviderKind.OpenAiCompatible }
        FeatureRow(
            "Transcription", "Writes recorded party lines up as text. Needs an OpenAI account or a server of your own with a speech model.",
            on = profile.isOn(AiFeature.Transcription), spent = null, switchEnabled = speech.isNotEmpty(),
            onSwitch = { on -> setFeature(AiFeature.Transcription) { it.copy(on = on) } },
        ) {
            if (speech.isEmpty()) Quiet("Add an OpenAI provider, or a server of your own with a speech model.")
            else ModelPicker(profile, profile.transcriptionRef(), allowDefault = false, providers = speech, only = { it.speech }) { ref ->
                setFeature(AiFeature.Transcription) { it.copy(model = ref) }
            }
        }
    }
    // The on-device index: search by meaning, topics and highlights.
    if (isOnDeviceAiSupported) AiSettings.Feature.values()
        .filter { !it.requiresCloudKey && isOnDeviceAiFeatureSupported(it) }
        .forEach { feature ->
            FeatureRow(feature.label, feature.description, on = aiFeatureEnabled(cfg, feature), spent = null, onSwitch = { aiSettings.setFeature(feature, it) })
        }

}

/**
 * Settings > Orrery: what Orrery is, in words for anyone, and one switch
 * for every device, off until the owner turns it on. On, every device
 * reads its own calls and status lines for Orrery, each under a key it
 * makes for itself ([settleOrreryGate]); the rest is the ship's, set on
 * Orrery's own page, which one row opens. Off, nothing of Orrery runs in
 * Talon, and turning it off stops the ship's own reading and writing too
 * ([OrreryRepo.switchOff]).
 */
@Composable
fun OrrerySettingsSection(aiSettings: AiSettingsRepository, orrery: OrreryRepo?) {
    val scope = rememberCoroutineScope()
    val cfg by aiSettings.state.collectAsState()
    val noFlag = remember { MutableStateFlow(false) }
    val noDecide = remember { MutableStateFlow(DecideSettings()) }
    val noGen = remember { MutableStateFlow<io.nisfeb.talon.orrery.GeneratorSettings?>(null) }
    val noShip = remember { MutableStateFlow(OrreryAvailability.UNKNOWN) }
    val fed by (orrery?.enabled ?: noFlag).collectAsState()
    val decide by (orrery?.decide?.settings ?: noDecide).collectAsState()
    val gen by (orrery?.generatorSettings ?: noGen).collectAsState()
    val availability by (orrery?.availability ?: noShip).collectAsState()
    val spend by AiSpend.month.collectAsState()
    LaunchedEffect(Unit) { AiSpend.load() }
    // Off, the generator's settings are not asked for: nothing of Orrery is.
    if (orrery != null && cfg.savedProfile?.orrery == true) LaunchedEffect(orrery) { orrery.loadGenerator() }
    fun starting(): AiProfile = migrateProfile(
        aiSettings.state.value,
        ProfileInputs(
            orreryFed = orrery?.enabled?.value == true,
            generatorOn = orrery?.generatorSettings?.value?.enabled == true,
            generatorUrl = orrery?.generatorSettings?.value?.url,
            generatorModel = orrery?.generatorSettings?.value?.model,
        ),
    )
    val started = remember(cfg, fed, gen) { starting() }
    val profile = cfg.savedProfile ?: started
    fun edit(change: (AiProfile) -> AiProfile) = aiSettings.setProfile(change(aiSettings.state.value.savedProfile ?: starting()))
    fun setFeature(f: AiFeature, change: (FeatureSetting) -> FeatureSetting) =
        edit { p -> p.copy(features = p.features + (f to change(p.features[f] ?: FeatureSetting()))) }
    val on = profile.orrery == true
    val orreryHere = orrery != null && availability == OrreryAvailability.PRESENT
    // A ship without Orrery has nothing to turn on: turned on, the page
    // was a row of greyed-out switches.
    val missing = !on && orrery != null && availability == OrreryAvailability.MISSING
    var busy by remember { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    OrreryAbout()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Use Orrery", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Quiet(
                when {
                    busy -> "Turning Orrery off."
                    on -> "On, for all your devices."
                    missing -> "Orrery is not on your ship. Install it from the Apps page."
                    else -> "Off, for all your devices. Nothing of Orrery runs."
                },
            )
        }
        Switch(
            checked = on,
            enabled = !busy && !missing,
            onCheckedChange = { want ->
                note = null
                if (want) edit { it.copy(orrery = true) } else confirmOff = true
            },
        )
    }
    note?.let { Quiet(it, error = true) }
    if (confirmOff) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Turn Orrery off?") },
            text = {
                Text(
                    "On all your devices, Talon stops reading and sending, and your ship stops reading your chats " +
                        "and email and writing your brief. Each device's key to Orrery is taken back. What Orrery " +
                        "already wrote down stays on your ship until you delete it there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOff = false
                    busy = true
                    scope.launch {
                        val off = { edit { it.copy(orrery = false) }; Unit }
                        val missed = orrery?.switchOff(after = off) ?: run { off(); emptyList() }
                        note = missed.takeIf { it.isNotEmpty() }?.let {
                            "Orrery is off, but your ship did not confirm it stopped ${it.joinToString(" and ")}. Check Orrery's own settings on your ship."
                        }
                        busy = false
                    }
                }) { Text("Turn off") }
            },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Keep it on") } },
        )
    }
    if (!on) return

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Heading("Settings")
    if (orrery == null) Quiet("Sign in to a ship to set Orrery up.")
    if (orrery != null && orreryHere) GeneratorOffer(orrery, profile) { ref -> setFeature(AiFeature.OrreryGenerator) { it.copy(model = ref, own = true) } }
    if (orrery != null) ReadingRow(orrery, profile, orreryHere, spend[AiFeature.OrreryTriage.name]) { ref -> setFeature(AiFeature.OrreryTriage) { it.copy(model = ref, own = true) } }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    // Until the owner flips it here, the decision model is what this device had.
    DecisionRow(profile, profile.jev ?: decide.on, spend[AiSpend.JEV]) { v -> edit { it.copy(jev = v) } }
    if (orrery != null && orreryHere) MoreOrreryRow(orrery)
}

/** What Orrery is and what turning it on allows, for someone who has never heard of it. */
@Composable
private fun OrreryAbout() {
    Heading("Orrery")
    Text(
        "Orrery is a private notebook about your life, kept on your own ship. When it is on, Talon and your ship " +
            "notice things that matter in your conversations, like where someone is, a plan you made, or something " +
            "a friend is going through, and write them down in Orrery. Orrery then helps you keep track: it can " +
            "remind you, write you a daily brief, and suggest things to do.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("Turning it on lets:", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
    listOf(
        // Orrery 58 turned every reader on from the start, and its chat
        // reader reads every DM until some are picked: "the chats you
        // choose" undersold what the switch allows.
        "Your ship read your chats and email: every direct message unless you pick which ones, the group " +
            "channels you pick, and your mail. You can turn each of these off in Orrery's own settings on your ship.",
        "Talon read your calls and your contacts' status lines on each of your devices, and share your contacts " +
            "and, if you allow it, where you are.",
        "An AI model do the reading. You choose which: one on this device, one on a computer of yours, or a " +
            "service you pick. A service sees the text it reads.",
        "Orrery keep what it finds on your ship. It is not sent to the makers of Talon or anyone else.",
        "Orrery suggest things like a calendar entry or a message. Nothing is done until you approve it " +
            "under Actions.",
    ).forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodyMedium) }
    Quiet(
        "Turning it off stops all of it, on every device: Talon stops reading and sending, your ship stops " +
            "reading your chats and email and writing your brief, and Orrery disappears from Talon. What Orrery " +
            "already wrote down stays on your ship until you delete it there.",
    )
}


@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun Quiet(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── Providers ──────────────────────────────────────────────────────

@Composable
private fun ProviderCard(
    p: AiProvider,
    catalog: ModelCatalog,
    armillary: ArmillaryRepo?,
    onSave: (AiProvider) -> Unit,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var key by remember(p.apiKey) { mutableStateOf(p.apiKey) }
    var url by remember(p.baseUrl) { mutableStateOf(p.baseUrl.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var note by remember(p.id) { mutableStateOf<Pair<String, Boolean>?>(null) }
    val edited = p.copy(apiKey = key.trim(), baseUrl = url.trim().ifBlank { null })
    val dirty = edited.apiKey != p.apiKey || edited.baseUrl != p.baseUrl

    // Saved first, then the models fetched: a fetch that hangs used to
    // hold the key in the screen, and backing out of Settings took the
    // key with it. Anthropic's and OpenAI's lists need the key, and a
    // server's needs the server, so a list is the test; OpenRouter lists
    // its models for anyone, so its key is tried as well.
    fun load(of: AiProvider) {
        onSave(of)
        scope.launch {
            busy = true
            runCatching { catalog.fetch(of) }
                .onSuccess { c ->
                    onSave(of.withCatalog(c))
                    note = if (of.kind != ProviderKind.OpenRouter) null else catalog.check(of).let { r -> if (r.ok) null else r.detail to true }
                }
                .onFailure { note = (it.message ?: "No answer.") to true }
            busy = false
        }
    }

    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (p.kind == ProviderKind.Armillary) io.nisfeb.talon.ui.ArmillaryLogo()
                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (p.label != p.kind.label) Quiet(p.kind.label)
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = {
                    // A lease is the vendor's key on loan: give it back,
                    // or it sits out there held by a device that has
                    // forgotten it. Not first: the row waited on the
                    // ship's answer, and on a busy ship Remove did
                    // nothing anyone could see.
                    if (p.kind == ProviderKind.Armillary) armillary?.giveLeaseBack()
                    onRemove()
                }) { Text("Remove") }
            }
            if (p.kind == ProviderKind.Armillary) {
                ArmillaryLines(p, armillary)
                return@Column
            }
            if (p.kind == ProviderKind.OpenAiCompatible) {
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Address") }, placeholder = { Text("http://localhost:1234/v1") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = key, onValueChange = { key = it },
                label = { Text(if (p.kind == ProviderKind.OpenAiCompatible) "Key (leave empty if your server has none)" else "API key") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    io.nisfeb.talon.ui.IconButton(tip = if (reveal) "Hide key" else "Show key", onClick = { reveal = !reveal }) {
                        Icon(if (reveal) TalonIcons.VisibilityOff else TalonIcons.Visibility, contentDescription = if (reveal) "Hide key" else "Show key")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Quiet(providerSummary(p))
            note?.let { (text, bad) -> Quiet(text, error = bad) }
            // One button: Save keeps the key, fetches the models and tries
            // it. Shown until a list has come, for a server added with its
            // address already filled in. The list is fetched again each
            // time Settings opens.
            if (dirty || p.models.isEmpty()) OutlinedButton(enabled = !busy, onClick = { load(edited) }) { Text("Save") }
        }
    }
}

/**
 * Providers as Settings lists them: the default model's first, then the
 * ones set up, then those still waiting on a key or an address, and this
 * device's own model last. Profile order within each. Profile order put
 * a keyless OpenRouter above a working Armillary (sneagan, 2026-10-08).
 */
internal fun showOrder(providers: List<AiProvider>, default: ModelRef?): List<AiProvider> =
    providers.sortedBy { p ->
        when {
            p.id == default?.provider -> 0
            p.kind == ProviderKind.ThisDevice -> 3
            p.isSetUp() -> 1
            else -> 2
        }
    }

/** Has what a call needs: a key, or a server's address. Armillary's key is the one its ship gave this device. */
private fun AiProvider.isSetUp(): Boolean =
    if (kind == ProviderKind.OpenAiCompatible) !baseUrl.isNullOrBlank() else apiKey.isNotBlank()

internal fun providerSummary(p: AiProvider): String {
    val retention = when (p.kind) {
        ProviderKind.Anthropic, ProviderKind.OpenAi -> " Whether it keeps what you send depends on your account."
        ProviderKind.OpenAiCompatible -> if (p.isPrivate) " Private: on your own machine or network." else " Not on your network, so it is an online service."
        else -> ""
    }
    if (p.models.isEmpty()) return "Save to fetch its models." + retention
    val kept = p.models.count { it.zdr }
    return "${p.models.size} models" +
        (if (p.kind == ProviderKind.OpenRouter) ", $kept of them Not kept" else "") +
        "." + retention
}

/** What this device reads with, and its download where it needs one. */
@Composable
private fun DeviceModelLine(orrery: OrreryRepo?) {
    val scope = rememberCoroutineScope()
    val noModel = remember { MutableStateFlow<Pair<String, RungStatus>?>(null) }
    val noProgress = remember { MutableStateFlow<Float?>(null) }
    val model by (orrery?.model ?: noModel).collectAsState()
    val download by (orrery?.download ?: noProgress).collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    Quiet("Runs on this device. Nothing it reads leaves it.")
    if (orrery != null) Quiet(
        when {
            model == null -> "Checking this device."
            model!!.second == RungStatus.Ready -> "Reads with ${model!!.first}."
            model!!.second is RungStatus.NeedsDownload ->
                "${model!!.first} can read here after a download of about ${(model!!.second as RungStatus.NeedsDownload).bytes / 1_000_000} MB."
            else -> (model!!.second as RungStatus.Unavailable).reason
        },
    )
    if (orrery != null && model?.second is RungStatus.NeedsDownload) {
        if (download != null) LinearProgressIndicator(progress = { download!! }, modifier = Modifier.fillMaxWidth())
        else TextButton(onClick = { scope.launch { orrery.prepareModel().onFailure { note = it.message ?: "The download did not finish." } } }) { Text("Download the model") }
    }
    note?.let { Quiet(it, error = true) }
    if (orrery != null) LaunchedEffect(orrery) { orrery.refreshModel() }
}

/**
 * Armillary's card: no address, no key, because the ship holds both.
 * What it has instead is a balance, what the plans cost, and the two
 * buttons that turn money into credit on the vendor's ledger.
 */
@Composable
private fun ArmillaryLines(p: AiProvider, repo: ArmillaryRepo?) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val noWhere = remember { MutableStateFlow(ArmillaryAvailability.UNKNOWN) }
    val noAccount = remember { MutableStateFlow<Account?>(null) }
    val noPlans = remember { MutableStateFlow<List<Plan>>(emptyList()) }
    val noInference = remember { MutableStateFlow<Inference?>(null) }
    val noBusy = remember { MutableStateFlow(false) }
    val where by (repo?.availability ?: noWhere).collectAsState()
    val account by (repo?.account ?: noAccount).collectAsState()
    val plans by (repo?.plans ?: noPlans).collectAsState()
    val inference by (repo?.inference ?: noInference).collectAsState()
    val refreshing by (repo?.refreshing ?: noBusy).collectAsState()
    val noPayment = remember { MutableStateFlow<Payment?>(null) }
    val payment by (repo?.payment ?: noPayment).collectAsState()
    val noOffer = remember { MutableStateFlow<String?>(null) }
    val offer by (repo?.offer ?: noOffer).collectAsState()
    val settingUp by (repo?.settingUp ?: noBusy).collectAsState()
    val keyProblem by (repo?.keyProblem ?: noOffer).collectAsState()
    var more by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var buying by remember { mutableStateOf(false) }
    var subscribing by remember { mutableStateOf<Plan?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var changingVendor by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var leo by remember { mutableStateOf(false) }
    var vendorTyped by remember { mutableStateOf("") }
    val here = repo != null && where == ArmillaryAvailability.PRESENT
    // An error surface sent the person here to top up: open the sheet
    // for them, once, and forget the ask. Whenever it is asked, not only
    // on arriving: the Top up under orrery's status asks from this screen.
    val topUpAsked by AiSettings.pendingTopUp.collectAsState()
    LaunchedEffect(topUpAsked) {
        if (topUpAsked) {
            AiSettings.pendingTopUp.value = false
            buying = true
        }
    }

    /** Open a checkout and send the person to it. */
    fun buy(rail: String, plan: String?, amountMicro: Long?) {
        val r = repo ?: return
        note = "Asking ${account?.vendor?.ifBlank { null } ?: "the vendor"} for a checkout. From some ships this takes a minute." to false
        scope.launch {
            r.topUp(rail, plan, amountMicro)
                .onSuccess { url -> note = null; uri.openUri(url) }
                .onFailure { note = (it.message ?: "The vendor did not answer.") to true }
        }
    }

    // Said only when something is wrong: an app that answers needs no line.
    when (where) {
        ArmillaryAvailability.PRESENT -> Unit
        ArmillaryAvailability.MISSING -> Quiet("Armillary is not on your ship. Install it from the Apps page.")
        ArmillaryAvailability.SIGNED_OUT -> Quiet("Signed out of the ship.")
        ArmillaryAvailability.UNKNOWN -> Quiet("Checking your ship.")
    }
    if (where == ArmillaryAvailability.MISSING) Quiet("Armillary is published by ~ricsul-bilwyt, the same as Orrery and the Calendar.")
    account?.let { a ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            val vendor = a.vendor.ifBlank { "no vendor yet" }
            Text(
                when {
                    !a.hasView -> "Account with $vendor"
                    a.balanceMicro <= 0 -> "No credit with $vendor"
                    else -> money(a.balanceMicro) + " credit with " + vendor
                },
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false),
            )
            TextButton(enabled = here, onClick = { changingVendor = !changingVendor; vendorTyped = "" }) { Text("Change vendor") }
        }
        if (changingVendor) {
            OutlinedTextField(
                value = vendorTyped, onValueChange = { vendorTyped = it },
                label = { Text("Another vendor") }, placeholder = { Text(ArmillaryRepo.DEFAULT_VENDOR) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Quiet("Any ship running Armillary can be your vendor, your own included.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val typed = vendorTyped.trim()
                TextButton(enabled = here && isValidPatp(typed) && typed != a.vendor, onClick = {
                    changingVendor = false
                    note = null
                    scope.launch {
                        repo?.setVendor(typed, io.nisfeb.talon.ui.platformLabel)
                            ?.onSuccess { note = "Your account is with $typed now." to false }
                            ?.onFailure { note = (it.message ?: "The ship did not answer.") to true }
                    }
                }) { Text("Use this vendor") }
                TextButton(onClick = { changingVendor = false }) { Text("Keep it") }
            }
        }
        if (a.hasView) {
            balanceWarning(a, io.nisfeb.talon.ui.isArmillaryPurchaseSupported)?.let { Quiet(it, error = true) }
            Quiet(planLine(a))
            paymentLine(payment, a.checkouts.firstOrNull { it.nonce == payment?.nonce })?.let { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (payment?.phase == Payment.Phase.WAITING) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Quiet(line, error = payment?.phase == Payment.Phase.ENDED)
                }
            }
        } else if (a.vendor.isNotBlank()) {
            IntroducingLine()
        }
    }
    // Paid for, and set up to run on it: the two features that read
    // messages wait for the owner's own word (their rule, 2026-09-28).
    offer?.let { model ->
        Text(
            readingOfferLine(model, p.models.firstOrNull { it.id == model }?.zdr == true, inference?.mode, isAssistantSupported),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { repo?.acceptOffer() }) { Text("Turn on") }
            TextButton(onClick = { repo?.declineOffer() }) { Text("Not now") }
        }
    }
    // Whether this device can use it: a key from the ship, or why not.
    if (here) inference.let { inf ->
        if (inf != null) Quiet(connectionLine(inf.mode, account, inf.models.size, io.nisfeb.talon.ui.isArmillaryPurchaseSupported))
        else if (settingUp || !refreshing) {
            Text("This device isn't connected yet.", style = MaterialTheme.typography.bodyMedium)
            keyProblem?.let { Quiet(it, error = true) }
            if (settingUp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Quiet("Asking your ship for a key. This can take half a minute.")
            } else Button(onClick = { repo?.setUpDevice(io.nisfeb.talon.ui.platformLabel) }) { Text("Set up this device") }
        }
    }
    note?.let { (text, bad) -> Quiet(text, error = bad) }

    val subscription = plans.firstOrNull { it.kind == "subscription" }
    // As many as five: on a phone the rest go on to a second line whole.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Buying is the one part of this card that a store may refuse to
        // carry. The balance, the warning, the history and the vendor
        // stand without it; see isArmillaryPurchaseSupported.
        if (io.nisfeb.talon.ui.isArmillaryPurchaseSupported) {
            OutlinedButton(enabled = repo != null && where == ArmillaryAvailability.PRESENT, onClick = { buying = true }) { Text("Top up") }
            if (subscription != null && account?.subscriptionActive != true) {
                TextButton(enabled = here, onClick = { subscribing = subscription; buying = true }) { Text(subscribeLabel(subscription)) }
            }
        }
        if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else TextButton(enabled = repo != null, onClick = {
            note = null
            scope.launch {
                val r = repo ?: return@launch
                r.refresh(fresh = true).onFailure { note = (it.message ?: "The ship did not answer.") to true }
                // A device with no key asks again: nothing else on the card did.
                if (r.inference.value == null && r.availability.value == ArmillaryAvailability.PRESENT) {
                    r.setUpDevice(io.nisfeb.talon.ui.platformLabel)
                }
            }
        }) { Text("Refresh") }
        // The rarely used ones, behind one button.
        val showHistory = account?.hasView == true
        val showLeo = io.nisfeb.talon.ui.isBraveLeoSupported && here
        val showCancel = account?.subscriptionActive == true
        // Not gated on buying: the account is deletable wherever it is
        // held (App Review guideline 5.1.1(v)).
        val showDelete = here && account?.vendor?.isNotBlank() == true
        if (showHistory || showLeo || showCancel || showDelete) androidx.compose.foundation.layout.Box {
            TextButton(onClick = { more = true }) { Text("More") }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                if (showHistory) DropdownMenuItem(text = { Text(if (history) "Hide history" else "History") }, onClick = { history = !history; more = false })
                if (showLeo) DropdownMenuItem(text = { Text("Use in Brave Leo") }, onClick = { leo = !leo; more = false })
                if (showCancel) DropdownMenuItem(text = { Text("Cancel subscription") }, onClick = { confirmCancel = true; more = false })
                if (showDelete) DropdownMenuItem(
                    text = { Text("Delete account", color = MaterialTheme.colorScheme.error) },
                    onClick = { confirmDelete = true; more = false },
                )
            }
        }
    }
    // The only receipt inside the app: Stripe and BTCPay send their
    // own by email.
    if (history) account?.let { a ->
        if (a.ledger.isEmpty()) Quiet("Nothing yet.")
        a.ledger.take(HISTORY_ROWS).forEach { row ->
            val (first, second) = historyLines(row)
            Column {
                Quiet(first)
                second?.let { Quiet(it) }
            }
        }
    }
    if (leo && repo != null) BraveLeoLines(p, repo, account?.leaseDisabled == true)

    if (buying && io.nisfeb.talon.ui.isArmillaryPurchaseSupported) TopUpSheet(
        plans = plans,
        subscribing = subscribing,
        onDismiss = { buying = false; subscribing = null },
        onBuy = { rail, plan, amountMicro -> buying = false; subscribing = null; buy(rail, plan, amountMicro) },
    )
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Cancel the subscription?") },
        text = { Text("It keeps running until the end of the period you have paid for. Your balance stays as it is.") },
        confirmButton = {
            TextButton(onClick = {
                confirmCancel = false
                note = null
                scope.launch {
                    repo?.cancelSubscription()
                        ?.onSuccess { note = "Asked your ship to stop it renewing. The account says so once the vendor confirms." to false }
                        ?.onFailure { note = (it.message ?: "The ship did not answer.") to true }
                }
            }) { Text("Cancel it") }
        },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } },
    )
    if (confirmDelete) account?.let { a ->
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your Armillary account?") },
            text = { Text(deleteAccountWarning(a)) },
            confirmButton = {
                io.nisfeb.talon.ui.DestructiveTextButton(onClick = {
                    confirmDelete = false
                    note = null
                    // A deletion the ship took takes this card with it; the
                    // Providers heading says how it went from the repo.
                    scope.launch {
                        repo?.deleteAccount()
                            ?.onSuccess { answer -> deleteRefusedLine(answer, a.vendor)?.let { note = it to true } }
                            ?.onFailure { note = (it.message ?: "The ship did not answer.") to true }
                    }
                }) { Text("Delete account") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

/**
 * What Brave Leo's "Bring your own model" form asks for, each with a
 * copy button, read fresh from the ship on opening: the mode may have
 * moved since the card last read it, and a failed read says so.
 */
@Composable
private fun BraveLeoLines(p: AiProvider, repo: ArmillaryRepo, leaseDisabled: Boolean) {
    var read by remember { mutableStateOf<Result<Inference>?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(repo) { read = repo.readInference() }
    Heading("Brave Leo")
    val got = read
    if (got == null) {
        Quiet("Asking your ship for the key.")
        return
    }
    val inf = got.getOrElse {
        Quiet("Your ship did not answer for Leo: " + (it.message ?: "no answer from the ship"), error = true)
        return
    }
    when (val s = leoSetup(inf, leaseDisabled, model, p.models)) {
        is LeoSetup.Cannot -> Quiet(s.why)
        is LeoSetup.Ready -> {
            Quiet(LEO_STEPS)
            LeoField("Label", s.label)
            LeoField("Model request name", s.model) {
                if (inf.models.size > 1) androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { picking = true }) { Text("Another model") }
                    DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                        inf.models.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { model = m; picking = false }) }
                    }
                }
            }
            LeoField("Server endpoint", s.endpoint)
            s.contextSize?.let { LeoField("Context size", it.toString()) }
            LeoField("API Key", s.apiKey, shown = "\u2022".repeat(8) + s.apiKey.takeLast(4))
            Quiet(LEO_KEY_NOTE)
        }
    }
}

/** One of Leo's fields: its name as Leo's form has it, the value, and a copy button. */
@Composable
private fun LeoField(name: String, value: String, shown: String = value, extra: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Quiet(name)
            Text(shown, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
        extra()
        io.nisfeb.talon.ui.CopyButton(text = { value })
    }
}

/** What deleting the account at its vendor costs, said before it is done. */
internal fun deleteAccountWarning(a: Account): String = buildString {
    append("${a.vendor} deletes everything it holds for your ship: the account, its ledger, its checkouts and its keys.")
    if (a.subscriptionActive) append(" Your subscription stops now.")
    if (a.balanceMicro > 0) append(" The ${money(a.balanceMicro)} left on it is lost.")
    append(" Stripe and BTCPay Server keep their own records of your payments. This cannot be undone.")
}

/** A deletion the ship did not take, said on the card that is still there; null when it took it. */
internal fun deleteRefusedLine(answer: DeleteAnswer, vendor: String): String? = when (answer) {
    DeleteAnswer.Unsupported ->
        "Armillary on your ship cannot delete accounts yet. Update it from the Grubbery shell on your ship, or message $vendor to ask."
    is DeleteAnswer.Refused -> "$vendor did not delete it: ${answer.reason}"
    else -> null
}

/** What a deletion the ship took did, under Providers once the card is gone. */
internal fun deletionLine(d: Deletion): String {
    val vendor = d.vendor.ifBlank { "the vendor" }
    return when (d.answer) {
        DeleteAnswer.Queued -> "Your ship has asked $vendor to delete your Armillary account, and keeps asking until it answers."
        DeleteAnswer.NothingHeld -> "Your ship bought from no vendor, so there was no Armillary account to delete."
        else -> "Your Armillary account at $vendor is deleted. It holds nothing for your ship now."
    }
}

/**
 * What turning on catch-up and the assistant after a payment means, in
 * a sentence: the messages the owner asks about go to [model], and
 * whether anything keeps them, as [readingWarning] says of a row.
 */
internal fun readingOfferLine(model: String, zdr: Boolean, mode: String?, withAssistant: Boolean): String {
    val what = if (withAssistant) "Catch-up and the assistant are" else "Catch-up is"
    val asked = if (withAssistant) "When you ask for a summary or ask the assistant something" else "When you ask for a summary"
    val kept = when {
        mode == "proxy" && zdr -> " through the vendor's ship. The model does not keep them, and the ship does not store them."
        mode == "proxy" -> " through the vendor's ship, and the model may keep them."
        zdr -> ", which keeps nothing (zero data retention)."
        else -> ", which may keep them."
    }
    return "$what set up on $model. Turn ${if (withAssistant) "them" else "it"} on? $asked, the messages involved go to $model$kept"
}

/**
 * A vendor named and no view read yet: the hello is on its way over
 * ames. The line waits a minute before saying so, since most hellos
 * land inside it and a line that flashed would only worry people.
 */
@Composable
private fun IntroducingLine() {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(INTRODUCING_MS)
        waited = true
    }
    if (waited) Quiet("Connecting to your vendor. This can take a minute.")
}

/** How long a card with a vendor and no view stays quiet before it explains itself. */
private const val INTRODUCING_MS = 60_000L

/**
 * The top-up sheet: the vendor's sizes as buttons, any other amount in
 * dollars, and the rail. Bitcoin is the vendor's BTCPay Server, card is
 * their Stripe account; neither is ours. A subscription comes through
 * the same sheet with its plan fixed and the card rail alone, since
 * subscriptions are card only.
 */
@Composable
private fun TopUpSheet(
    plans: List<Plan>,
    subscribing: Plan?,
    onDismiss: () -> Unit,
    onBuy: (rail: String, plan: String?, amountMicro: Long?) -> Unit,
) {
    val sizes = topUpSizes(plans)
    val minMicro = minTopUp(plans)
    var rail by remember { mutableStateOf("stripe") }
    var size by remember { mutableStateOf<Plan?>(null) }
    var amount by remember { mutableStateOf("") }
    val custom = dollarsToMicro(amount)
    val chosen = subscribing != null || size != null || (custom != null && custom >= minMicro)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (subscribing != null) "Subscribe" else "Top up") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (subscribing != null) {
                    Text(subscriptionLine(subscribing), style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (sizes.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sizes.forEach { plan ->
                            val label = @Composable { Text(money(plan.priceMicro)) }
                            if (size?.id == plan.id) Button(onClick = { size = null }, modifier = Modifier.weight(1f), content = { label() })
                            else OutlinedButton(onClick = { size = plan; amount = "" }, modifier = Modifier.weight(1f), content = { label() })
                        }
                    }
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it; if (it.isNotBlank()) size = null },
                        label = { Text("Another amount") },
                        placeholder = { Text(money(minMicro).removePrefix("$")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Quiet("In dollars. The smallest the vendor takes is " + money(minMicro) + ".")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rails = if (subscribing != null) listOf("stripe" to "Card") else listOf("stripe" to "Card", "btcpay" to "Bitcoin")
                    rails.forEach { (id, label) ->
                        if (rail == id) Button(onClick = { rail = id }) { Text(label) }
                        else OutlinedButton(onClick = { rail = id }) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen, onClick = {
                when {
                    subscribing != null -> onBuy("stripe", subscribing.id, null)
                    size != null -> onBuy(rail, size!!.id, null)
                    else -> onBuy(rail, null, custom)
                }
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** The vendor's top-up plans as sizes, cheapest first. */
internal fun topUpSizes(plans: List<Plan>): List<Plan> =
    plans.filter { it.kind == "topup" }.sortedBy { it.priceMicro }

/** The smallest amount the vendor takes: its cheapest size, else five dollars. */
internal fun minTopUp(plans: List<Plan>): Long =
    topUpSizes(plans).firstOrNull()?.priceMicro?.takeIf { it > 0 } ?: DEFAULT_MIN_TOPUP

/** "Talon Pro, $10.00 a month for $12.00 of credit". */
internal fun subscriptionLine(plan: Plan): String {
    val every = when (plan.interval) {
        "year" -> " a year"
        "month" -> " a month"
        else -> ""
    }
    return plan.name + ", " + money(plan.priceMicro) + every + " for " + money(plan.creditMicro) + " of credit"
}

/** What the card's Subscribe button says. */
internal fun subscribeLabel(plan: Plan): String = "Subscribe: " + subscriptionLine(plan)

/** Five dollars, which is armillary's own default smallest top-up. */
private const val DEFAULT_MIN_TOPUP = 5_000_000L

/** Dollars as typed to microdollars, or null when that is not a number. */
internal fun dollarsToMicro(typed: String): Long? {
    val t = typed.trim().removePrefix("$").trim()
    if (t.isEmpty()) return null
    val d = t.toDoubleOrNull() ?: return null
    if (d <= 0.0) return null
    return kotlin.math.round(d * 1_000_000.0).toLong()
}

/** What the account's plan line says, subscribed or not. */
internal fun planLine(a: Account): String = when {
    a.subscriptionActive -> a.plan.ifBlank { "Subscribed" } + (a.renews?.let { ", renews $it" } ?: "") + "."
    a.plan.isNotBlank() -> a.plan + "."
    else -> "Pay as you go."
}

/**
 * What the card says about the checkout this session opened, or null
 * when there is nothing to say and the plain balance stands. The row's
 * own status wins once the balance has not moved: the vendor knows
 * more about a failed or expired checkout than a watch does.
 */
internal fun paymentLine(p: Payment?, row: Checkout?): String? {
    if (p == null) return null
    if (p.phase == Payment.Phase.PAID) return "Paid: " + money(p.addedMicro) + " added"
    when (row?.status) {
        "processing" -> return "Payment seen, waiting for confirmation"
        "failed" -> return "The payment did not go through"
        "expired" -> return "The checkout expired before it was paid"
        "refused" -> return row.note.ifBlank { "The vendor refused the checkout" }
    }
    return when (p.phase) {
        Payment.Phase.WAITING ->
            if (p.rail == ArmillaryRepo.BTC_RAIL) "Waiting for your bitcoin payment to confirm, usually ten to twenty minutes"
            else "Waiting for your payment"
        Payment.Phase.UNSEEN -> "No payment seen yet. If you paid, it arrives within a few minutes; Refresh to check."
        else -> null
    }
}

/** How many ledger rows the history shows, which is as many as the view carries. */
internal const val HISTORY_ROWS = 50

/**
 * A ledger row as the history shows it: the date, the kind, the
 * amount, and on a credit its rail; a charge names the model and the
 * token counts on a second line where the row has them.
 */
internal fun historyLines(r: LedgerRow): Pair<String, String?> {
    val kind = when (r.kind) {
        "credit" -> "Credit"
        "debit" -> "Charge"
        "refund" -> "Refund"
        else -> r.kind.replaceFirstChar { it.uppercase() }
    }
    val rail = when (r.rail) {
        "stripe" -> "card"
        "btcpay" -> "bitcoin"
        else -> r.rail
    }
    val stamp = r.at.take(16).replace('T', ' ')
    val first = listOf(stamp, kind, money(r.amountMicro)).filter { it.isNotBlank() }.joinToString("  ") +
        (if (r.kind == "credit" && rail.isNotBlank()) ", by $rail" else "")
    val second = if (r.kind == "debit" && r.model.isNotBlank()) {
        r.model + ", " + r.tokensIn + " in, " + r.tokensOut + " out"
    } else {
        null
    }
    return first to second
}

/** Below this much credit the card says so in red, before a request fails. */
internal const val LOW_BALANCE_MICRO = 1_000_000L

/**
 * A warning under the balance, or null when there is enough. Empty means
 * the next request fails with a 402; low means it soon will.
 *
 * [canBuy] false is an app that cannot take a payment, so the line names
 * no button that is not on the screen, and no other place to pay.
 */
internal fun balanceWarning(a: Account, canBuy: Boolean = true): String? = when {
    a.balanceMicro <= 0 ->
        if (canBuy) "Empty: requests fail until you top up." else "Empty: requests fail until there is credit on the account."
    a.balanceMicro < LOW_BALANCE_MICRO ->
        if (canBuy) "Almost out: top up before your next request fails." else "Almost out: requests fail once it runs out."
    else -> null
}

/**
 * This device's connection, once it has a key: how Talon reaches the
 * model, and how many it may use. [canBuy] false is a storefront that
 * may not be told to pay outside the App Store (isArmillaryPurchaseSupported),
 * so the line names no top up there, as [balanceWarning] does.
 */
internal fun connectionLine(mode: String?, account: Account?, models: Int, canBuy: Boolean = true): String {
    // How a request travels is the vendor's business; what the owner
    // needs is whether it works. Where credit cannot be bought here, the
    // line points nowhere to buy it.
    val how = when {
        account?.leaseDisabled == true -> if (canBuy) "Out of credit. Top up to keep using AI." else "Out of credit."
        mode == "proxy" -> "Connected through your vendor."
        else -> "Connected."
    }
    return "$how ${plural(models, "model")}."
}

private fun plural(n: Int, one: String) = "$n $one" + if (n == 1) "" else "s"

/**
 * What to do when there is no provider at all.
 *
 * Every other provider starts with going somewhere else, making an
 * account and coming back with a key. This one is the owner's own ship
 * buying the inference for them, so the pitch is the thing itself:
 * somebody else deals with the AI, you get the AI.
 */
@Composable
private fun StartWithArmillary(onStart: () -> Unit) {
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Let your ship handle it", style = MaterialTheme.typography.titleSmall)
            Text(
                "Armillary buys the models for you, through your own ship. No accounts to make, no API keys to " +
                    "copy in, no bill to watch on somebody else's dashboard: you top up, your ship spends it, " +
                    "and the balance is on this screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Already have a key from OpenRouter, Anthropic or OpenAI? Add it below instead, or run a model " +
                    "on this machine.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onStart, modifier = Modifier.align(Alignment.End)) { Text("Set up Armillary") }
        }
    }
}

@Composable
private fun AddProvider(hasArmillary: Boolean, onAdd: (ProviderKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }) { Text("Add a provider") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val kinds = buildList {
                // First, because it is the one that asks nothing of the
                // owner: the others all begin with going and getting an
                // API key. One at most, since a device buys from one
                // ship, its own. (The enum keeps it last, where an old
                // build that cannot decode the name will not meet it.)
                if (!hasArmillary) add(ProviderKind.Armillary)
                add(ProviderKind.OpenRouter)
                add(ProviderKind.Anthropic)
                add(ProviderKind.OpenAi)
                add(ProviderKind.OpenAiCompatible)
            }
            kinds.forEach { k ->
                DropdownMenuItem(
                    text = { Text(k.label) },
                    leadingIcon = if (k == ProviderKind.Armillary) ({ io.nisfeb.talon.ui.ArmillaryLogo() }) else null,
                    onClick = { open = false; onAdd(k) },
                )
            }
        }
    }
}

// ── Model pickers ──────────────────────────────────────────────────

private const val DEVICE_MODEL = "This device (private)"

private fun AiProvider.offered(): List<ModelInfo> =
    if (kind == ProviderKind.ThisDevice) listOf(ModelInfo("", DEVICE_MODEL)) else models

/**
 * What a model picker shows for [ref]. An Armillary model goes by its
 * short name beside Armillary's logo, and Armillary's default (a blank
 * model, or the vendor's pick for a feature, [vendorPick]) is "Default ·
 * Opus 5.5". It read "Default: its default, Armillary" (sneagan, 2026-10-09).
 */
internal fun refLabel(profile: AiProfile, ref: ModelRef?, vendorPick: Boolean = false): String {
    val r = ref ?: return "No model yet"
    val p = profile.provider(r.provider) ?: return "${r.model}, on a provider no longer here"
    // Said three times over: "This device's own model, On this device · Private".
    if (p.kind == ProviderKind.ThisDevice) return DEVICE_MODEL
    if (p.kind == ProviderKind.Armillary) {
        val model = profile.resolve(r)?.model.orEmpty()
        val name = if (model.isBlank()) "no models yet" else shortModelName(model)
        return if (vendorPick || r.model.isBlank()) "Default · $name" else name
    }
    val m = p.offered().firstOrNull { it.id == r.model }
    val name = m?.name ?: r.model.ifBlank { "its default" }
    val badge = when {
        p.isPrivate -> " · Private"
        m?.zdr == true -> " · Not kept"
        else -> ""
    }
    return "$name, ${p.label}$badge"
}

/** A feature row following the default model: "Default: " and the model, or Armillary's own "Default · Opus 5.5". */
internal fun defaultLabel(profile: AiProfile, ref: ModelRef? = profile.defaultModel): String {
    ref ?: return "No model yet. Add a provider above."
    val d = refLabel(profile, ref)
    return if (d.startsWith("Default")) d else "Default: $d"
}

/**
 * A model id as a person says it: "anthropic/claude-opus-5.5" is "Opus
 * 5.5", "openai/gpt-5" is "GPT-5", "google/gemini-2.5-pro" is "Gemini
 * 2.5 Pro". Dashed version numbers join with a dot, and a date stamp
 * goes.
 */
internal fun shortModelName(id: String): String {
    val base = id.substringAfterLast('/').substringBefore(':').removePrefix("claude-")
    val words = mutableListOf<String>()
    for (w in base.split('-').filter { it.isNotEmpty() }) {
        if (w.length == 8 && w.all { it.isDigit() }) continue
        val last = words.lastOrNull()
        if (last != null && w.all { it.isDigit() } && last.last().isDigit() && last.all { it.isDigit() || it == '.' }) words[words.size - 1] = "$last.$w"
        else words += w
    }
    if (words.isEmpty()) return id
    val cap = words.map { w -> if (w.first().isLetter()) w.replaceFirstChar { it.uppercase() } else w }
    // OpenAI writes its own as one word: GPT-5, GPT-4o Mini.
    if (words[0].equals("gpt", ignoreCase = true)) {
        return if (cap.size == 1) "GPT" else (listOf("GPT-" + cap[1]) + cap.drop(2)).joinToString(" ")
    }
    return cap.joinToString(" ")
}

/**
 * A searchable list of models, grouped by provider, with their badges.
 * Typing an id a provider did not list offers it anyway: a server may
 * load a model on demand, and a list may not have been fetched.
 */
@Composable
private fun ModelPicker(
    profile: AiProfile,
    selected: ModelRef?,
    allowDefault: Boolean,
    providers: List<AiProvider>,
    only: (ModelInfo) -> Boolean = { true },
    flag: (ModelInfo) -> String? = { null },
    /** [selected] is the Armillary vendor's pick, shown as its default. */
    vendorPick: Boolean = false,
    /** What "Default" runs on this row: the default model, except where a feature follows only some of it. */
    defaultRef: ModelRef? = profile.defaultModel,
    onPick: (ModelRef?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            val shown = if (selected == null && allowDefault) defaultRef else selected
            if (shown?.let { profile.provider(it.provider) }?.kind == ProviderKind.Armillary) {
                io.nisfeb.talon.ui.ArmillaryLogo(modifier = Modifier.padding(end = 8.dp))
            }
            Text(
                if (selected == null && allowDefault) defaultLabel(profile, defaultRef) else refLabel(profile, selected, vendorPick),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(TalonIcons.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; query = "" }, modifier = Modifier.heightIn(max = 460.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search, or type a model's id", maxLines = 1, overflow = TextOverflow.Ellipsis) }, singleLine = true,
                modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            )
            if (allowDefault) DropdownMenuItem(
                text = { Text(defaultLabel(profile, defaultRef)) },
                leadingIcon = if (defaultRef?.let { profile.provider(it.provider) }?.kind == ProviderKind.Armillary) {
                    { io.nisfeb.talon.ui.ArmillaryLogo() }
                } else null,
                onClick = { open = false; query = ""; onPick(null) },
            )
            val q = query.trim()
            showOrder(providers, profile.defaultModel).forEach { p ->
                val all = p.offered().filter(only)
                val shown = rankModels(q, all).take(80)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (p.kind == ProviderKind.Armillary) io.nisfeb.talon.ui.ArmillaryLogo(size = 14.dp)
                    Text(p.label, style = MaterialTheme.typography.labelMedium)
                }
                shown.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (p.isPrivate) Badge("Private")
                                if (m.zdr) Badge("Not kept")
                                flag(m)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                            }
                        },
                        onClick = { open = false; query = ""; onPick(ModelRef(p.id, m.id)) },
                    )
                }
                if (all.size > shown.size && q.isEmpty()) Quiet("   ${all.size - shown.size} more: search to narrow.")
                if (q.isNotEmpty() && p.kind != ProviderKind.ThisDevice && all.none { it.id == q }) DropdownMenuItem(
                    text = { Text("Use \"$q\" on ${p.label}") },
                    onClick = { open = false; query = ""; onPick(ModelRef(p.id, q)) },
                )
                if (all.isEmpty() && q.isEmpty()) Quiet("   No models fetched. Type a model's id.")
            }
        }
    }
}

/**
 * [models] that fit [query], best first: each word of it fuzzily in the
 * model's id or name (its letters in order, gaps allowed), a word the id
 * or name starts with before one inside it before letters strewn through.
 * "cl son" finds anthropic/claude-sonnet-4, "gpt4o" openai/gpt-4o. A
 * plain substring filter missed both. All of them, as listed, for none.
 */
internal fun rankModels(query: String, models: List<ModelInfo>): List<ModelInfo> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return models
    return models.mapNotNull { m ->
        val id = m.id.lowercase()
        val name = m.name.lowercase()
        var score = 0
        for (w in words) score += io.nisfeb.talon.ui.fuzzyScore(w, id, name, id.substringAfterLast('/')) ?: return@mapNotNull null
        m to score
    }.sortedBy { it.second }.map { it.first }
}

@Composable
private fun Badge(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

/** A feature's model picker, and the warning when a row that reads messages sends them where they may be kept. */
@Composable
private fun FeatureModel(
    profile: AiProfile,
    f: AiFeature,
    providers: List<AiProvider>,
    reads: Boolean,
    flag: (ModelInfo) -> String? = { null },
    /** How an Armillary model is reached, where one is chosen: `lease` or `proxy`. */
    armillaryMode: String? = null,
    /** Back to the Armillary vendor's model, for a feature the owner picked by hand. */
    onFollow: (() -> Unit)? = null,
    onPick: (ModelRef?) -> Unit,
) {
    val vendor = profile.vendorModel(f)
    val follows = profile.followsVendor(f)
    ModelPicker(
        profile, if (follows && vendor != null) ModelRef(vendor.provider.id, vendor.model) else profile.pick(f),
        allowDefault = !follows, providers = providers, flag = flag, vendorPick = follows, defaultRef = profile.defaultFor(f), onPick = onPick,
    )
    if (follows) Quiet("Armillary's pick.")
    else if (vendor != null && onFollow != null) TextButton(onClick = onFollow) { Text("Use Armillary's pick, ${shortModelName(vendor.model)}") }
    // Said where the model is picked. It used to surface, for triage
    // only, on the device model's card, where "OpenRouter has no key"
    // read as no provider at all (sneagan, 2026-10-08). A row on with no
    // model at all said nothing, and its feature never appeared.
    val r = profile.resolve(f)
    if (r == null) Quiet("No model yet. Add a provider above.", error = true)
    else r.takeIf { it.provider.kind != ProviderKind.ThisDevice }?.problem()?.let { Quiet(it, error = true) }
    if (reads) readingWarning(profile, f, armillaryMode)?.let { Quiet(it, error = true) }
}

/**
 * Why this row's model may keep your messages, or null when it is
 * Private or ZDR. Armillary is two answers: under a lease the request
 * goes straight to the model provider, so a ZDR model is as safe there
 * as anywhere; through the proxy it passes the vendor's ship, which is
 * worth saying even of a ZDR model.
 */
internal fun readingWarning(profile: AiProfile, f: AiFeature, armillaryMode: String? = null): String? {
    val r = profile.resolve(f) ?: return null
    val notKept = r.provider.models.firstOrNull { it.id == r.model }?.zdr == true
    // Triage reads every call and status line, unasked; the others what you ask about.
    val what = if (f == AiFeature.OrreryTriage) "Every call and status line read here goes" else "Messages you ask about go"
    // Through the vendor's ship nothing is stored; the model decides the
    // rest. Under a lease the request goes straight to the model provider,
    // and is said as any other.
    if (r.provider.kind == ProviderKind.Armillary && armillaryMode == "proxy") {
        return "Messages pass through your vendor on the way to the model. " + if (notKept) "Nothing keeps them." else "The model may keep them."
    }
    if (r.private || notKept) return null
    return when (r.provider.kind) {
        ProviderKind.Anthropic, ProviderKind.OpenAi -> "$what to ${r.provider.label}. Whether they are kept depends on your account."
        else -> "$what to this model and may be stored."
    }
}

// ── Rows ───────────────────────────────────────────────────────────

@Composable
private fun FeatureRow(
    title: String,
    line: String,
    on: Boolean,
    spent: Double?,
    onSwitch: (Boolean) -> Unit,
    switchEnabled: Boolean = true,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Quiet(line)
                spent?.let { Quiet(money(kotlin.math.round(it * 1_000_000).toLong()) + " this month.") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Switch(checked = on, onCheckedChange = onSwitch, enabled = switchEnabled)
        }
        if (on) content()
    }
}

/**
 * What this device reads for Orrery: its calls and its contacts' status
 * lines, with triage's model. No switch of its own: Use Orrery turns it
 * on for every device, and each device makes its key by itself
 * ([settleOrreryGate]). This device's own model is here, the one place
 * it can be picked.
 */
@Composable
private fun ReadingRow(orrery: OrreryRepo, profile: AiProfile, here: Boolean, spent: Double?, onPick: (ModelRef?) -> Unit) {
    val scope = rememberCoroutineScope()
    val availability by orrery.availability.collectAsState()
    val fed by orrery.enabled.collectAsState()
    val lastMs by orrery.lastPushMs.collectAsState()
    val pushing by orrery.pushing.collectAsState()
    val yielding by orrery.yielding.collectAsState()
    val error by orrery.error.collectAsState()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Read calls and status lines on this device", style = MaterialTheme.typography.bodyLarge)
        Quiet("Notes facts from your calls and your contacts' status lines and sends them to Orrery. Your ship reads your chats, calendar and mail itself.")
        spent?.let { Quiet(money(kotlin.math.round(it * 1_000_000).toLong()) + " this month.") }
        if (!here) {
            Quiet(
                when (availability) {
                    OrreryAvailability.MISSING -> "Orrery is not on your ship. Install it from the Apps page."
                    OrreryAvailability.SIGNED_OUT -> "Signed out of your ship."
                    else -> "Checking your ship for Orrery."
                },
            )
            return@Column
        }
        FeatureModel(profile, AiFeature.OrreryTriage, profile.providers, reads = true, onPick = onPick)
        if (profile.pick(AiFeature.OrreryTriage) == null && profile.defaultModel != null && profile.defaultFor(AiFeature.OrreryTriage) != profile.defaultModel) {
            Quiet("Your default model is not private, so this device reads with its own. Pick a model here to read elsewhere.")
        }
        if (profile.resolve(AiFeature.OrreryTriage)?.provider?.kind == ProviderKind.ThisDevice) DeviceModelLine(orrery)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (fed) {
                Quiet(if (lastMs != null) "Last sent ${agoLabel(lastMs!!)}." else "Not sent yet.")
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !pushing, onClick = { scope.launch { orrery.push() } }) { Text(if (pushing) "Sending" else "Send now") }
            } else {
                // Made by itself when Orrery is on; this is for when it was not.
                Quiet(if (busy) "Setting this device up." else "This device is not set up for Orrery yet.")
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { scope.launch { busy = true; orrery.ensureEnabled(); busy = false } }) { Text("Try again") }
            }
        }
        // A phone leaves the reading to a computer that has been on it lately.
        if (isTouchPrimary && fed && yielding) {
            Quiet("A computer running Talon has been reading in the last two hours, so this phone leaves the reading to it. Facts still go up from here.")
        }
        if (io.nisfeb.talon.ui.isLocationSharingSupported) LocationRow()
        if (io.nisfeb.talon.ui.isHealthSharingSupported) HealthRow()
        error?.let { Quiet(it, error = true) }
        // An empty balance holds every message the pipe would read, so it
        // says how to fix that, as a chat's failed summary does.
        if (io.nisfeb.talon.ui.isArmillaryPurchaseSupported && io.nisfeb.talon.ai.isOutOfCredit(error)) {
            TextButton(onClick = { AiSettings.pendingTopUp.value = true }) { Text("Top up") }
        }
    }
}

/** Where the owner is, from this phone, when they move. See [io.nisfeb.talon.ui.LocationSharing]. */
@Composable
private fun LocationRow() {
    val sharing = io.nisfeb.talon.ui.rememberLocationSharing() ?: return
    val scope = rememberCoroutineScope()
    val on by sharing.on.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send where I am", style = MaterialTheme.typography.bodyMedium)
            Quiet("When this phone moves a few hundred metres, Orrery hears where you are: a place it knows, or the neighbourhood or town. Never the coordinates.")
            if (on && !sharing.allowed()) Quiet("Location access for Talon is off, or not all the time, so nothing is sent.", error = true)
            if (on && !sharing.names) Quiet("This phone cannot look place names up, so only places Orrery knows are sent.")
        }
        Switch(checked = on, onCheckedChange = { want ->
            note = null
            scope.launch {
                if (!want) sharing.stop()
                else if (!sharing.start()) note = "Talon needs location access all the time to hear a move with the app closed."
            }
        })
    }
    note?.let { Quiet(it, error = true) }
}

@Composable
private fun HealthRow() {
    val sharing = io.nisfeb.talon.ui.rememberHealthSharing() ?: return
    val scope = rememberCoroutineScope()
    val on by sharing.on.collectAsState()
    var note by remember { mutableStateOf<String?>(null) }
    var background by remember { mutableStateOf(true) }
    LaunchedEffect(on) { if (on) background = sharing.background() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send my health", style = MaterialTheme.typography.bodyMedium)
            Quiet("Each day's steps, workouts and sleep from Health Connect: totals and times, never heart rate or where. Your ship keeps them to itself.")
            if (on && !background) Quiet("Health Connect lets Talon read only while it is open, so today goes up when you open Talon.")
        }
        Switch(checked = on, onCheckedChange = { want ->
            note = null
            scope.launch {
                when {
                    !want -> sharing.stop()
                    !sharing.available() -> note = "Health Connect is not on this phone, or needs an update."
                    !sharing.start() -> note = "Talon needs to read steps, exercise and sleep in Health Connect."
                }
            }
        })
    }
    note?.let { Quiet(it, error = true) }
}

/**
 * Orrery on, and the ship's generator with no model or no key: nothing
 * writes the brief or proposes anything, and nothing said so. The AI set
 * up in Talon stays in Talon, so a first-time owner expected it to carry
 * over and it did not (a user's report, 2026-10-09). The one step that
 * gives the ship a model is offered here, first on the page, with the
 * model Orrery would use when the ship can reach it.
 */
@Composable
private fun GeneratorOffer(orrery: OrreryRepo, profile: AiProfile, onPick: (ModelRef?) -> Unit) {
    val gen by orrery.generatorSettings.collectAsState()
    var hidden by remember { mutableStateOf(false) }
    val g = gen ?: return
    if (hidden || (g.model != null && g.keySet)) return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    // Orrery's own pick when the ship can run it, else the default model.
    val own = profile.features[AiFeature.OrreryGenerator]?.model
    val ref = own?.takeIf { generatorProblem(generatorTarget(profile, it)) == null }
    val target = generatorTarget(profile, ref)
    val problem = generatorProblem(target)
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Orrery names a model of its own when none was given, so on a
            // current ship it is the key that is missing.
            Text(if (g.model == null) "Orrery needs an AI model on your ship" else "Orrery on your ship needs a key for its model", style = MaterialTheme.typography.titleSmall)
            Quiet("Until it has one, Orrery cannot write your brief or suggest anything. The AI set up in Talon stays in Talon.")
            if (target != null && problem == null) {
                val label = refLabel(profile, ModelRef(target.first.id, target.second))
                // What the tap lets happen, not only what it sends: the
                // ship's chat and mail readers read with this model and key.
                Quiet("With $label, your ship reads the chats and email you let it read, and writes your brief. Your ${target.first.label} key is copied to your ship." + if (ref == null) " It follows your default model from then on." else "")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = !busy, onClick = {
                        note = null
                        busy = true
                        scope.launch {
                            orrery.pointGenerator(profile, true, ref)
                                .onSuccess { onPick(ref) }
                                .onFailure { note = it.message ?: "The ship did not answer." }
                            busy = false
                        }
                    }) { Text("Use $label") }
                    TextButton(enabled = !busy, onClick = { hidden = true }) { Text("Not now") }
                }
            } else {
                Quiet(problem ?: "")
                TextButton(onClick = { hidden = true }) { Text("Not now") }
            }
            note?.let { Quiet(it, error = true) }
        }
    }
}

/**
 * The decision model, one switch: before the reading model sees a call or
 * a status line it screens it, keeps feelings off status lines, picks
 * whom it is about and flags what needs help soon ([Decider]), at the
 * thresholds [DecideSettings] keeps. Greyed, not hidden, when nothing
 * offers one. Off, or with none to be had, everything still runs: the
 * reading model reads it all, and the ship's own pass comes on the hour.
 */
@Composable
private fun DecisionRow(profile: AiProfile, on: Boolean, spent: Double?, onSwitch: (Boolean) -> Unit) {
    // Where the decisions call can go: an OpenRouter key, or the Armillary lease's.
    val offered = profile.jevProvider() != null || profile.vendorDecision() != null
    FeatureRow(
        "Decision model",
        "A small, fast model that screens each call and status line before the reading model sees it. " +
            "It skips the ones with nothing to note, keeps feelings off status lines, and flags what needs help soon. " +
            "It runs on OpenRouter with nothing kept, for cents a day. Off, the reading model reads everything itself.",
        // On is on, whoever offers it: a switch that reads off because
        // the provider went could only ever send "on", so it did nothing.
        on = on, spent = spent, switchEnabled = offered || on,
        onSwitch = onSwitch,
    ) {}
    if (!offered) {
        Quiet(
            if (on) "Nothing offers a decision model now, so nothing is screened. Turn it off, or add an OpenRouter key under AI."
            else "Needs an OpenRouter key under AI.",
            error = on,
        )
    }
}

/**
 * The rest of Orrery is the ship's, the same for every device, and set on
 * Orrery's own page there: its model, which chats and mail it reads, the
 * owner's preferences, where approved things go.
 */
@Composable
private fun MoreOrreryRow(orrery: OrreryRepo) {
    val uri = LocalUriHandler.current
    val page = orrery.settingsPage() ?: return
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(page) } }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Heading("More Orrery settings")
            Quiet("Orrery's model, which chats and mail your ship reads, your preferences and where approved things go are set on Orrery's own page on your ship.")
        }
        TextButton(onClick = { runCatching { uri.openUri(page) } }) { Text("Open") }
    }
}

/** Whether a list can be asked for without the owner: OpenRouter's is public, the rest need a key or an address. */
internal fun AiProvider.canFetchModels(): Boolean = when (kind) {
    ProviderKind.OpenRouter -> true
    ProviderKind.Anthropic, ProviderKind.OpenAi -> apiKey.isNotBlank()
    ProviderKind.OpenAiCompatible -> !baseUrl.isNullOrBlank()
    ProviderKind.Armillary, ProviderKind.ThisDevice -> false
}
