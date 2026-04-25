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
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import kotlinx.coroutines.flow.map
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
    onOpenAdministration: () -> Unit = {},
    onOpenInvites: () -> Unit = {},
    onOpenSettings: () -> Unit,
    /** Every ship the user is logged in with. The switcher drawer
     *  lists them and the header badge shows the active one when the
     *  list has more than one entry. */
    allShips: List<String> = emptyList(),
    activeShip: String? = null,
    /** Per-ship nickname cache, keyed by patp. Used for the header
     *  badge + the switcher drawer rows. Empty by default. */
    shipNicknames: Map<String, String> = emptyMap(),
    onSwitchShip: (String) -> Unit = {},
    onAddShip: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Per-ship snapshot holder. Binding here swaps the singleton
    // `active` reference so helpers (homeSnapshotZeroUnread) write to
    // the right ship's cache. Stable across recompositions — if the
    // user switches ships the tree re-keys and we get a fresh snap.
    val snap = remember(activeShip) { HomeListSnapshot.bind(activeShip) }

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
            snap.rows = result
            result
        }
    }.collectAsState(initial = snap.rows)

    // Mention (notify-count) map, keyed by whom. Built off the same
    // %activity summaries — `notifyCount` is the count of events that
    // should actually ping you (@-mentions, replies to your posts, etc.).
    val mentionCounts by remember {
        db.unreads().stream().map { list ->
            list.associate { it.whom to it.notifyCount }
        }
    }.collectAsState(initial = emptyMap())

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        ).onEach { snap.contactMap = it }
    }.collectAsState(initial = snap.contactMap)

    val app = (LocalContext.current.applicationContext as TalonApplication)
    val drafts by app.drafts.state.collectAsState()

    val folders by remember {
        db.folders().streamFolders().onEach { snap.folders = it }
    }.collectAsState(initial = snap.folders)
    val members by remember {
        db.folders().streamMembers().onEach { snap.members = it }
    }.collectAsState(initial = snap.members)
    val groupOrders by remember {
        db.groupOrders().stream().onEach { snap.groupOrders = it }
    }.collectAsState(initial = snap.groupOrders)

    // Tab selection state. Either a custom folder is selected, or one
    // of the three derived "special" tabs (All, Unread, Mentions).
    // Seeded from HomeListSnapshot so back-navigation from a chat
    // returns the user to whatever tab they were on, not the
    // fresh-launch auto-landed folder.
    var selectedFolderId by remember {
        mutableStateOf(snap.selectedFolderId)
    }
    var selectedSpecial by remember {
        mutableStateOf(snap.selectedSpecial)
    }
    var initialTabApplied by remember {
        mutableStateOf(snap.initialTabApplied)
    }
    LaunchedEffect(folders) {
        if (!initialTabApplied) {
            if (folders.isNotEmpty()) {
                selectedFolderId = folders.first().id
            }
            initialTabApplied = true
        }
    }
    // Mirror every selection change back into the snapshot so the
    // next mount picks up where this one left off.
    LaunchedEffect(selectedFolderId, selectedSpecial, initialTabApplied) {
        snap.selectedFolderId = selectedFolderId
        snap.selectedSpecial = selectedSpecial
        snap.initialTabApplied = initialTabApplied
    }
    var folderSheetWhom by remember { mutableStateOf<String?>(null) }
    // Separate state for group-kind folder assignment — different write path.
    var folderSheetGroup by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    // Edit mode: when on, rows are drag-reorderable (long-press anywhere
    // on the row picks up the drag) and the long-press action sheet is
    // suppressed. When off, long-press opens the action sheet as normal.
    var editMode by remember { mutableStateOf(false) }

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
    // Kept for legacy call sites; folder view now renders via folderRows.
    val filteredRows = remember(rows, selectedFolderId, membersByFolder, folderMemberOrdinals) {
        val folderId = selectedFolderId ?: return@remember rows
        val whomSet = membersByFolder[folderId].orEmpty().toSet()
        rows.filter { (m, _) -> m.whom in whomSet }
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
    var expandedGroups by remember { mutableStateOf(snap.expandedGroups) }
    // Mirror every change back into the snapshot so the next mount
    // starts from the user's latest expansion state.
    androidx.compose.runtime.LaunchedEffect(expandedGroups) {
        snap.expandedGroups = expandedGroups
    }

    // Stable callbacks so LazyColumn rows stay skippable during scroll.
    // Without rememberUpdatedState the fresh lambdas rebuilt on every
    // DAO emission break the compose skippable fast-path; with it we
    // keep a frozen callback identity that still dispatches through to
    // the latest outer function. Same pattern as DmChatScreen.
    val currentOnOpenConversation by rememberUpdatedState(onOpenConversation)
    val hapticRoot = LocalHapticFeedback.current
    val onRowOpen: (String) -> Unit = remember {
        { whom -> currentOnOpenConversation(whom) }
    }
    val onRowLongPress: (String) -> Unit = remember(editMode) {
        { whom ->
            if (!editMode) {
                hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                folderSheetWhom = whom
            }
        }
    }
    val onGroupHeadToggle: (String) -> Unit = remember {
        { flag ->
            expandedGroups = if (flag in expandedGroups) {
                expandedGroups - flag
            } else {
                expandedGroups + flag
            }
        }
    }
    val onGroupHeadLongPress: (String) -> Unit = remember(editMode) {
        { flag ->
            if (!editMode) {
                hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                folderSheetGroup = flag
            }
        }
    }

    // For the "All" tab we render a structured home list (Groups →
    // DMs); for any selected folder we fall back to a folder view.
    val allUnreads = remember(rows) { rows.associate { it.first.whom to it.second } }
    val homeRows = remember(
        selectedFolderId, rows, groupOrders, contactMap, expandedGroups, allUnreads,
    ) {
        if (selectedFolderId != null) emptyList()
        else buildHomeRows(
            allConvs = rows,
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

    // Unread view rows — flat list of whoms with unread > 0, most recent
    // first. Derived from the same `rows` snapshot; empty when caught up.
    val unreadRows = remember(rows) {
        rows.filter { (_, unread) -> unread > 0 }
            .sortedByDescending { (m, _) -> m.sentMs }
    }

    // Folder view rows — a blend of group heads (collapsible) and loose
    // conversation rows, in the user's per-folder ordinal order.
    val folderRows = remember(
        selectedFolderId, rows, members, contactMap, expandedGroups, allUnreads,
    ) {
        val fid = selectedFolderId ?: return@remember emptyList<HomeRow>()
        val folderMembers = members.filter { it.folderId == fid }
        buildFolderRows(
            members = folderMembers,
            allConvs = rows,
            contactMap = contactMap,
            expandedGroups = expandedGroups,
            allUnreads = allUnreads,
        )
    }

    // Reuse a singleton LazyListState so the user's scroll position in
    // the home list survives navigating into a chat and back out.
    val listState = snap.listState
    // Tracks what was touched during a drag so onDragStopped knows what
    // to push to %settings. Null means "nothing pending".
    var pendingPushFolderId by remember { mutableStateOf<Long?>(null) }
    var pendingPushGroupOrders by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        when {
            selectedFolderId == null && selectedSpecial == SpecialTab.All &&
                fromKey.startsWith("group:") && toKey.startsWith("group:") -> {
                val fromFlag = fromKey.removePrefix("group:")
                val toFlag = toKey.removePrefix("group:")
                val order = effectiveGroupOrder.toMutableList()
                val fi = order.indexOf(fromFlag)
                val ti = order.indexOf(toFlag)
                if (fi < 0 || ti < 0 || fi == ti) return@rememberReorderableLazyListState
                order.add(ti, order.removeAt(fi))
                // Local Room write only — smooth per-frame animation.
                // We push to %settings once on drag release.
                app.repo.settingsSync.reorderGroupOrdersLocal(order)
                pendingPushGroupOrders = true
            }
            // Folder-view drag: group-head rows and loose-conv rows both
            // reorder the same folder_members table. Children and headers
            // aren't reorderable.
            fromKey.isFolderDraggableKey() && toKey.isFolderDraggableKey() -> {
                val folderId = selectedFolderId ?: return@rememberReorderableLazyListState
                val fromWhom = folderMemberWhomFromKey(fromKey) ?: return@rememberReorderableLazyListState
                val toWhom = folderMemberWhomFromKey(toKey) ?: return@rememberReorderableLazyListState
                val order = folderRows
                    .mapNotNull { folderMemberWhomFromKey(it.key) }
                    .toMutableList()
                val fi = order.indexOf(fromWhom)
                val ti = order.indexOf(toWhom)
                if (fi < 0 || ti < 0 || fi == ti) return@rememberReorderableLazyListState
                order.add(ti, order.removeAt(fi))
                app.repo.settingsSync.reorderFolderMembersLocal(folderId, order)
                pendingPushFolderId = folderId
            }
            // Any other cross-section / cross-kind swap: silently ignore.
        }
    }
    val onDragStopped: () -> Unit = {
        val folderId = pendingPushFolderId
        val pushGroups = pendingPushGroupOrders
        pendingPushFolderId = null
        pendingPushGroupOrders = false
        if (folderId != null || pushGroups) {
            scope.launch {
                if (folderId != null) {
                    runCatching { app.repo.settingsSync.pushFolderMembersOrder(folderId) }
                }
                if (pushGroups) {
                    runCatching { app.repo.settingsSync.pushGroupOrders() }
                }
            }
        }
    }

    val drawerState = androidx.compose.material3.rememberDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
    )
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ShipSwitcherDrawer(
                ships = allShips,
                activeShip = activeShip,
                nicknames = shipNicknames,
                onPick = { ship ->
                    scope.launch { drawerState.close() }
                    onSwitchShip(ship)
                },
                onAdd = {
                    scope.launch { drawerState.close() }
                    onAddShip()
                },
            )
        },
    ) {
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
                contentDescription = "Switch ship",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { scope.launch { drawerState.open() } },
            )
            Text(
                "Talon",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
            if (allShips.size > 1 && activeShip != null) {
                // Prefer nickname, but collapse to patp when more than
                // one logged-in ship shares the same nickname so the
                // label actually disambiguates.
                val activeNick = shipNicknames[activeShip]
                val collision = activeNick != null &&
                    allShips.count { shipNicknames[it] == activeNick } > 1
                val label = if (activeNick != null && !collision) activeNick else activeShip
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = { editMode = !editMode }) {
                Icon(
                    imageVector = if (editMode) Icons.Filled.Done else Icons.Filled.Edit,
                    contentDescription = if (editMode) "Finish reordering" else "Reorder",
                    tint = if (editMode) MaterialTheme.colorScheme.primary
                    else LocalContentColor.current,
                )
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
                        text = { Text("Administration") },
                        onClick = {
                            menuOpen = false
                            onOpenAdministration()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Invites") },
                        onClick = {
                            menuOpen = false
                            onOpenInvites()
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
        val totalUnread = remember(unreadRows) { unreadRows.sumOf { it.second } }
        val totalMentions = remember(mentionCounts) { mentionCounts.values.sum() }
        FolderTabs(
            folders = folders,
            selectedFolderId = selectedFolderId,
            selectedSpecial = selectedSpecial,
            unreadCount = totalUnread,
            mentionCount = totalMentions,
            editMode = editMode,
            onSelectFolder = {
                selectedFolderId = it
            },
            onSelectSpecial = {
                selectedFolderId = null
                selectedSpecial = it
            },
            onLongPress = { f -> renamingFolder = f },
            onCreateNew = { creatingFolder = true },
        )
        HorizontalDivider()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (selectedFolderId == null && selectedSpecial == SpecialTab.Unread) {
                // Unread view — flat list of whoms with pending pings.
                // Read-only (no drag-reorder, no section headers).
                if (unreadRows.isEmpty()) {
                    item(key = "__unread_empty") {
                        SpecialEmpty("You're all caught up.")
                    }
                } else {
                    items(
                        items = unreadRows,
                        key = { "unr:${it.first.whom}" },
                        contentType = { "unr" },
                    ) { (m, unread) ->
                        ConversationRow(
                            m = m,
                            unread = unread,
                            contactMap = contactMap,
                            draft = drafts[m.whom],
                            onClick = onRowOpen,
                            onLongClick = onRowLongPress,
                        )
                        HorizontalDivider()
                    }
                }
            } else if (selectedFolderId == null && selectedSpecial == SpecialTab.Mentions) {
                // Mentions view — whoms where %activity reports a non-zero
                // notify-count (@-mentions and replies-to-your-posts).
                val mentionRows = rows
                    .filter { (m, _) -> (mentionCounts[m.whom] ?: 0) > 0 }
                    .sortedByDescending { (m, _) -> m.sentMs }
                if (mentionRows.isEmpty()) {
                    item(key = "__mentions_empty") {
                        SpecialEmpty("No mentions.")
                    }
                } else {
                    items(
                        items = mentionRows,
                        key = { "men:${it.first.whom}" },
                        contentType = { "men" },
                    ) { (m, _) ->
                        ConversationRow(
                            m = m,
                            unread = mentionCounts[m.whom] ?: 0,
                            contactMap = contactMap,
                            draft = drafts[m.whom],
                            onClick = onRowOpen,
                            onLongClick = onRowLongPress,
                        )
                        HorizontalDivider()
                    }
                }
            } else if (selectedFolderId != null) {
                // Folder view — user's curated mix of group heads (with
                // collapsible children) and loose conversation rows.
                items(
                    items = folderRows,
                    key = { it.key },
                    contentType = { it::class.simpleName.orEmpty() },
                ) { row ->
                    when (row) {
                        is HomeRow.Header -> SectionHeader(row.label)
                        is HomeRow.GroupHead -> {
                            ReorderableItem(reorderState, key = row.key) { _ ->
                                GroupHeaderRow(
                                    flag = row.flag,
                                    title = row.title,
                                    avatarUrl = row.image,
                                    childCount = row.childCount,
                                    totalUnread = row.totalUnread,
                                    expanded = row.expanded,
                                    onToggle = onGroupHeadToggle,
                                    onLongClick = if (editMode) null else onGroupHeadLongPress,
                                    editMode = editMode,
                                    dragHandleModifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                                            expandedGroups = expandedGroups - row.flag
                                        },
                                        onDragStopped = { onDragStopped() },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
                        is HomeRow.GroupChild -> {
                            GroupChannelRow(
                                whom = row.whom,
                                m = row.m,
                                unread = row.unread,
                                contactMap = contactMap,
                                draft = drafts[row.whom],
                                onClick = onRowOpen,
                                onLongClick = onRowLongPress,
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                        is HomeRow.Flat -> {
                            ReorderableItem(reorderState, key = row.key) { _ ->
                                ConversationRow(
                                    m = row.m,
                                    unread = row.unread,
                                    contactMap = contactMap,
                                    draft = drafts[row.m.whom],
                                    onClick = onRowOpen,
                                    onLongClick = onRowLongPress,
                                    editMode = editMode,
                                    dragHandleModifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragStopped = { onDragStopped() },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
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
                        is HomeRow.GroupHead -> {
                            ReorderableItem(reorderState, key = row.key) { _ ->
                                GroupHeaderRow(
                                    flag = row.flag,
                                    title = row.title,
                                    avatarUrl = row.image,
                                    childCount = row.childCount,
                                    totalUnread = row.totalUnread,
                                    expanded = row.expanded,
                                    onToggle = onGroupHeadToggle,
                                    onLongClick = if (editMode) null else onGroupHeadLongPress,
                                    editMode = editMode,
                                    dragHandleModifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            hapticRoot.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                            // Collapse while dragging so children
                                            // don't get in the way of swap targets.
                                            expandedGroups = expandedGroups - row.flag
                                        },
                                        onDragStopped = { onDragStopped() },
                                    ),
                                )
                                HorizontalDivider()
                            }
                        }
                        is HomeRow.GroupChild -> {
                            GroupChannelRow(
                                whom = row.whom,
                                m = row.m,
                                unread = row.unread,
                                contactMap = contactMap,
                                draft = drafts[row.whom],
                                onClick = onRowOpen,
                                onLongClick = onRowLongPress,
                            )
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                        is HomeRow.Flat -> {
                            ConversationRow(
                                m = row.m,
                                unread = row.unread,
                                contactMap = contactMap,
                                draft = drafts[row.m.whom],
                                onClick = onRowOpen,
                                onLongClick = onRowLongPress,
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

        // Subtle off-screen unread indicators. Only valid for the All
        // tab — the Unread / Mentions tabs render a different flat
        // list, so unreadIndices (built from homeRows) wouldn't line
        // up with listState's visibleItemsInfo and we'd flash a stale
        // "scroll for more" chip on top of an already-fully-visible
        // list. Folder views are short enough not to need the hint.
        if (selectedFolderId == null && selectedSpecial == SpecialTab.All) {
            UnreadOffscreenIndicators(
                homeRows = homeRows,
                listState = listState,
                onScrollTo = { idx ->
                    scope.launch { listState.animateScrollToItem(idx) }
                },
            )
        }
    }
    } // ModalNavigationDrawer content

    folderSheetWhom?.let { whom ->
        val label = contactMap.conversationLabel(whom)
        val selected = remember(whom, members) {
            membersByWhom[whom].orEmpty().toSet()
        }
        FolderAssignmentSheet(
            conversationLabel = label,
            folders = folders,
            selectedFolderIds = selected,
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

    folderSheetGroup?.let { flag ->
        val group = remember(flag, contactMap) {
            contactMap.allGroups().firstOrNull { it.flag == flag }
        }
        val groupLabel = "Group · ${group?.title ?: flag}"
        val selected = remember(flag, members) {
            members.filter { it.whom == flag && it.kind == FolderMemberEntity.KIND_GROUP }
                .map { it.folderId }
                .toSet()
        }
        FolderAssignmentSheet(
            conversationLabel = groupLabel,
            folders = folders,
            selectedFolderIds = selected,
            onToggle = { folder, checked ->
                scope.launch {
                    if (checked) app.repo.settingsSync.addGroupToFolder(folder.id, flag)
                    else app.repo.settingsSync.removeGroupFromFolder(folder.id, flag)
                }
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = app.repo.settingsSync.createFolder(name, folders.size)
                    app.repo.settingsSync.addGroupToFolder(id, flag)
                }
            },
            onDismiss = { folderSheetGroup = null },
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

@Composable
private fun ShipSwitcherDrawer(
    ships: List<String>,
    activeShip: String?,
    nicknames: Map<String, String>,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
) {
    androidx.compose.material3.ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Ships",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            for (ship in ships) {
                val selected = ship == activeShip
                val nickname = nicknames[ship]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onPick(ship) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(
                            id = io.nisfeb.talon.R.mipmap.ic_launcher_monochrome,
                        ),
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Column {
                        if (!nickname.isNullOrBlank()) {
                            Text(
                                nickname,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold
                                    else FontWeight.Medium,
                                ),
                            )
                            Text(
                                ship,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                ship,
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onAdd() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "Add ship",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTabs(
    folders: List<FolderEntity>,
    selectedFolderId: Long?,
    selectedSpecial: SpecialTab,
    unreadCount: Int,
    mentionCount: Int,
    editMode: Boolean,
    onSelectFolder: (Long?) -> Unit,
    onSelectSpecial: (SpecialTab) -> Unit,
    onLongPress: (FolderEntity) -> Unit,
    onCreateNew: () -> Unit,
) {
    val specialActive = selectedFolderId == null
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(folders, key = { _, f -> f.id }) { _, folder ->
            TabChip(
                text = folder.name,
                selected = selectedFolderId == folder.id,
                onClick = { onSelectFolder(folder.id) },
                onLongClick = { onLongPress(folder) },
                // Pencil-to-rename only when the user is in edit mode.
                onEditClick = if (editMode) {
                    { onLongPress(folder) }
                } else null,
            )
        }
        item(key = "__new") {
            TabChip(
                text = "+",
                selected = false,
                onClick = onCreateNew,
            )
        }
        // Special tabs on the right, in priority order:
        //   Mentions (highest-signal) → Unread → All (scavenge pile).
        // Mentions / Unread hide when empty — the tab is a pile of
        // pending items, so no items = no tab.
        // Only keep the tab visible while actively selected — if the
        // user switches to a folder (specialActive = false) or another
        // special tab, the empty tab should disappear.
        if (mentionCount > 0 ||
            (specialActive && selectedSpecial == SpecialTab.Mentions)
        ) {
            item(key = "__mentions") {
                TabChip(
                    text = "Mentions",
                    badgeCount = mentionCount,
                    selected = specialActive && selectedSpecial == SpecialTab.Mentions,
                    onClick = { onSelectSpecial(SpecialTab.Mentions) },
                )
            }
        }
        if (unreadCount > 0 ||
            (specialActive && selectedSpecial == SpecialTab.Unread)
        ) {
            item(key = "__unread") {
                TabChip(
                    text = "Unread",
                    badgeCount = unreadCount,
                    selected = specialActive && selectedSpecial == SpecialTab.Unread,
                    onClick = { onSelectSpecial(SpecialTab.Unread) },
                )
            }
        }
        item(key = "__all") {
            TabChip(
                text = "All",
                selected = specialActive && selectedSpecial == SpecialTab.All,
                onClick = { onSelectSpecial(SpecialTab.All) },
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
    onEditClick: (() -> Unit)? = null,
    badgeCount: Int = 0,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = fg,
        )
        if (badgeCount > 0) {
            // Amber count badge. On an unselected chip the chip bg is
            // surfaceVariant, so amber primary pops; on a selected
            // chip the chip bg is already primary — use onPrimary
            // there so the count stays readable.
            val badgeColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.primary
            Text(
                " " + if (badgeCount < 100) badgeCount.toString() else "99+",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = badgeColor,
            )
        }
        // Rename pencil — only surfaced on the active, renameable chip
        // while the user is in edit mode.
        if (selected && onEditClick != null) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "Rename folder",
                tint = fg,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(14.dp)
                    .clickable { onEditClick() },
            )
        }
    }
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
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    editMode: Boolean = false,
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

    // In edit mode, the entire row is the drag handle — long-press
    // anywhere picks it up. Tap still opens the chat. The action-sheet
    // long-press is suppressed so gestures don't collide.
    val rowClickModifier = if (editMode && dragHandleModifier != null) {
        Modifier.clickable { onClick(m.whom) }.then(dragHandleModifier)
    } else {
        Modifier.combinedClickable(
            onClick = { onClick(m.whom) },
            onLongClick = { onLongClick(m.whom) },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowClickModifier)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                )
                ChannelTypeBadge(m.whom)
            }
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
        if (editMode) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
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

/** Derived "special" tabs that aren't user-created folders. */
internal enum class SpecialTab { All, Unread, Mentions }

@Composable
private fun SpecialEmpty(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether a HomeRow key represents a draggable row in the folder view.
 * GroupHead rows and loose-conversation (Flat) rows are draggable;
 * GroupChild and Header rows are not.
 */
private fun String.isFolderDraggableKey(): Boolean =
    startsWith("group:") || startsWith("conv:")

/** Map a HomeRow key to the folder_members.whom it represents. */
private fun folderMemberWhomFromKey(key: String): String? = when {
    key.startsWith("group:") -> key.removePrefix("group:")
    key.startsWith("conv:") -> key.removePrefix("conv:")
    else -> null
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
private fun GroupHeaderRow(
    flag: String,
    title: String,
    avatarUrl: String?,
    childCount: Int,
    totalUnread: Int,
    expanded: Boolean,
    onToggle: (String) -> Unit,
    editMode: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onLongClick: ((String) -> Unit)? = null,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 160),
        label = "groupChevronRotation",
    )
    val rowClickModifier = if (editMode) {
        Modifier.clickable { onToggle(flag) }.then(dragHandleModifier)
    } else {
        Modifier.combinedClickable(
            onClick = { onToggle(flag) },
            onLongClick = onLongClick?.let { { it(flag) } },
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowClickModifier)
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
        if (editMode) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
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
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
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
            .combinedClickable(
                onClick = { onClick(whom) },
                onLongClick = { onLongClick(whom) },
            )
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
                is HomeRow.Flat -> row.unread > 0
                is HomeRow.GroupChild -> row.unread > 0
                is HomeRow.GroupHead -> row.totalUnread > 0 && row.flag !in expandedFlags
                is HomeRow.Header -> false
            }
            if (hasUnread) i else null
        }
    }

    if (unreadIndices.isEmpty()) return

    // derivedStateOf so we only recompose when the chip-relevant
    // derived values (counts + scroll targets) actually change —
    // direct reads of firstVisibleItemIndex / visibleItemsInfo here
    // would fire on every scroll frame, dragging the whole All tab.
    val above by remember(unreadIndices, listState) {
        derivedStateOf {
            val firstVisible = listState.firstVisibleItemIndex
            unreadIndices.filter { it < firstVisible }
        }
    }
    val below by remember(unreadIndices, listState) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo
                .lastOrNull()?.index ?: -1
            unreadIndices.filter { it > lastVisible }
        }
    }

    if (above.isNotEmpty()) {
        UnreadChip(
            count = above.size,
            arrowUp = true,
            onClick = { onScrollTo(above.max()) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Clears the title row + tab strip above the LazyColumn
                // (~56 + ~52 + status-bar inset on most devices). 72dp
                // overlapped the tab chips on tall-status-bar phones.
                .padding(top = 140.dp),
        )
    }
    if (below.isNotEmpty()) {
        UnreadChip(
            count = below.size,
            arrowUp = false,
            onClick = { onScrollTo(below.min()) },
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
    HomeListSnapshot.active?.let { snap ->
        snap.rows = snap.rows.map { (m, unread) ->
            if (m.whom == whom && unread > 0) m to 0 else m to unread
        }
    }
}

/**
 * Deprecated / unused — we now keep a per-ship snapshot so switching
 * between ships preserves each ship's cached rows, previews, and
 * unread counts until the new ship's Room flows emit fresh data.
 */
fun resetHomeListSnapshot() { /* no-op; per-ship snapshots replace this */ }

/**
 * Per-ship home-list cache. Keyed on the active ship so switching
 * between accounts keeps each one's cached rows (and unread counts)
 * until the new ship's Room query round-trips. Without this, a fresh
 * switch paints the chat list empty-of-unreads for a beat while Room
 * catches up behind whatever repo.start is doing on IO.
 */
private object HomeListSnapshot {
    private val perShip = java.util.concurrent.ConcurrentHashMap<String, ShipSnapshot>()

    @Volatile var active: ShipSnapshot? = null

    fun bind(ship: String?): ShipSnapshot {
        val s = if (ship == null) ShipSnapshot() else perShip.getOrPut(ship) { ShipSnapshot() }
        active = s
        return s
    }
}

@Composable
private fun ChannelTypeBadge(whom: String) {
    val (label, bg) = when {
        whom.startsWith("diary/") -> "Notebook" to MaterialTheme.colorScheme.tertiaryContainer
        whom.startsWith("heap/") -> "Gallery" to MaterialTheme.colorScheme.secondaryContainer
        else -> return
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bg,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

internal class ShipSnapshot {
    @Volatile var rows: List<Pair<MessageEntity, Int>> = emptyList()
    @Volatile var contactMap: ContactMap = ContactMap.EMPTY
    @Volatile var expandedGroups: Set<String> = emptySet()
    @Volatile var groupOrders: List<io.nisfeb.talon.data.GroupOrderEntity> = emptyList()
    @Volatile var folders: List<FolderEntity> = emptyList()
    @Volatile var members: List<FolderMemberEntity> = emptyList()
    @Volatile var selectedFolderId: Long? = null
    @Volatile var selectedSpecial: SpecialTab = SpecialTab.All
    @Volatile var initialTabApplied: Boolean = false
    val listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.LazyListState()
}
