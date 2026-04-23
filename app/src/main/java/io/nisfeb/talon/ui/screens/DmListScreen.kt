package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.TalonApplication
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.data.FolderEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.BatteryExemptionBanner
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.FolderAssignmentSheet
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DmListScreen(
    db: AppDatabase,
    onOpenConversation: (whom: String) -> Unit,
    onOpenSearch: () -> Unit,
    onNewMessage: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSelfProfile: () -> Unit,
    onOpenStatusFeed: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val rows by remember {
        combine(
            db.messages().conversationLatest(),
            db.unreads().stream(),
        ) { messages, unreads ->
            val unreadMap = unreads.associate { it.whom to it.count }
            // Defensive: guarantee one row per whom. The DAO query already
            // enforces this, but any future change must not be allowed to
            // reach LazyColumn's duplicate-key crash.
            val result = messages
                .distinctBy { it.whom }
                .map { m -> m to (unreadMap[m.whom] ?: 0) }
            HomeListSnapshot.rows = result
            result
        }
    }.collectAsState(initial = HomeListSnapshot.rows)

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        ).onEach { HomeListSnapshot.contactMap = it }
    }.collectAsState(initial = HomeListSnapshot.contactMap)

    val app = (LocalContext.current.applicationContext as TalonApplication)
    val drafts by app.drafts.state.collectAsState()

    val folders by remember {
        db.folders().streamFolders().onEach { HomeListSnapshot.folders = it }
    }.collectAsState(initial = HomeListSnapshot.folders)
    val members by remember {
        db.folders().streamMembers().onEach { HomeListSnapshot.members = it }
    }.collectAsState(initial = HomeListSnapshot.members)
    val pins by remember {
        db.pins().stream().onEach { HomeListSnapshot.pins = it }
    }.collectAsState(initial = HomeListSnapshot.pins)
    val pinnedWhoms = remember(pins) { pins.map { it.whom }.toSet() }
    val groupOrders by remember {
        db.groupOrders().stream().onEach { HomeListSnapshot.groupOrders = it }
    }.collectAsState(initial = HomeListSnapshot.groupOrders)

    // selectedFolderId = null means the special "All" tab.
    var selectedFolderId by remember { mutableStateOf<Long?>(null) }
    var folderSheetWhom by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }

    val membersByWhom = remember(members) {
        members.groupBy(FolderMemberEntity::whom) { it.folderId }
    }
    val membersByFolder = remember(members) {
        members.groupBy(FolderMemberEntity::folderId) { it.whom }
    }
    // Per-folder display order: map whom → ordinal for the selected folder.
    val folderMemberOrdinals = remember(members, selectedFolderId) {
        val folderId = selectedFolderId ?: return@remember emptyMap<String, Int>()
        members.filter { it.folderId == folderId }
            .associate { it.whom to it.ordinal }
    }
    val filteredRows = remember(rows, selectedFolderId, membersByFolder, folderMemberOrdinals) {
        val folderId = selectedFolderId ?: return@remember rows
        val whomSet = membersByFolder[folderId].orEmpty().toSet()
        rows.filter { (m, _) -> m.whom in whomSet }
            // Stable sort: user-set ordinal first, then latest-first for ties.
            .sortedWith(
                compareBy<Pair<MessageEntity, Int>> { (m, _) ->
                    folderMemberOrdinals[m.whom] ?: Int.MAX_VALUE
                }.thenByDescending { (m, _) -> m.sentMs }
            )
    }

    // Which groups are currently expanded. Transient state — resets on
    // process death; can be persisted later if folks ask for it.
    // Persist expanded-group set across navigations so groups stay open
    // when the user returns from a chat.
    var expandedGroups by remember { mutableStateOf(HomeListSnapshot.expandedGroups) }
    // Mirror every change back into the snapshot so the next mount
    // starts from the user's latest expansion state.
    androidx.compose.runtime.LaunchedEffect(expandedGroups) {
        HomeListSnapshot.expandedGroups = expandedGroups
    }

    // For the "All" tab we render a structured home list (Pinned →
    // Groups → DMs); for any selected folder we fall back to a flat
    // filtered view so the folder is a pure subset of everything.
    val allUnreads = remember(rows) { rows.associate { it.first.whom to it.second } }
    val homeRows = remember(
        selectedFolderId, rows, pins, groupOrders, contactMap, expandedGroups, allUnreads,
    ) {
        if (selectedFolderId != null) emptyList()
        else buildHomeRows(
            allConvs = rows,
            pinnedWhoms = pins.map { it.whom },
            contactMap = contactMap,
            expandedGroups = expandedGroups,
            allUnreads = allUnreads,
            groupOrderFlags = groupOrders.map { it.flag },
        )
    }
    // Snapshot the effective group order at this moment so the reorder
    // callback can compute new orderings without needing to replicate
    // buildHomeRows' sorting logic.
    val effectiveGroupOrder = remember(homeRows) {
        homeRows.filterIsInstance<HomeRow.GroupHead>().map { it.flag }
    }

    // Reuse a singleton LazyListState so the user's scroll position in
    // the home list survives navigating into a chat and back out.
    val listState = HomeListSnapshot.listState
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        when {
            fromKey.startsWith("pin:") && toKey.startsWith("pin:") -> {
                val fromWhom = fromKey.removePrefix("pin:")
                val toWhom = toKey.removePrefix("pin:")
                val order = pins.map { it.whom }.toMutableList()
                val fi = order.indexOf(fromWhom)
                val ti = order.indexOf(toWhom)
                if (fi < 0 || ti < 0 || fi == ti) return@rememberReorderableLazyListState
                order.add(ti, order.removeAt(fi))
                app.repo.settingsSync.reorderPins(order)
            }
            fromKey.startsWith("group:") && toKey.startsWith("group:") -> {
                val fromFlag = fromKey.removePrefix("group:")
                val toFlag = toKey.removePrefix("group:")
                val order = effectiveGroupOrder.toMutableList()
                val fi = order.indexOf(fromFlag)
                val ti = order.indexOf(toFlag)
                if (fi < 0 || ti < 0 || fi == ti) return@rememberReorderableLazyListState
                order.add(ti, order.removeAt(fi))
                app.repo.settingsSync.reorderGroupOrders(order)
            }
            fromKey.startsWith("fmem:") && toKey.startsWith("fmem:") -> {
                val folderId = selectedFolderId ?: return@rememberReorderableLazyListState
                val fromWhom = fromKey.removePrefix("fmem:")
                val toWhom = toKey.removePrefix("fmem:")
                val order = filteredRows.map { it.first.whom }.toMutableList()
                val fi = order.indexOf(fromWhom)
                val ti = order.indexOf(toWhom)
                if (fi < 0 || ti < 0 || fi == ti) return@rememberReorderableLazyListState
                order.add(ti, order.removeAt(fi))
                app.repo.settingsSync.reorderFolderMembers(folderId, order)
            }
            // Any other cross-section / cross-kind swap: silently ignore.
        }
    }

    Box(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    id = io.nisfeb.talon.R.mipmap.ic_launcher_monochrome,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Text(
                "Talon",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("My profile") },
                        onClick = {
                            menuOpen = false
                            onOpenSelfProfile()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Statuses") },
                        onClick = {
                            menuOpen = false
                            onOpenStatusFeed()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks") },
                        onClick = {
                            menuOpen = false
                            onOpenBookmarks()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Activity") },
                        onClick = {
                            menuOpen = false
                            onOpenActivity()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            menuOpen = false
                            onOpenSettings()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Sign out") },
                        onClick = {
                            menuOpen = false
                            onSignOut()
                        },
                    )
                }
            }
        }
        HorizontalDivider()
        BatteryExemptionBanner()
        FolderTabs(
            folders = folders,
            selectedFolderId = selectedFolderId,
            onSelect = { selectedFolderId = it },
            onLongPress = { f -> renamingFolder = f },
            onCreateNew = { creatingFolder = true },
        )
        HorizontalDivider()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (selectedFolderId != null) {
                // Folder view — flat filtered list. Each row is draggable
                // so the user can curate per-folder ordering.
                items(
                    items = filteredRows,
                    key = { "fmem:${it.first.whom}" },
                    contentType = { "fmem" },
                ) { (m, unread) ->
                    ReorderableItem(reorderState, key = "fmem:${m.whom}") { _ ->
                        val haptic = LocalHapticFeedback.current
                        ConversationRow(
                            m = m,
                            unread = unread,
                            contactMap = contactMap,
                            draft = drafts[m.whom],
                            onClick = { onOpenConversation(m.whom) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                folderSheetWhom = m.whom
                            },
                            dragHandleModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                            ),
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                // All view — structured home list.
                items(
                    items = homeRows,
                    key = { it.key },
                    contentType = { it::class.simpleName.orEmpty() },
                ) { row ->
                    when (row) {
                        is HomeRow.Header -> SectionHeader(row.label)
                        is HomeRow.Pinned -> {
                            ReorderableItem(reorderState, key = row.key) { _ ->
                                val haptic = LocalHapticFeedback.current
                                PinnedConversationRow(
                                    whom = row.whom,
                                    m = row.m,
                                    unread = row.unread,
                                    contactMap = contactMap,
                                    draft = drafts[row.whom],
                                    onClick = { onOpenConversation(row.whom) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        folderSheetWhom = row.whom
                                    },
                                    dragHandleModifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
                        is HomeRow.GroupHead -> {
                            ReorderableItem(reorderState, key = row.key) { _ ->
                                val haptic = LocalHapticFeedback.current
                                GroupHeaderRow(
                                    title = row.title,
                                    avatarUrl = row.image,
                                    childCount = row.childCount,
                                    totalUnread = row.totalUnread,
                                    expanded = row.expanded,
                                    onToggle = {
                                        expandedGroups = if (row.flag in expandedGroups) {
                                            expandedGroups - row.flag
                                        } else {
                                            expandedGroups + row.flag
                                        }
                                    },
                                    dragHandleModifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                            // Collapse while dragging so children
                                            // don't get in the way of swap targets.
                                            expandedGroups = expandedGroups - row.flag
                                        },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
                        is HomeRow.GroupChild -> {
                            val haptic = LocalHapticFeedback.current
                            GroupChannelRow(
                                whom = row.whom,
                                m = row.m,
                                unread = row.unread,
                                contactMap = contactMap,
                                draft = drafts[row.whom],
                                onClick = { onOpenConversation(row.whom) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    folderSheetWhom = row.whom
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                        is HomeRow.Flat -> {
                            val haptic = LocalHapticFeedback.current
                            ConversationRow(
                                m = row.m,
                                unread = row.unread,
                                contactMap = contactMap,
                                draft = drafts[row.m.whom],
                                onClick = { onOpenConversation(row.m.whom) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    folderSheetWhom = row.m.whom
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
        FloatingActionButton(
            onClick = onNewMessage,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) { Icon(Icons.Filled.Add, contentDescription = "New message") }

        // Subtle off-screen unread indicators. Only visible in the
        // structured (All-tab) view; the folder view is usually short.
        if (selectedFolderId == null) {
            UnreadOffscreenIndicators(
                homeRows = homeRows,
                listState = listState,
                onScrollTo = { idx ->
                    scope.launch { listState.animateScrollToItem(idx) }
                },
            )
        }
    }

    folderSheetWhom?.let { whom ->
        val label = contactMap.conversationLabel(whom)
        val selected = remember(whom, members) {
            membersByWhom[whom].orEmpty().toSet()
        }
        val pinned = whom in pinnedWhoms
        FolderAssignmentSheet(
            conversationLabel = label,
            folders = folders,
            selectedFolderIds = selected,
            isPinned = pinned,
            onTogglePin = {
                scope.launch {
                    if (pinned) app.repo.settingsSync.removePin(whom)
                    else app.repo.settingsSync.pin(whom)
                }
            },
            onToggle = { folder, checked ->
                scope.launch {
                    if (checked) app.repo.settingsSync.addFolderMember(folder.id, whom)
                    else app.repo.settingsSync.removeFolderMember(folder.id, whom)
                }
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = app.repo.settingsSync.createFolder(name, folders.size)
                    app.repo.settingsSync.addFolderMember(id, whom)
                }
            },
            onDismiss = { folderSheetWhom = null },
        )
    }

    renamingFolder?.let { folder ->
        FolderRenameDialog(
            folder = folder,
            onRename = { newName ->
                scope.launch { app.repo.settingsSync.renameFolder(folder.id, newName) }
                renamingFolder = null
            },
            onDelete = {
                renamingFolder = null
                confirmDeleteFolder = folder
            },
            onDismiss = { renamingFolder = null },
        )
    }

    confirmDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("Delete '${folder.name}'?") },
            text = { Text("The conversations themselves stay; they just leave this folder.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.repo.settingsSync.deleteFolder(folder.id)
                    }
                    if (selectedFolderId == folder.id) selectedFolderId = null
                    confirmDeleteFolder = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteFolder = null }) { Text("Cancel") }
            },
        )
    }

    if (creatingFolder) {
        FolderCreateDialog(
            onCreate = { name ->
                scope.launch {
                    app.repo.settingsSync.createFolder(name, folders.size)
                }
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTabs(
    folders: List<FolderEntity>,
    selectedFolderId: Long?,
    onSelect: (Long?) -> Unit,
    onLongPress: (FolderEntity) -> Unit,
    onCreateNew: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "__all") {
            TabChip(
                text = "All",
                selected = selectedFolderId == null,
                onClick = { onSelect(null) },
            )
        }
        itemsIndexed(folders, key = { _, f -> f.id }) { _, folder ->
            TabChip(
                text = folder.name,
                selected = selectedFolderId == folder.id,
                onClick = { onSelect(folder.id) },
                onLongClick = { onLongPress(folder) },
            )
        }
        item(key = "__new") {
            TabChip(
                text = "+",
                selected = false,
                onClick = onCreateNew,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        ),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun FolderRenameDialog(
    folder: FolderEntity,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(folder) { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onRename(name.trim()) },
                    enabled = name.isNotBlank() && name != folder.name,
                ) { Text("Rename") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FolderCreateDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    m: MessageEntity,
    unread: Int,
    contactMap: ContactMap,
    draft: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragHandleModifier: Modifier? = null,
) {
    val preview = remember(m.id, m.contentJson) {
        StoryCache.textFor(m.id, m.contentJson)
            .take(200)
            .replace('\n', ' ')
    }
    val timestamp = remember(m.sentMs) { formatRelative(m.sentMs) }
    val title = remember(m.whom, contactMap) { contactMap.conversationLabel(m.whom) }
    val authorLabel = remember(m.author, contactMap) { contactMap.displayName(m.author) }
    val draftPreview = remember(draft) {
        draft?.trim()?.replace('\n', ' ')?.take(160)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = title,
            url = contactMap.conversationAvatar(m.whom),
            colorHex = contactMap.conversationColor(m.whom),
            size = 44.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                ),
            )
            Text(
                "$authorLabel · $timestamp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!draftPreview.isNullOrEmpty()) {
                Text(
                    "Draft: $draftPreview",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            } else if (preview.isNotEmpty()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (unread > 0) {
            Text(
                if (unread < 100) unread.toString() else "99+",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (dragHandleModifier != null) {
            Box(modifier = dragHandleModifier.padding(start = 4.dp)) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private val TIME_TODAY = SimpleDateFormat("HH:mm", Locale.getDefault())
private val DATE_OLD = SimpleDateFormat("MMM d", Locale.getDefault())

private fun formatRelative(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    return when {
        diff < 24 * 3600_000L -> TIME_TODAY.format(Date(ms))
        else -> DATE_OLD.format(Date(ms))
    }
}

@Composable
private fun SectionHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedConversationRow(
    whom: String,
    m: MessageEntity?,
    unread: Int,
    contactMap: ContactMap,
    draft: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val title = remember(whom, contactMap) { contactMap.conversationLabel(whom) }
    val avatar = remember(whom, contactMap) { contactMap.conversationAvatar(whom) }
    val preview = m?.let {
        remember(it.id, it.contentJson) {
            StoryCache.textFor(it.id, it.contentJson).take(200).replace('\n', ' ')
        }
    }
    val draftPreview = remember(draft) {
        draft?.trim()?.replace('\n', ' ')?.take(160)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = title,
            url = avatar,
            colorHex = contactMap.conversationColor(whom),
            size = 44.dp,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                )
            }
            if (!draftPreview.isNullOrEmpty()) {
                Text(
                    "Draft: $draftPreview",
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            } else if (!preview.isNullOrEmpty()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (unread > 0) {
            Text(
                if (unread < 100) unread.toString() else "99+",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        // Drag handle — long-press the icon to pick it up and drag; a
        // short tap opens the same pin/folder sheet as long-pressing the
        // row, matching the affordance most users expect.
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .clickable(onClick = onLongClick)
                .then(dragHandleModifier),
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder, tap for options",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun GroupHeaderRow(
    title: String,
    avatarUrl: String?,
    childCount: Int,
    totalUnread: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 160),
        label = "groupChevronRotation",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val isHexTint = avatarUrl?.startsWith("#") == true
        Avatar(
            label = title,
            url = if (isHexTint) null else avatarUrl,
            colorHex = if (isHexTint) avatarUrl else null,
            size = 32.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Text(
                "$childCount channel${if (childCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (totalUnread > 0 && !expanded) {
            Text(
                if (totalUnread < 100) totalUnread.toString() else "99+",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = rotation },
        )
        // Drag handle — long-press to reorder within the Groups section.
        Box(modifier = dragHandleModifier.padding(start = 4.dp)) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupChannelRow(
    whom: String,
    m: MessageEntity?,
    unread: Int,
    contactMap: ContactMap,
    draft: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shortName = remember(whom) { contactMap.channelShortName(whom) }
    val preview = m?.let {
        remember(it.id, it.contentJson) {
            StoryCache.textFor(it.id, it.contentJson).take(200).replace('\n', ' ')
        }
    }
    val timestamp = m?.let { remember(it.sentMs) { formatRelative(it.sentMs) } }
    val authorLabel = m?.let {
        remember(it.author, contactMap) { contactMap.displayName(it.author) }
    }
    val draftPreview = remember(draft) {
        draft?.trim()?.replace('\n', ' ')?.take(160)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                shortName,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            if (!draftPreview.isNullOrEmpty()) {
                Text(
                    "Draft: $draftPreview",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            } else if (!preview.isNullOrEmpty() && timestamp != null) {
                Text(
                    "${authorLabel ?: ""} · $timestamp · $preview",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (unread > 0) {
            Text(
                if (unread < 100) unread.toString() else "99+",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}


/**
 * Process-wide snapshot of the home-list state. Lets DmListScreen
 * re-mount (e.g. after navigating back from a chat) with the last-seen
 * rows + contactMap already populated, so content previews stay visible
 * while the Room Flow reconnects — no empty-to-full flicker.
 */
/**
 * Small arrow chips at the top and bottom of the home list that
 * appear when there are unread conversations currently scrolled
 * off-screen. Tap to jump to the nearest one in that direction.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.UnreadOffscreenIndicators(
    homeRows: List<HomeRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onScrollTo: (Int) -> Unit,
) {
    // Indices that count as "unread" for our purposes. Exclude group
    // headers when expanded — their children already carry the unread
    // flag and would double-report.
    val unreadIndices = remember(homeRows) {
        val expandedFlags = homeRows.asSequence()
            .filterIsInstance<HomeRow.GroupHead>()
            .filter { it.expanded }
            .map { it.flag }
            .toSet()
        homeRows.mapIndexedNotNull { i, row ->
            val hasUnread = when (row) {
                is HomeRow.Pinned -> row.unread > 0
                is HomeRow.Flat -> row.unread > 0
                is HomeRow.GroupChild -> row.unread > 0
                is HomeRow.GroupHead -> row.totalUnread > 0 && row.flag !in expandedFlags
                is HomeRow.Header -> false
            }
            if (hasUnread) i else null
        }
    }

    if (unreadIndices.isEmpty()) return

    val firstVisible = listState.firstVisibleItemIndex
    val lastVisible = listState.layoutInfo.visibleItemsInfo
        .lastOrNull()?.index ?: -1

    val unreadAbove = unreadIndices.filter { it < firstVisible }
    val unreadBelow = unreadIndices.filter { it > lastVisible }

    if (unreadAbove.isNotEmpty()) {
        UnreadChip(
            count = unreadAbove.size,
            arrowUp = true,
            onClick = { onScrollTo(unreadAbove.max()) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp),
        )
    }
    if (unreadBelow.isNotEmpty()) {
        UnreadChip(
            count = unreadBelow.size,
            arrowUp = false,
            onClick = { onScrollTo(unreadBelow.min()) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
        )
    }
}

@Composable
private fun UnreadChip(
    count: Int,
    arrowUp: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (arrowUp)
                Icons.Filled.KeyboardArrowUp
            else
                Icons.Filled.KeyboardArrowDown,
            contentDescription = if (arrowUp) "Unread above" else "Unread below",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * Zero the cached unread count for a specific whom. Called from the
 * chat screen's open-hook so that when the user navigates back, the
 * instant-hydrate path doesn't paint a stale badge for a fraction of
 * a second before the Flow catches up.
 */
fun homeSnapshotZeroUnread(whom: String) {
    HomeListSnapshot.rows = HomeListSnapshot.rows.map { (m, unread) ->
        if (m.whom == whom && unread > 0) m to 0 else m to unread
    }
}

private object HomeListSnapshot {
    @Volatile var rows: List<Pair<MessageEntity, Int>> = emptyList()
    @Volatile var contactMap: ContactMap = ContactMap.EMPTY
    @Volatile var expandedGroups: Set<String> = emptySet()
    @Volatile var pins: List<io.nisfeb.talon.data.PinEntity> = emptyList()
    @Volatile var groupOrders: List<io.nisfeb.talon.data.GroupOrderEntity> = emptyList()
    @Volatile var folders: List<FolderEntity> = emptyList()
    @Volatile var members: List<FolderMemberEntity> = emptyList()
    // Shared LazyListState so scroll position persists across mounts.
    val listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.LazyListState()
}
