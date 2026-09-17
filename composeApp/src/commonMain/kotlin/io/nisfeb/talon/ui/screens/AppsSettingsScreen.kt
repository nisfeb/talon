package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.calendar.CalendarRepo
import io.nisfeb.talon.mail.MailRepo
import io.nisfeb.talon.ui.AppRow
import io.nisfeb.talon.ui.AppState
import io.nisfeb.talon.ui.calendarRow
import io.nisfeb.talon.ui.groupsRow
import io.nisfeb.talon.ui.latticeRow
import io.nisfeb.talon.ui.mailRow
import io.nisfeb.talon.ui.permitsUrl
import kotlinx.coroutines.launch

/**
 * What this ship's Grubbery apps are doing: whether each answers, what
 * it last said when it did not, and the install where installing is the
 * fix.
 *
 * Read-only about permissions. Each app asks the ship for what it needs
 * and the ship's own permits page is where those are answered, so this
 * links there rather than pretending to grant anything.
 */
@Composable
fun AppsSettingsScreen(
    mail: MailRepo?,
    calendar: CalendarRepo?,
    /** The pipe into orrery on the ship; null where no ship is known. */
    orrery: io.nisfeb.talon.orrery.OrreryRepo? = null,
    /** Probes whether Grubbery is on the ship; null where no ship is known. */
    latticeInstalled: (suspend () -> Boolean)?,
    /** Probes whether %groups is on the ship, which chat itself runs on. */
    groupsInstalled: (suspend () -> Boolean)? = null,
    /** Installs %groups from its own publisher. Null hides the offer. */
    onInstallGroups: (suspend () -> Result<Unit>)? = null,
    /** This ship's base URL, for the permits page. Null when signed out. */
    shipUrl: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val installGrubbery = io.nisfeb.talon.mail.LocalGrubberyInstall.current

    // One remembered stand-in for a null repo's error flow, rather
    // than a fresh MutableStateFlow on every recomposition.
    val noError = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val mailAvailability = mail?.availability?.collectAsState()?.value
    val mailError by (mail?.error ?: noError).collectAsState()
    val calendarAvailability = calendar?.availability?.collectAsState()?.value
    val calendarError by (calendar?.error ?: noError).collectAsState()
    val orreryAvailability = orrery?.availability?.collectAsState()?.value
    val orreryError by (orrery?.error ?: noError).collectAsState()
    val orreryOn = orrery?.enabled?.collectAsState()?.value ?: false
    val orreryLastMs = orrery?.lastPushMs?.collectAsState()?.value
    var orreryBusy by remember { mutableStateOf(false) }

    var lattice by remember { mutableStateOf<Boolean?>(null) }
    var groups by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    suspend fun probeLattice() {
        lattice = latticeInstalled?.let { runCatching { it() }.getOrNull() }
    }
    suspend fun probeGroups() {
        groups = groupsInstalled?.let { runCatching { it() }.getOrNull() }
    }
    LaunchedEffect(latticeInstalled, groupsInstalled) {
        probeLattice()
        probeGroups()
    }

    val rows = buildList {
        if (mailAvailability != null) add(mailRow(mailAvailability, mailError))
        if (calendarAvailability != null) add(calendarRow(calendarAvailability, calendarError, lattice))
        add(latticeRow(lattice))
        add(groupsRow(groups))
        if (orreryAvailability != null) add(io.nisfeb.talon.ui.orreryRow(orreryAvailability, orreryError))
    }

    /**
     * Install, then re-ask every app whether it is there now. One action:
     * mail, the calendar and lattice all arrive in the Grubbery desk, so
     * there is nothing else left to install.
     */
    fun install(row: AppRow) {
        val action = when (row.install) {
            io.nisfeb.talon.ui.AppInstall.GROUPS -> onInstallGroups
            io.nisfeb.talon.ui.AppInstall.GRUBBERY -> installGrubbery
            null -> null
        } ?: return
        busy = row.name
        note = null
        scope.launch {
            action().fold(
                onSuccess = {
                    note = if (row.install == io.nisfeb.talon.ui.AppInstall.GROUPS) "Installed Groups."
                    else "Installed Grubbery. The apps arrive with it."
                },
                onFailure = { note = it.message ?: "The install did not finish." },
            )
            probeLattice()
            probeGroups()
            runCatching { mail?.refresh() }
            runCatching { calendar?.refreshAll() }
            runCatching { orrery?.probe() }
            busy = null
        }
    }

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            io.nisfeb.talon.ui.NavIcon(onBack = onBack)
            Text(
                "Apps",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            TextButton(
                enabled = busy == null,
                onClick = {
                    note = null
                    scope.launch {
                        busy = "all"
                        probeLattice()
                        probeGroups()
                        runCatching { mail?.refresh() }
                        runCatching { calendar?.refreshAll() }
            runCatching { orrery?.probe() }
                        busy = null
                    }
                },
            ) { Text("Check again") }
        }
        HorizontalDivider()

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Mail, the calendar and Lattice run on your ship, inside Grubbery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stateWord(row.state),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (row.state == AppState.WORKING) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // The ship's own words, kept even when the app works:
                        // a refusal it reported is the thing somebody came here for.
                        row.error?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (busy == row.name) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (row.canInstall && (if (row.install == io.nisfeb.talon.ui.AppInstall.GROUPS) onInstallGroups != null else installGrubbery != null)) {
                        TextButton(enabled = busy == null, onClick = { install(row) }) { Text("Install") }
                    }
                }
                HorizontalDivider()
            }

            note?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            // The pipe into orrery. Off until the person says so: it is
            // their world model, and what feeds it is theirs to decide.
            if (orrery != null && orreryAvailability == io.nisfeb.talon.orrery.OrreryAvailability.PRESENT) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Feed Orrery", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Your contacts, who wrote to you and on which day, and your calendar go to Orrery on your ship as facts, under a key made for this install. Nothing anyone said leaves this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (orreryOn) {
                            val pushing by orrery.pushing.collectAsState()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (orreryLastMs != null) "Pushed ${agoLabel(orreryLastMs)}." else "Not pushed yet.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                // For whoever is checking a phone: a pass on demand,
                                // and what it refused shown in the row's error line.
                                TextButton(enabled = !pushing, onClick = { scope.launch { orrery.push() } }) {
                                    Text(if (pushing) "Pushing" else "Push now")
                                }
                            }
                        }
                        // Which model reads messages here, and why not a better one.
                        val model = orrery.model.collectAsState().value
                        val download = orrery.download.collectAsState().value
                        val modelLine = when {
                            !io.nisfeb.talon.ui.isLocalTriageSupported -> "No local model on this platform yet. The rules alone read messages."
                            model == null -> null
                            model.second == io.nisfeb.talon.orrery.RungStatus.Ready -> "Reads with ${model.first}."
                            model.second is io.nisfeb.talon.orrery.RungStatus.NeedsDownload -> "${model.first} can read messages here after a download of about ${(model.second as io.nisfeb.talon.orrery.RungStatus.NeedsDownload).bytes / 1_000_000} MB."
                            else -> (model.second as io.nisfeb.talon.orrery.RungStatus.Unavailable).reason
                        }
                        modelLine?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (model?.second is io.nisfeb.talon.orrery.RungStatus.NeedsDownload) {
                            if (download != null) {
                                androidx.compose.material3.LinearProgressIndicator(progress = { download }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            } else {
                                TextButton(onClick = { scope.launch { orrery.prepareModel().onFailure { note = it.message ?: "The download did not finish." } } }) { Text("Download the model") }
                            }
                        }
                        // The cloud opt-in: theirs to choose, with the cost said.
                        orrery.cloud?.let { cloud ->
                            val cloudOn by cloud.on.collectAsState()
                            val hasKey = cloud.config().apiKey.isNotBlank()
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Read with your cloud AI key instead", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (hasKey) "Every message the triage reads leaves this device for ${cloud.config().provider.label}. A larger model reads better; that is the trade."
                                        else "Needs an API key under AI. Off until then.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                androidx.compose.material3.Switch(
                                    checked = cloudOn && hasKey,
                                    enabled = hasKey,
                                    onCheckedChange = { on -> cloud.set(on); scope.launch { orrery.refreshModel() } },
                                )
                            }
                        }
                    }
                    if (orreryBusy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        androidx.compose.material3.Switch(
                            checked = orreryOn,
                            onCheckedChange = { on ->
                                note = null
                                scope.launch {
                                    orreryBusy = true
                                    (if (on) orrery.enable() else orrery.disable())
                                        .onFailure { note = it.message ?: "Orrery did not answer." }
                                    orreryBusy = false
                                }
                            },
                        )
                    }
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(16.dp))
            Text("Permissions", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Each app asks your ship for what it needs. Your ship's permits page is where those are answered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shipUrl != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { uriHandler.openUri(permitsUrl(shipUrl)) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Open permits on your ship", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            } else {
                Text(
                    "Sign in to a ship to reach its permits page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun stateWord(state: AppState): String = when (state) {
    AppState.WORKING -> "working"
    AppState.MISSING -> "not installed"
    AppState.OUTDATED -> "out of date"
    AppState.SIGNED_OUT -> "signed out"
    AppState.UNKNOWN -> "unknown"
}
