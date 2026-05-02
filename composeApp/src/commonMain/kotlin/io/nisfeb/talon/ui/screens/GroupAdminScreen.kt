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
                    listOf(
                        "chat" to "Chat",
                        "diary" to "Notebook",
                        "heap" to "Gallery",
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
            val picked = pickImage() ?: return@launch
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

        // ───────── Members ─────────
        SectionHeader("Members · ${group.members.size}")
        for (m in group.members) {
            MemberRow(
                member = m,
                contactMap = contactMap,
                onLongPress = { onMemberLongPress(m) },
            )
        }
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
