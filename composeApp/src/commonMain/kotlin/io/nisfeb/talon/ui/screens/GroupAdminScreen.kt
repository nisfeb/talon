package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.nisfeb.talon.ui.isCallsSupported
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Switch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.AdminGroup
import io.nisfeb.talon.urbit.AdminMember
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.util.decodeImageDimensions
import io.nisfeb.talon.util.rememberImagePicker
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupAdminScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    flag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drives the party-line switches. Null hides that section — a
     *  platform without calls has nothing to configure. */
    callController: io.nisfeb.talon.call.CallController? = null,
    /** Our own @p, to decide whether we may change the line. */
    me: String = "",
) {
    val scope = rememberCoroutineScope()

    var group by remember { mutableStateOf<AdminGroup?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var memberActionTarget by remember {
        mutableStateOf<AdminMember?>(null)
    }
    var pendingKick by remember { mutableStateOf<String?>(null) }
    var pendingBan by remember { mutableStateOf<String?>(null) }
    var newChannelOpen by remember { mutableStateOf(false) }
    var creatingChannel by remember { mutableStateOf(false) }

    // Contact map for nicknames on member rows. Updates live as
    // %contacts events come in.
    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        )
    }.collectAsState(initial = ContactMap.EMPTY)

    suspend fun refresh() {
        runCatching { repo.fetchGroupAdmin(flag) }
            .onSuccess { group = it; error = null }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    LaunchedEffect(flag) {
        loading = true
        refresh()
        loading = false
    }

    Column(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                group?.title ?: flag,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = 4.dp).weight(1f),
                maxLines = 1,
            )
            IconButton(
                enabled = !creatingChannel,
                onClick = { newChannelOpen = true },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New channel")
            }
        }
        HorizontalDivider()
        when {
            loading -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            error != null -> Text(
                "Couldn't load: $error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )

            group != null -> AdminBody(
                repo = repo,
                    callController = callController,
                    me = me,
                flag = flag,
                group = group!!,
                contactMap = contactMap,
                onSaveMeta = { title, desc, img, cover ->
                    scope.launch {
                        runCatching {
                            repo.updateGroupMeta(flag, title, desc, img, cover)
                        }.onSuccess { refresh() }
                            .onFailure { error = it.message ?: it::class.simpleName }
                    }
                },
                onInvite = { ship ->
                    scope.launch {
                        runCatching { repo.inviteToGroup(flag, ship) }
                            .onSuccess { scope.launch { kotlinx.coroutines.delay(500); refresh() } }
                            .onFailure { error = it.message ?: it::class.simpleName }
                    }
                },
                onRevokeInvite = { ship ->
                    val snapshot = group ?: return@AdminBody
                    // Optimistic: drop the ship from the local group state
                    // so the row disappears immediately. The real scry
                    // reconciles a moment later.
                    group = snapshot.copy(
                        invitedTokenByShip = snapshot.invitedTokenByShip - ship,
                        directInvitedShips = snapshot.directInvitedShips - ship,
                    )
                    scope.launch {
                        val action = runCatching {
                            val token = snapshot.invitedTokenByShip[ship]
                            if (token != null) {
                                repo.revokeTokenInvite(flag, token)
                            } else {
                                repo.revokeDirectInvite(flag, ship)
                            }
                        }
                        action.onSuccess {
                            // Give the agent a beat to commit before re-scrying.
                            kotlinx.coroutines.delay(500)
                            refresh()
                        }.onFailure {
                            error = it.message ?: it::class.simpleName
                            // Rollback by re-fetching.
                            refresh()
                        }
                    }
                },
                onApproveRequest = { ship ->
                    val snapshot = group ?: return@AdminBody
                    group = snapshot.copy(pendingShips = snapshot.pendingShips - ship)
                    scope.launch {
                        runCatching { repo.approveRequest(flag, ship) }
                            .onSuccess {
                                kotlinx.coroutines.delay(500)
                                refresh()
                            }
                            .onFailure {
                                error = it.message ?: it::class.simpleName
                                refresh()
                            }
                    }
                },
                onDenyRequest = { ship ->
                    val snapshot = group ?: return@AdminBody
                    group = snapshot.copy(pendingShips = snapshot.pendingShips - ship)
                    scope.launch {
                        runCatching { repo.denyRequest(flag, ship) }
                            .onSuccess {
                                kotlinx.coroutines.delay(500)
                                refresh()
                            }
                            .onFailure {
                                error = it.message ?: it::class.simpleName
                                refresh()
                            }
                    }
                },
                onUnban = { ship ->
                    val snapshot = group ?: return@AdminBody
                    group = snapshot.copy(bannedShips = snapshot.bannedShips - ship)
                    scope.launch {
                        runCatching { repo.unbanFromGroup(flag, ship) }
                            .onSuccess {
                                kotlinx.coroutines.delay(500)
                                refresh()
                            }
                            .onFailure {
                                error = it.message ?: it::class.simpleName
                                refresh()
                            }
                    }
                },
                onMemberLongPress = { memberActionTarget = it },
                onReportError = { e -> error = e },
            )
        }
    }

    memberActionTarget?.let { m ->
        val hostShip = flag.substringBefore('/')
        val isHostRow = m.ship == hostShip
        val hasAdminRole = "admin" in m.sects
        AlertDialog(
            onDismissRequest = { memberActionTarget = null },
            title = { Text(m.ship) },
            text = {
                Text(
                    "Roles: ${if (m.sects.isEmpty()) "(none)" else m.sects.joinToString(", ")}",
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!isHostRow) {
                        TextButton(onClick = {
                            val target = m
                            memberActionTarget = null
                            scope.launch {
                                runCatching {
                                    repo.setMemberRole(flag, target.ship, "admin", add = !hasAdminRole)
                                }.onSuccess { refresh() }
                                    .onFailure { error = it.message ?: it::class.simpleName }
                            }
                        }) {
                            Text(if (hasAdminRole) "Revoke admin" else "Make admin")
                        }
                    }
                    TextButton(onClick = {
                        memberActionTarget = null
                        pendingKick = m.ship
                    }) { Text("Kick") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    memberActionTarget = null
                    pendingBan = m.ship
                }) { Text("Ban") }
            },
        )
    }

    pendingKick?.let { ship ->
        AlertDialog(
            onDismissRequest = { pendingKick = null },
            title = { Text("Kick $ship?") },
            text = { Text("They'll be removed from the group but can re-join.") },
            confirmButton = {
                TextButton(onClick = {
                    val s = ship
                    pendingKick = null
                    scope.launch {
                        runCatching { repo.kickFromGroup(flag, s) }
                            .onSuccess { refresh() }
                            .onFailure { error = it.message ?: it::class.simpleName }
                    }
                }) { Text("Kick") }
            },
            dismissButton = {
                TextButton(onClick = { pendingKick = null }) { Text("Cancel") }
            },
        )
    }

    pendingBan?.let { ship ->
        AlertDialog(
            onDismissRequest = { pendingBan = null },
            title = { Text("Ban $ship?") },
            text = {
                Text(
                    "They'll be prevented from joining or re-joining. " +
                        "You can unban later via the ship's cordon list.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = ship
                    pendingBan = null
                    scope.launch {
                        runCatching { repo.banFromGroup(flag, s) }
                            .onSuccess { refresh() }
                            .onFailure { error = it.message ?: it::class.simpleName }
                    }
                }) { Text("Ban") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBan = null }) { Text("Cancel") }
            },
        )
    }

    if (newChannelOpen) {
        NewChannelDialog(
            busy = creatingChannel,
            onDismiss = { if (!creatingChannel) newChannelOpen = false },
            onCreate = { kind, title, description ->
                creatingChannel = true
                error = null
                scope.launch {
                    runCatching {
                        repo.createChannel(flag, kind, title, description)
                    }.onSuccess {
                        creatingChannel = false
                        newChannelOpen = false
                    }.onFailure {
                        creatingChannel = false
                        error = it.message ?: it::class.simpleName
                    }
                }
            },
        )
    }
}

@Composable
private fun NewChannelDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (kind: String, title: String, description: String) -> Unit,
) {
    var kind by remember { mutableStateOf("chat") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New channel") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // No "diary"/Bulletin here: Tlon deprecated the type in
                    // webapp v12 (it was the old "Notebook"). Existing
                    // bulletins still render and stay readable/writable —
                    // we just don't mint new ones. "notes" is its
                    // replacement, served by the %notes agent.
                    listOf(
                        "chat" to "Chat",
                        "heap" to "Gallery",
                        "notes" to "Notebook",
                    ).forEach { (k, label) ->
                        val selected = k == kind
                        OutlinedButton(
                            onClick = { kind = k },
                            enabled = !busy,
                            colors = if (selected)
                                androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                )
                            else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                        ) { Text(label) }
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            "Creating…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.trim().isNotEmpty() && !busy,
                onClick = { onCreate(kind, title.trim(), description.trim()) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdminBody(
    repo: TlonChatRepo,
    flag: String,
    group: AdminGroup,
    callController: io.nisfeb.talon.call.CallController?,
    me: String,
    contactMap: ContactMap,
    onSaveMeta: (title: String, description: String, image: String, cover: String) -> Unit,
    onInvite: (ship: String) -> Unit,
    onRevokeInvite: (ship: String) -> Unit,
    onApproveRequest: (ship: String) -> Unit,
    onDenyRequest: (ship: String) -> Unit,
    onUnban: (ship: String) -> Unit,
    onMemberLongPress: (AdminMember) -> Unit,
    onReportError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    var title by remember(group.flag) { mutableStateOf(group.title.orEmpty()) }
    var description by remember(group.flag) { mutableStateOf(group.description.orEmpty()) }
    var image by remember(group.flag) { mutableStateOf(group.image.orEmpty()) }
    var cover by remember(group.flag) { mutableStateOf(group.cover.orEmpty()) }
    var inviteText by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }

    val pickImage = rememberImagePicker()

    fun launchPicker(slot: String) {
        scope.launch {
            val picked = runCatching { pickImage() }
                .onFailure { onReportError("couldn't read image: ${it.message ?: it::class.simpleName}") }
                .getOrNull() ?: return@launch
            uploading = true
            runCatching {
                // Bounds-only decode validates the bytes are a real
                // image without allocating the full bitmap. Null
                // means the picker handed us something we can't
                // decode — bail before uploading.
                if (decodeImageDimensions(picked.bytes) == null) {
                    error("not a valid image")
                }
                repo.uploadImage(picked.bytes, picked.mimeType, picked.displayName)
            }.onSuccess { url ->
                if (slot == "image") image = url else cover = url
            }.onFailure { e ->
                onReportError("upload failed: ${e.message ?: e::class.simpleName}")
            }
            uploading = false
        }
    }

    val metaDirty = title != group.title.orEmpty() ||
        description != group.description.orEmpty() ||
        image != group.image.orEmpty() ||
        cover != group.cover.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ───────── Metadata ─────────
        SectionHeader("Metadata")
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = image,
                onValueChange = { image = it },
                label = { Text("Image URL or #RRGGBB") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                enabled = !uploading,
                onClick = { launchPicker("image") },
            ) { Text("Upload") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = cover,
                onValueChange = { cover = it },
                label = { Text("Cover URL or #RRGGBB") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedButton(
                enabled = !uploading,
                onClick = { launchPicker("cover") },
            ) { Text("Upload") }
        }
        if (uploading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 4.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "Uploading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = { onSaveMeta(title, description, image, cover) },
            enabled = metaDirty && !uploading,
        ) { Text("Save metadata") }

        HorizontalDivider()

        // ───────── Invite ─────────
        SectionHeader("Invite")
        val privacyLabel = when (group.privacy) {
            "public" -> "Public — anyone can join. Invites still speed up discovery."
            "private" -> "Private — members must be invited or request access."
            "secret" -> "Secret — invite-only; the group isn't discoverable."
            null -> when (group.cordonKind) {
                "open" -> "Open group — anyone can join."
                else -> "Invite-only."
            }
            else -> "Privacy: ${group.privacy}"
        }
        Text(
            privacyLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inviteText,
                onValueChange = { inviteText = it },
                label = { Text("~ship-name") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                enabled = inviteText.trim().isNotEmpty(),
                onClick = {
                    onInvite(inviteText.trim())
                    inviteText = ""
                },
            ) { Text("Invite") }
        }

        HorizontalDivider()

        // ───────── Outstanding invites ─────────
        val allInvited = (group.invitedTokenByShip.keys + group.directInvitedShips).sorted()
        if (allInvited.isNotEmpty()) {
            SectionHeader("Invited · ${allInvited.size}")
            for (ship in allInvited) {
                ShipRow(
                    ship = ship,
                    contactMap = contactMap,
                    trailing = {
                        OutlinedButton(onClick = { onRevokeInvite(ship) }) {
                            Text("Revoke")
                        }
                    },
                )
            }
            HorizontalDivider()
        }

        // ───────── Join requests ─────────
        if (group.pendingShips.isNotEmpty()) {
            SectionHeader("Requests · ${group.pendingShips.size}")
            for (ship in group.pendingShips.sorted()) {
                ShipRow(
                    ship = ship,
                    contactMap = contactMap,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(onClick = { onApproveRequest(ship) }) { Text("Accept") }
                            OutlinedButton(onClick = { onDenyRequest(ship) }) { Text("Deny") }
                        }
                    },
                )
            }
            HorizontalDivider()
        }

        // ───────── Banned ─────────
        if (group.bannedShips.isNotEmpty()) {
            SectionHeader("Banned · ${group.bannedShips.size}")
            for (ship in group.bannedShips.sorted()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Avatar(
                        label = contactMap.nickname(ship) ?: ship,
                        url = contactMap.avatar(ship),
                        colorHex = contactMap.shipColor(ship),
                        size = 32.dp,
                    )
                    Column(Modifier.weight(1f)) {
                        contactMap.nickname(ship)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Text(
                                ship,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } ?: Text(ship, style = MaterialTheme.typography.bodyMedium)
                    }
                    OutlinedButton(onClick = { onUnban(ship) }) { Text("Unban") }
                }
            }
            HorizontalDivider()
        }

        // ───────── Party line ─────────
        if (callController != null && isCallsSupported) {
            PartyLineSection(callController, flag, group, me)
            HorizontalDivider()
        }

        // ───────── Members ─────────
        //
        // Paged rather than lazy: this whole screen is one
        // verticalScroll Column, and a LazyColumn nested in that has
        // no bounded height to work with. A `for` over every member
        // composed the entire roster up front, which is what made big
        // groups crawl.
        SectionHeader("Members · ${group.members.size}")
        var shownMembers by remember(group.flag) { mutableStateOf(MEMBER_PAGE) }
        val visible = group.members.take(shownMembers)
        for (m in visible) {
            MemberRow(
                member = m,
                contactMap = contactMap,
                onLongPress = { onMemberLongPress(m) },
            )
        }
        val remaining = group.members.size - visible.size
        if (remaining > 0) {
            TextButton(
                onClick = { shownMembers += MEMBER_PAGE },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    if (remaining > MEMBER_PAGE) {
                        "Show $MEMBER_PAGE more · $remaining remaining"
                    } else {
                        "Show $remaining more"
                    },
                )
            }
        }
    }
}

/** How many members to add per page. Big enough that small groups
 *  never see the button, small enough that one page is cheap. */
private const val MEMBER_PAGE = 50

/**
 * Whether this group has a voice room, and on whose terms.
 *
 * One line per group, so this is the group's switch rather than a
 * per-channel one, and the call button in every channel follows it.
 * Only the host ship and the group's admins may change it — %trunk
 * enforces that on its own, but showing switches that would be
 * refused is worse than showing none.
 */
@Composable
private fun PartyLineSection(
    controller: io.nisfeb.talon.call.CallController,
    flag: String,
    group: AdminGroup,
    me: String,
) {
    val target = io.nisfeb.talon.call.PartyLineHost.roomForGroup(flag) ?: return
    val (host, roomName) = target
    val key = "$host/$roomName"

    val hosted by controller.rooms.collectAsState()
    val invited by controller.invites.collectAsState()
    val link by controller.listenLink.collectAsState()
    val scope = rememberCoroutineScope()

    val room = hosted[key]
    val invite = invited[key]
    val enabled = room != null || invite != null
    val listening = room?.listen ?: invite?.listen ?: false
    val sfuBase = room?.sfuBase?.takeIf { it.isNotEmpty() }
        ?: invite?.sfuBase?.takeIf { it.isNotEmpty() }

    val mayEdit = me.isNotEmpty() &&
        (me == host || group.members.any { it.ship == me && it.isAdmin })

    var sfuOpen by remember(flag) { mutableStateOf(false) }
    var sfuBaseField by remember(flag) { mutableStateOf("") }
    var sfuGroupField by remember(flag) { mutableStateOf("talon") }
    var sfuKeyField by remember(flag) { mutableStateOf("") }

    SectionHeader("Party line")
    if (!mayEdit) {
        Text(
            if (enabled) "This group has a party line." else "This group has no party line.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    FeatureRow(
        label = "Party line",
        description = "A voice room for the whole group. Every channel joins the same one.",
        checked = enabled,
        onChange = { on ->
            scope.launch {
                controller.configureRoom(host, roomName, open = on, listen = false)
            }
        },
    )

    if (!enabled) return

    FeatureRow(
        label = "Anyone with a link can listen",
        description = if (listening) {
            "People outside the group can listen with a link."
        } else {
            "Only group members can join."
        },
        checked = listening,
        onChange = { on ->
            scope.launch { controller.configureRoom(host, roomName, open = true, listen = on) }
        },
    )

    if (listening) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val clipboard = LocalClipboardManager.current
            if (link == null) {
                OutlinedButton(onClick = { scope.launch { controller.shareRoom(roomName) } }) {
                    Text("Create listen link")
                }
            } else {
                Text(
                    // The token is the credential; don't put it on screen.
                    link!!.url.substringBefore("?token="),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(link!!.url)) }) {
                    Text("Copy")
                }
            }
        }
        Text(
            "A link expires on its own and can't be revoked early.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Whose sidecar the audio runs through. Groups that would rather
    // not route through the host ship's server can point at their own.
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().clickable { sfuOpen = !sfuOpen },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Server", style = MaterialTheme.typography.bodyMedium)
            Text(
                sfuBase ?: "The host ship's own",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            if (sfuOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (sfuOpen) "Hide server settings" else "Server settings",
        )
    }
    if (sfuOpen) {
        OutlinedTextField(
            value = sfuBaseField,
            onValueChange = { sfuBaseField = it },
            label = { Text("https://your-sidecar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sfuGroupField,
            onValueChange = { sfuGroupField = it },
            label = { Text("Galène group") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sfuKeyField,
            onValueChange = { sfuKeyField = it },
            label = { Text("Shared secret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = sfuBaseField.isNotBlank() && sfuKeyField.isNotBlank(),
                onClick = {
                    scope.launch {
                        controller.configureRoom(
                            host, roomName, open = true, listen = listening,
                            sfu = io.nisfeb.talon.call.SfuConfig(
                                sfuBaseField.trim().trimEnd('/'),
                                sfuGroupField.trim(),
                                sfuKeyField.trim(),
                            ),
                        )
                        sfuKeyField = ""
                        sfuOpen = false
                    }
                },
            ) { Text("Use this server") }
            if (sfuBase != null) {
                OutlinedButton(onClick = {
                    scope.launch {
                        controller.configureRoom(
                            host, roomName, open = true, listen = listening, sfu = null,
                        )
                        sfuOpen = false
                    }
                }) { Text("Use the host's") }
            }
        }
    }
}

@Composable
private fun FeatureRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ShipRow(
    ship: String,
    contactMap: ContactMap,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = contactMap.nickname(ship) ?: ship,
            url = contactMap.avatar(ship),
            colorHex = contactMap.shipColor(ship),
            size = 32.dp,
        )
        Column(Modifier.weight(1f)) {
            contactMap.nickname(ship)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    ship,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Text(ship, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberRow(
    member: AdminMember,
    contactMap: ContactMap,
    onLongPress: () -> Unit,
) {
    val nickname = contactMap.nickname(member.ship)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableWithSecondary(onClick = {}, onLongClick = onLongPress)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = nickname ?: member.ship,
            url = contactMap.avatar(member.ship),
            colorHex = contactMap.shipColor(member.ship),
            size = 32.dp,
        )
        Column(Modifier.weight(1f)) {
            if (!nickname.isNullOrBlank()) {
                Text(
                    nickname,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    member.ship,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    member.ship,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Admin badge + other sects rendered as chips.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (s in member.sects) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (s == "admin") MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        s,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
