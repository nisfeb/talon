package io.nisfeb.talon.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.nisfeb.talon.ai.ModelInfo
import io.nisfeb.talon.ai.ModelRef
import io.nisfeb.talon.ai.ProfileInputs
import io.nisfeb.talon.ai.ProviderKind
import io.nisfeb.talon.ai.migrateProfile
import io.nisfeb.talon.ai.shipBase
import io.nisfeb.talon.armillary.Account
import io.nisfeb.talon.armillary.ArmillaryAvailability
import io.nisfeb.talon.armillary.ArmillaryRepo
import io.nisfeb.talon.armillary.Checkout
import io.nisfeb.talon.armillary.Inference
import io.nisfeb.talon.armillary.LedgerRow
import io.nisfeb.talon.armillary.Payment
import io.nisfeb.talon.armillary.Plan
import io.nisfeb.talon.armillary.money
import io.nisfeb.talon.orrery.DecideControl
import io.nisfeb.talon.orrery.DecideDay
import io.nisfeb.talon.orrery.DecideSettings
import io.nisfeb.talon.orrery.OrreryAvailability
import io.nisfeb.talon.orrery.OrreryRepo
import io.nisfeb.talon.orrery.RungStatus
import io.nisfeb.talon.orrery.generatorLine
import io.nisfeb.talon.ui.icons.TalonIcons
import io.nisfeb.talon.ui.isAssistantSupported
import io.nisfeb.talon.ui.isCallsSupported
import io.nisfeb.talon.ui.isLocalTriageSupported
import io.nisfeb.talon.ui.isTouchPrimary
import io.nisfeb.talon.urbit.isValidPatp
import io.nisfeb.talon.util.nowMs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Settings > AI as the owner thinks of it: the providers they have, one
 * default model, a row per feature with its switch and its model, and
 * one Jev switch. docs/superpowers/specs/2026-09-19-ai-settings-redesign.md
 * is the plan.
 *
 * Until the owner changes something here the profile shown is the one
 * the old settings make, with what only the ship and the orrery pipe
 * know (Feed Orrery, the generator, the decision model) folded in; the
 * first change saves it.
 */
@Composable
fun AiSettingsSection(aiSettings: AiSettingsRepository, orrery: OrreryRepo?, armillary: ArmillaryRepo? = null) {
    val scope = rememberCoroutineScope()
    val cfg by aiSettings.state.collectAsState()
    val catalog = remember { ModelCatalog() }
    // One collection each whether or not there is a ship, so nothing moves about.
    val noFlag = remember { MutableStateFlow(false) }
    val noDecide = remember { MutableStateFlow(DecideSettings()) }
    val noGen = remember { MutableStateFlow<io.nisfeb.talon.orrery.GeneratorSettings?>(null) }
    val noShip = remember { MutableStateFlow(OrreryAvailability.UNKNOWN) }
    val fed by (orrery?.enabled ?: noFlag).collectAsState()
    val decide by (orrery?.decide?.settings ?: noDecide).collectAsState()
    val gen by (orrery?.generatorSettings ?: noGen).collectAsState()
    val availability by (orrery?.availability ?: noShip).collectAsState()
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
    val started = remember(cfg, fed, decide.on, gen) { starting() }
    val profile = cfg.savedProfile ?: started
    // Always from the settings as they are now: a fetch lands after the screen has moved on.
    fun edit(change: (AiProfile) -> AiProfile) = aiSettings.setProfile(change(aiSettings.state.value.savedProfile ?: starting()))
    fun setFeature(f: AiFeature, change: (FeatureSetting) -> FeatureSetting) =
        edit { p -> p.copy(features = p.features + (f to change(p.features[f] ?: FeatureSetting()))) }
    val orreryHere = orrery != null && availability == OrreryAvailability.PRESENT
    val chat = profile.providers.filter { it.kind != ProviderKind.ThisDevice }

    // ── Providers ──────────────────────────────────────────────
    Heading("Providers")
    Quiet("Where your models come from. Talon fetches each one's models, and marks those with zero data retention (ZDR) and those that stay on your own machines (Private).")
    profile.providers.forEach { p ->
        ProviderCard(
            p = p,
            catalog = catalog,
            orrery = orrery,
            armillary = armillary,
            onSave = { next -> edit { it.copy(providers = it.providers.map { q -> if (q.id == next.id) next else q }) } },
            onRemove = if (p.kind == ProviderKind.ThisDevice) null else ({
                edit { it.without(p.id) }
            }),
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
        edit { it.copy(providers = it.providers + p) }
        if (kind == ProviderKind.Armillary && armillary != null) {
            scope.launch { armillary.ensureKey(io.nisfeb.talon.ui.platformLabel) }
        }
    }

    // ── Default model ──────────────────────────────────────────
    Spacer(Modifier.height(12.dp))
    Heading("Default model")
    Quiet("What every feature uses unless its row below says otherwise.")
    ModelPicker(profile, profile.defaultModel, allowDefault = false, providers = chat) { ref -> edit { it.copy(defaultModel = ref) } }
    Quiet(
        "Some features read your messages. That is best done by a model on this device, on a server of your own, or one with zero data retention. " +
            if (profile.providers.none { it.kind == ProviderKind.OpenAiCompatible }) "LM Studio or Ollama on a machine of yours is a private model: add it as a server of your own above." else "",
    )

    // ── Features ───────────────────────────────────────────────
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Heading("Features")
    FeatureRow(
        "Channel catch-up", "When you open a chat with unread messages, offer a summary. Reads messages.",
        on = profile.isOn(AiFeature.CatchUp), spent = spend[AiFeature.CatchUp.name],
        onSwitch = { on -> setFeature(AiFeature.CatchUp) { it.copy(on = on) } },
    ) {
        FeatureModel(profile, AiFeature.CatchUp, chat, reads = true, armillaryMode = armillaryMode?.mode) { ref -> setFeature(AiFeature.CatchUp) { it.copy(model = ref) } }
    }
    if (isAssistantSupported) FeatureRow(
        "Assistant", "Answers from your messages and does what you ask, confirming anything that changes data. Loops run on it. Reads messages when asked.",
        on = profile.isOn(AiFeature.Assistant), spent = spend[AiFeature.Assistant.name],
        onSwitch = { on -> setFeature(AiFeature.Assistant) { it.copy(on = on) } },
    ) {
        FeatureModel(profile, AiFeature.Assistant, chat, reads = true, flag = { if (it.tools == false) "no tool use" else null }, armillaryMode = armillaryMode?.mode) { ref ->
            setFeature(AiFeature.Assistant) { it.copy(model = ref) }
        }
    }
    if (orrery != null) TriageRow(orrery, profile, orreryHere, spend[AiFeature.OrreryTriage.name]) { ref -> setFeature(AiFeature.OrreryTriage) { it.copy(model = ref) } }
    if (orrery != null && orreryHere) GeneratorRow(orrery, profile) { ref -> setFeature(AiFeature.OrreryGenerator) { it.copy(model = ref) } }
    if (orreryHere) FeatureRow(
        "Orrery brief", "Mail at seven each morning: today, what waits on you, and suggestions. Your reply is read by the same model, which files what you say. Sent by an install that feeds orrery.",
        on = profile.isOn(AiFeature.OrreryBrief), spent = spend[AiFeature.OrreryBrief.name],
        onSwitch = { on -> setFeature(AiFeature.OrreryBrief) { it.copy(on = on) } },
    ) {
        FeatureModel(profile, AiFeature.OrreryBrief, chat, reads = false) { ref -> setFeature(AiFeature.OrreryBrief) { it.copy(model = ref) } }
    }
    if (isCallsSupported) {
        val speech = profile.providers.filter { it.kind == ProviderKind.OpenAi || it.kind == ProviderKind.OpenAiCompatible }
        FeatureRow(
            "Transcription", "Turns a recorded party line into text. Needs a speech model, such as OpenAI's whisper-1.",
            on = profile.isOn(AiFeature.Transcription), spent = null, switchEnabled = speech.isNotEmpty(),
            onSwitch = { on -> setFeature(AiFeature.Transcription) { it.copy(on = on) } },
        ) {
            if (speech.isEmpty()) Quiet("Add an OpenAI provider, or a server of your own with a speech model.")
            else ModelPicker(profile, profile.features[AiFeature.Transcription]?.model, allowDefault = false, providers = speech, only = { it.speech }) { ref ->
                setFeature(AiFeature.Transcription) { it.copy(model = ref) }
            }
        }
    }

    // ── Jev ────────────────────────────────────────────────────
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    // Until the owner flips it here, Jev is what this install had.
    JevRow(orrery, profile, profile.jev ?: decide.on, orreryHere, spend[AiSpend.JEV]) { on -> edit { it.copy(jev = on) } }
}

private fun AiProfile.without(id: String): AiProfile = copy(
    providers = providers.filterNot { it.id == id },
    defaultModel = defaultModel?.takeUnless { it.provider == id },
    features = features.mapValues { (_, f) -> if (f.model?.provider == id) f.copy(model = null) else f },
)


@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Quiet(text: String, error: Boolean = false) {
    if (text.isBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Dollars to the cent, for a month's spend. */
private fun money(usd: Double): String {
    if (usd < 0.005) return "under a cent"
    val cents = kotlin.math.round(usd * 100).toLong()
    return "$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

// ── Providers ──────────────────────────────────────────────────────

@Composable
private fun ProviderCard(
    p: AiProvider,
    catalog: ModelCatalog,
    orrery: OrreryRepo?,
    armillary: ArmillaryRepo?,
    onSave: (AiProvider) -> Unit,
    onRemove: (() -> Unit)?,
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
    // key with it.
    fun load(of: AiProvider) {
        onSave(of)
        scope.launch {
            busy = true
            runCatching { catalog.fetch(of) }
                .onSuccess { onSave(of.withCatalog(it)); note = null }
                .onFailure { note = (it.message ?: "No answer.") to true }
            busy = false
        }
    }

    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.label, style = MaterialTheme.typography.bodyLarge)
                    if (p.label != p.kind.label) Quiet(p.kind.label)
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                onRemove?.let { remove ->
                    TextButton(onClick = {
                        // A lease is the vendor's key on loan. Give it
                        // back before the row goes, or it sits out there
                        // held by a device that has forgotten it.
                        if (p.kind == ProviderKind.Armillary && armillary != null) {
                            scope.launch {
                                armillary.dropLease()
                                remove()
                            }
                        } else {
                            remove()
                        }
                    }) { Text("Remove") }
                }
            }
            if (p.kind == ProviderKind.ThisDevice) {
                DeviceModelLine(orrery)
                return@Column
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
                label = { Text(if (p.kind == ProviderKind.OpenAiCompatible) "API key (only if it wants one)" else "API key") },
                singleLine = true,
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { reveal = !reveal }) {
                        Icon(if (reveal) TalonIcons.VisibilityOff else TalonIcons.Visibility, contentDescription = if (reveal) "Hide key" else "Show key")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Quiet(providerSummary(p))
            note?.let { (text, bad) -> Quiet(text, error = bad) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dirty) OutlinedButton(enabled = !busy, onClick = { load(edited) }) { Text("Save") }
                TextButton(enabled = !busy, onClick = { load(edited) }) { Text(if (p.models.isEmpty()) "Fetch models" else "Refresh models") }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val c = catalog.check(edited)
                        note = (if (c.ok) "Answers in ${c.ms} ms." else c.detail) to !c.ok
                        busy = false
                    }
                }) { Text("Test") }
            }
        }
    }
}

private fun providerSummary(p: AiProvider): String {
    val retention = when (p.kind) {
        ProviderKind.Anthropic, ProviderKind.OpenAi -> " Retention is as your account's agreement says."
        ProviderKind.OpenAiCompatible -> if (p.isPrivate) " Private: on your own machine or network." else " Not on your own network, so treat it as a cloud service."
        ProviderKind.Armillary -> " Paid through your ship."
        else -> ""
    }
    // Armillary's models come from the ship, not from a fetch here, so
    // an empty list means the ship has not answered yet.
    if (p.models.isEmpty() && p.kind == ProviderKind.Armillary) return "No models yet." + retention
    if (p.models.isEmpty()) return "Models not fetched yet." + retention
    val zdr = p.models.count { it.zdr }
    return "${p.models.size} models" +
        (if (p.kind == ProviderKind.OpenRouter) ", $zdr with zero data retention" else "") +
        "." + (if (p.offersJev) " Jev is offered." else "") + retention
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
    Quiet(
        when {
            !isLocalTriageSupported -> "No model runs on this platform yet."
            orrery == null -> "The model this device runs, for reading messages."
            model == null -> "Looking."
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
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var buying by remember { mutableStateOf(false) }
    var subscribing by remember { mutableStateOf<Plan?>(null) }
    var confirmCancel by remember { mutableStateOf(false) }
    var changingVendor by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var vendorTyped by remember { mutableStateOf("") }
    val here = repo != null && where == ArmillaryAvailability.PRESENT
    // An error surface sent the person here to top up: open the sheet
    // for them, once, and forget the ask.
    LaunchedEffect(Unit) {
        if (AiSettings.pendingTopUp.value) {
            AiSettings.pendingTopUp.value = false
            buying = true
        }
    }

    /** Open a checkout and send the person to it. */
    fun buy(rail: String, plan: String?, amountMicro: Long?) {
        val r = repo ?: return
        note = null
        scope.launch {
            r.topUp(rail, plan, amountMicro)
                .onSuccess { url -> uri.openUri(url) }
                .onFailure { note = (it.message ?: "The vendor did not answer.") to true }
        }
    }

    Quiet(
        when (where) {
            ArmillaryAvailability.PRESENT -> "Answering on this ship."
            ArmillaryAvailability.MISSING -> "Not on this ship. Install it from the Grubbery shell on your ship."
            ArmillaryAvailability.SIGNED_OUT -> "Signed out of the ship."
            ArmillaryAvailability.UNKNOWN -> "Not asked yet whether this ship has Armillary."
        },
    )
    if (where == ArmillaryAvailability.MISSING) Quiet("Armillary is published by ~ricsul-bilwyt, the same as Orrery and the Calendar.")
    account?.let { a ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Quiet("Vendor " + a.vendor.ifBlank { "not set yet" })
            TextButton(enabled = here, onClick = { changingVendor = !changingVendor; vendorTyped = "" }) { Text("Change") }
        }
        if (changingVendor) {
            OutlinedTextField(
                value = vendorTyped, onValueChange = { vendorTyped = it },
                label = { Text("Another vendor") }, placeholder = { Text(ArmillaryRepo.DEFAULT_VENDOR) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Quiet("Any ship running Armillary sells inference, your own included.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val typed = vendorTyped.trim()
                TextButton(enabled = here && isValidPatp(typed) && typed != a.vendor, onClick = {
                    changingVendor = false
                    note = null
                    scope.launch {
                        repo?.setVendor(typed, io.nisfeb.talon.ui.platformLabel)
                            ?.onSuccess { note = "Buying from $typed now." to false }
                            ?.onFailure { note = (it.message ?: "The ship did not answer.") to true }
                    }
                }) { Text("Use this vendor") }
                TextButton(onClick = { changingVendor = false }) { Text("Keep it") }
            }
        }
        if (a.hasView) {
            Text(money(a.balanceMicro) + " on your account.", style = MaterialTheme.typography.bodyMedium)
            balanceWarning(a)?.let { Quiet(it, error = true) }
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
    Quiet(armillaryModeLine(inference?.mode, account))
    Quiet(providerSummary(p))
    note?.let { (text, bad) -> Quiet(text, error = bad) }

    val subscription = plans.firstOrNull { it.kind == "subscription" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(enabled = repo != null && where == ArmillaryAvailability.PRESENT, onClick = { buying = true }) { Text("Top up") }
        if (subscription != null && account?.subscriptionActive != true) {
            TextButton(enabled = here, onClick = { subscribing = subscription; buying = true }) { Text(subscribeLabel(subscription)) }
        }
        if (account?.subscriptionActive == true) {
            TextButton(onClick = { confirmCancel = true }) { Text("Cancel subscription") }
        }
        if (refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else TextButton(enabled = repo != null, onClick = {
            note = null
            scope.launch { repo?.refresh(fresh = true)?.onFailure { note = (it.message ?: "The ship did not answer.") to true } }
        }) { Text("Refresh") }
        if (account?.hasView == true) TextButton(onClick = { history = !history }) { Text("History") }
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

    if (buying) TopUpSheet(
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
    if (waited) Quiet("Your ship is introducing itself to the vendor. Give it a moment and Refresh.")
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
    else -> "No plan: you pay as you go."
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
 */
internal fun balanceWarning(a: Account): String? = when {
    a.balanceMicro <= 0 -> "Empty: requests fail until you top up."
    a.balanceMicro < LOW_BALANCE_MICRO -> "Almost out: top up before your next request fails."
    else -> null
}

/** How Talon reaches the model, in the owner's terms. */
internal fun armillaryModeLine(mode: String?, account: Account?): String = when {
    account?.leaseDisabled == true -> "Balance is empty: requests go through the vendor's ship until you top up."
    mode == "lease" -> "Talon talks to the model provider directly with a key your ship holds."
    mode == "proxy" -> "Requests go through the vendor's ship."
    else -> "Your ship has not said yet how it reaches the model."
}

@Composable
private fun AddProvider(hasArmillary: Boolean, onAdd: (ProviderKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }) { Text("Add a provider") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val kinds = buildList {
                add(ProviderKind.OpenRouter)
                add(ProviderKind.Anthropic)
                add(ProviderKind.OpenAi)
                add(ProviderKind.OpenAiCompatible)
                // One at most: a device buys from one ship, its own.
                if (!hasArmillary) add(ProviderKind.Armillary)
            }
            kinds.forEach { k ->
                DropdownMenuItem(text = { Text(k.label) }, onClick = { open = false; onAdd(k) })
            }
        }
    }
}

// ── Model pickers ──────────────────────────────────────────────────

private const val DEVICE_MODEL = "This device's own model"

private fun AiProvider.offered(): List<ModelInfo> =
    if (kind == ProviderKind.ThisDevice) listOf(ModelInfo("", DEVICE_MODEL)) else models

private fun refLabel(profile: AiProfile, ref: ModelRef?): String {
    val r = ref ?: return "None chosen"
    val p = profile.provider(r.provider) ?: return "${r.model}, on a provider no longer here"
    val m = p.offered().firstOrNull { it.id == r.model }
    val name = m?.name ?: r.model.ifBlank { "its default" }
    val badge = when {
        p.isPrivate -> " · Private"
        m?.zdr == true -> " · ZDR"
        else -> ""
    }
    return "$name, ${p.label}$badge"
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
    onPick: (ModelRef?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (selected == null && allowDefault) "Default: " + refLabel(profile, profile.defaultModel) else refLabel(profile, selected),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(TalonIcons.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false; query = "" }, modifier = Modifier.heightIn(max = 460.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search, or type a model's id") }, singleLine = true,
                modifier = Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
            )
            if (allowDefault) DropdownMenuItem(
                text = { Text("Default: " + refLabel(profile, profile.defaultModel)) },
                onClick = { open = false; query = ""; onPick(null) },
            )
            val q = query.trim()
            providers.forEach { p ->
                val all = p.offered().filter(only)
                val shown = all.filter { q.isEmpty() || it.id.contains(q, true) || it.name.contains(q, true) }.take(80)
                Text(p.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                shown.forEach { m ->
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (p.isPrivate) Badge("Private")
                                if (m.zdr) Badge("ZDR")
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
    onPick: (ModelRef?) -> Unit,
) {
    ModelPicker(profile, profile.features[f]?.model, allowDefault = true, providers = providers, flag = flag, onPick = onPick)
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
    val zdr = r.provider.models.firstOrNull { it.id == r.model }?.zdr == true
    if (r.provider.kind == ProviderKind.Armillary) {
        if (armillaryMode == "proxy") {
            return "Your messages go through the vendor's ship to the model. A ZDR model does not keep them, the ship does not store them."
        }
        if (armillaryMode == "lease" && zdr) return null
    }
    if (r.private || zdr) return null
    return when (r.provider.kind) {
        ProviderKind.Anthropic, ProviderKind.OpenAi -> "Your messages go to ${r.provider.label}, kept as your account's agreement says. A Private or ZDR model does not keep them."
        else -> "Your messages go to a model that is neither Private nor ZDR, and may be kept."
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
                spent?.let { Quiet(money(it) + " this month.") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Switch(checked = on, onCheckedChange = onSwitch, enabled = switchEnabled)
        }
        if (on) content()
    }
}

/**
 * Triage: its switch is Feed Orrery on this install, which reads your
 * messages with triage's model and sends orrery the facts.
 */
@Composable
private fun TriageRow(orrery: OrreryRepo, profile: AiProfile, here: Boolean, spent: Double?, onPick: (ModelRef?) -> Unit) {
    val scope = rememberCoroutineScope()
    val availability by orrery.availability.collectAsState()
    val on by orrery.enabled.collectAsState()
    val lastMs by orrery.lastPushMs.collectAsState()
    val pushing by orrery.pushing.collectAsState()
    val error by orrery.error.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    if (!here) {
        FeatureRow("Orrery triage", when (availability) {
            OrreryAvailability.MISSING -> "Orrery is not on this ship. Install it from the Grubbery shell on your ship; the Apps page shows its state."
            OrreryAvailability.SIGNED_OUT -> "Signed out of the ship."
            else -> "Not asked yet whether this ship has Orrery."
        }, on = false, spent = null, onSwitch = {}, switchEnabled = false)
        return
    }
    FeatureRow(
        "Orrery triage",
        "Reads your messages on this install and sends orrery the facts: who wrote, about what, your contacts and calendar, under a key made for this install.",
        on = on, spent = spent, busy = busy,
        onSwitch = { want ->
            note = null
            scope.launch {
                busy = true
                (if (want) orrery.enable() else orrery.disable()).onFailure { note = it.message ?: "Orrery did not answer." }
                busy = false
            }
        },
    ) {
        FeatureModel(profile, AiFeature.OrreryTriage, profile.providers, reads = true, onPick = onPick)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Quiet(if (lastMs != null) "Pushed ${agoLabel(lastMs!!)}." else "Not pushed yet.")
            Spacer(Modifier.weight(1f))
            TextButton(enabled = !pushing, onClick = { scope.launch { orrery.push() } }) { Text(if (pushing) "Pushing" else "Push now") }
        }
        if (isTouchPrimary) orrery.standDown?.let { sd ->
            val standing by sd.on.collectAsState()
            val yielding by orrery.yielding.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Leave reading to my computer", style = MaterialTheme.typography.bodyMedium)
                    Quiet(
                        if (standing && yielding) "A computer running Talon has been on the job in the last two hours, so this phone is leaving the reading to it. Facts still go up from here."
                        else "When a computer running Talon has been on the job in the last two hours, this phone leaves the reading to it. Facts still go up from here.",
                    )
                }
                Switch(checked = standing, onCheckedChange = { sd.set(it) })
            }
        }
        if (io.nisfeb.talon.ui.isLocationSharingSupported) LocationRow()
    }
    (note ?: error)?.let { Quiet(it, error = true) }
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
            Quiet("When this phone moves a few hundred metres, orrery hears where you are: a place it knows, or the neighbourhood or town. Never the coordinates.")
            if (on && !sharing.allowed()) Quiet("Location access for Talon is off, or not all the time, so nothing is sent.", error = true)
            if (on && !sharing.names) Quiet("This phone cannot look place names up, so only places orrery knows are sent.")
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

/**
 * The generator runs on the ship. Its switch and model are the ship's
 * settings, written with the owner's session; choosing a model sends
 * that provider's key to the ship.
 */
@Composable
private fun GeneratorRow(orrery: OrreryRepo, profile: AiProfile, onPick: (ModelRef?) -> Unit) {
    val scope = rememberCoroutineScope()
    val gen by orrery.generatorSettings.collectAsState()
    val last by orrery.generator.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val g = gen ?: return
    val reachable = profile.providers.filter { it.shipBase() != null }

    /** Point the ship at [ref], or the default model; a provider the ship cannot reach is said so. */
    fun point(enabled: Boolean, ref: ModelRef?) = scope.launch {
        busy = true
        note = null
        val r = (ref ?: profile.defaultModel)?.let { rr -> profile.provider(rr.provider)?.let { it to rr.model } }
        val base = r?.first?.shipBase()
        val result = when {
            r == null -> orrery.setGenerator(enabled)
            base == null -> Result.failure(IllegalStateException("The ship cannot reach ${r.first.label}. Pick a model on OpenRouter, OpenAI, or a server on your network."))
            else -> orrery.setGenerator(enabled, base, r.second.ifBlank { null }, r.first.apiKey)
        }
        result.onSuccess { onPick(ref) }.onFailure { note = it.message ?: "The ship did not answer." }
        busy = false
    }

    FeatureRow(
        "Orrery analysis", "The generator, on your ship: reads orrery's state and proposes actions for you to approve. Choosing a model sends that provider's key to the ship, which keeps it and never gives it back.",
        on = g.enabled, spent = null, busy = busy,
        onSwitch = { want ->
            // Turned on with nothing to run, it is pointed at this row's model first.
            if (want && (g.model == null || !g.keySet)) point(true, profile.features[AiFeature.OrreryGenerator]?.model)
            else scope.launch { busy = true; orrery.setGenerator(want).onFailure { note = it.message }; busy = false }
        },
    ) {
        ModelPicker(
            profile, profile.features[AiFeature.OrreryGenerator]?.model, allowDefault = true, providers = reachable,
            flag = { m -> m.contextLength?.takeIf { it < 100_000 }?.let { "short context" } },
        ) { ref -> point(g.enabled, ref) }
        g.model?.let { Quiet("On the ship: $it" + (g.url?.let { u -> " at $u" } ?: "") + if (g.keySet) "." else ", with no key.") }
        last?.let { Quiet("Last run: " + generatorLine(it, nowMs(), kotlinx.datetime.TimeZone.currentSystemDefault())) }
    }
    note?.let { Quiet(it, error = true) }
}

/**
 * Jev, one switch: the gate, the status check and the body picks
 * together, at the thresholds under Advanced. Greyed, not hidden, when
 * no provider offers it, so the owner knows what they are missing.
 */
@Composable
private fun JevRow(orrery: OrreryRepo?, profile: AiProfile, on: Boolean, orreryHere: Boolean, spent: Double?, onSwitch: (Boolean) -> Unit) {
    val jev = profile.jevProvider()
    var advanced by remember { mutableStateOf(false) }
    FeatureRow(
        "Jev gating",
        "TypeSafe's Jev reads each message before triage does, through OpenRouter with zero data retention, for cents a day. " +
            "It skips messages that say nothing, drops statuses that are feelings rather than circumstances, and picks the people and things triage sees. " +
            "Triage reads less and reads better.",
        // On is on, whoever offers it: a switch that reads off because
        // the provider went could only ever send "on", so it did nothing.
        on = on, spent = spent, switchEnabled = jev != null || on,
        onSwitch = onSwitch,
    ) {}
    if (jev == null) {
        Quiet(
            if (on) "No provider offers Jev any more, so nothing is being gated. Turn it off, or add an OpenRouter provider with a key."
            else "Needs an OpenRouter provider with a key: OpenRouter is where Jev is offered.",
            error = on,
        )
    }
    val dc = orrery?.decide
    if (orrery != null && dc != null && orreryHere && on) {
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced" else "Advanced") }
        if (advanced) JevAdvanced(orrery, dc)
    }
}

/** The thresholds and the checks that choose them, and today's tally. */
@Composable
private fun JevAdvanced(orrery: OrreryRepo, dc: DecideControl) {
    val d by dc.settings.collectAsState()
    val run by orrery.gateCheck.collectAsState()
    val checking = run != null && run?.result == null
    var threshold by remember(d.threshold) { mutableStateOf(d.threshold.toString()) }
    var keep by remember(d.keep) { mutableStateOf(d.keep.toString()) }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val today by orrery.decideToday.collectAsState()
    LaunchedEffect(orrery) { orrery.loadDecideToday() }
    today?.let { (day, tally) ->
        if (tally == DecideDay()) Quiet("Nothing read with it yet today.")
        else tally.lines(day).forEach { Quiet(it) }
    }
    Quiet("Below the gate's threshold triage is not asked; a gate that cannot answer lets the message through. Run the check, then pick from 0.2 to 0.4.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = threshold,
            onValueChange = { t -> threshold = t; t.toDoubleOrNull()?.takeIf { it in 0.05..0.95 }?.let { dc.set(d.copy(threshold = it)) } },
            label = { Text("Gate threshold") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        if (checking) TextButton(onClick = { orrery.stopGateCheck() }) { Text("Stop") }
        else TextButton(onClick = { orrery.startGateCheck() }) { Text("Check the gate") }
    }
    Quiet("Triage sees the bodies Jev scores at or above this, with the sender and you. A pick that fails shows triage the usual list.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = keep,
            onValueChange = { t -> keep = t; t.toDoubleOrNull()?.takeIf { it in 0.05..0.95 }?.let { dc.set(d.copy(keep = it)) } },
            label = { Text("Keep bodies at or above") }, singleLine = true, modifier = Modifier.weight(1f),
        )
        if (!checking) TextButton(onClick = { orrery.startGateCheck(limit = 50, picks = true) }) { Text("Check body picks") }
    }
    // The route is alpha and may move: it and the model are settings, not code.
    OutlinedTextField(value = d.url, onValueChange = { dc.set(d.copy(url = it.trim())) }, label = { Text("Decisions route") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = d.model, onValueChange = { dc.set(d.copy(model = it.trim())) }, label = { Text("Jev model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    run?.takeIf { it.result == null }?.let { r ->
        Quiet(if (r.total == 0) "Choosing the messages to check." else "Checked ${r.done} of ${r.total}.")
        if (r.total > 0) LinearProgressIndicator(progress = { r.done.toFloat() / r.total }, modifier = Modifier.fillMaxWidth())
    }
    run?.result?.fold(
        onSuccess = { c ->
            Text(
                "${c.total} messages already read. " +
                    c.readAt.joinToString("; ") { (t, n) -> "at $t, $n read and ${c.total - n} skipped" } +
                    ". The check cost ${"$"}${(kotlin.math.round(c.costUsd * 10_000) / 10_000)}" +
                    (if (c.failed > 0) "; ${c.failed} could not be asked and count as read." else ".") +
                    (if (c.keptAt.isEmpty()) "" else " Bodies triage would see: " + c.keptAt.joinToString("; ") { (t, n) -> "at $t, ${(kotlin.math.round(n * 10) / 10)} on average" } + "."),
                style = MaterialTheme.typography.labelSmall,
            )
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                c.lines.forEach { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = quiet) }
            }
        },
        onFailure = { Quiet(it.message ?: "The check failed.", error = true) },
    )
}
