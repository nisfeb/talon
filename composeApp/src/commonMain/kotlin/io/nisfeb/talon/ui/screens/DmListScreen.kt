package io.nisfeb.talon.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nisfeb.talon.data.AppDatabase
import io.nisfeb.talon.ui.DraftStore
import io.nisfeb.talon.update.UpdateState
import io.nisfeb.talon.urbit.TlonChatRepo
import io.nisfeb.talon.data.FolderEntity
import io.nisfeb.talon.data.FolderMemberEntity
import io.nisfeb.talon.data.MessageEntity
import io.nisfeb.talon.data.UnreadEntity
import io.nisfeb.talon.ui.Avatar
import io.nisfeb.talon.ui.ContactMap
import io.nisfeb.talon.ui.applyEmojiSpans
import io.nisfeb.talon.ui.FolderAssignmentSheet
import io.nisfeb.talon.ui.RailItem
import io.nisfeb.talon.ui.UpdateBanner
import io.nisfeb.talon.ui.contactMapFlow
import io.nisfeb.talon.update.UpdateStatus
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DmListScreen(
    db: AppDatabase,
    repo: TlonChatRepo,
    drafts: DraftStore,
    updateState: UpdateState,
    onOpenConversation: (whom: String) -> Unit,
    onOpenSearch: () -> Unit,
    onNewMessage: () -> Unit,
    onSignOut: () -> Unit,
    onOpenSelfProfile: () -> Unit,
    onOpenStatusFeed: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenWatchwords: () -> Unit = {},
    onOpenDigest: () -> Unit = {},
    /** Hide the "Today's brief" menu entry when the digest alarm is
     *  disabled in settings — no point routing into a screen the user
     *  hasn't opted into yet. */
    digestEnabled: Boolean = false,
    /** Per-ship persistent "I've seen this" timestamps for the More
     *  menu's freshness dots. Tap-throughs on Today's brief /
     *  Statuses / Invites mark the corresponding entry seen so the
     *  pip clears even though the underlying data is still there.
     *  Defaults to NoopMenuSeenStore for tests / hosts that haven't
     *  wired persistence yet. */
    menuSeen: io.nisfeb.talon.ui.MenuSeenStore =
        io.nisfeb.talon.ui.NoopMenuSeenStore,
    onOpenAdministration: () -> Unit = {},
    onOpenInvites: () -> Unit = {},
    onOpenSettings: () -> Unit,
    /**
     * Items the kebab dropdown should show. App.kt computes this:
     *  - On wide windows: items NOT on the rail (the rail is the
     *    primary surface, the kebab is the overflow tray).
     *  - On compact windows: every item (the rail isn't visible).
     * Sign out is rendered separately and isn't a [RailItem]; it
     * always appears as the final entry in the dropdown.
     */
    kebabItems: Set<RailItem> = RailItem.entries.toSet(),
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
    /** Opens the ship-switcher drawer. The drawer itself lives at the
     *  App.kt level (so it covers the whole window — rail + list +
     *  detail) instead of inside this screen, where on the wide
     *  split-pane layout it would only have covered the list pane and
     *  leak its left-translated closed-state over the rail. Default
     *  no-op for tests / hosts that don't wire a switcher. */
    onOpenShipSwitcher: () -> Unit = {},
    /** Optional slot for the platform-specific battery-exemption nudge
     *  banner. Android wires it to a real Composable; desktop (and
     *  tests) leave it null. Replaces the previous expect/actual
     *  shim that put a no-op on every non-Android target. */
    batteryBanner: (@Composable () -> Unit)? = null,
    /** How channels under each group head sort. Threaded through
     *  from UiSettings so the user's "Recent vs host order" toggle
     *  takes effect without a relaunch. */
    groupChannelOrder: io.nisfeb.talon.ui.GroupChannelOrder =
        io.nisfeb.talon.ui.GroupChannelOrder.Recent,
    /** When set to true by a keyboard-shortcut handler upstream, open
     *  the search screen and reset the flag. DmListScreen has no
     *  inline search field — Ctrl+K navigates to the full SearchScreen
     *  via [onOpenSearch]. */
    focusSearchRequest: Boolean = false,
    onFocusSearchHandled: () -> Unit = {},
    /** When set to true by a keyboard-shortcut handler upstream, open
     *  the New-DM dialog and reset the flag. */
    showNewDmRequest: Boolean = false,
    onShowNewDmHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Per-ship snapshot holder. Binding here swaps the singleton
    // `active` reference so helpers (homeSnapshotZeroUnread) write to
    // the right ship's cache. Stable across recompositions — if the
    // user switches ships the tree re-keys and we get a fresh snap.
    val snap = remember(activeShip) { HomeListSnapshot.bind(activeShip) }

    // Single unreads subscription, derived twice (rows + mentionCounts).
    // Previously the table was collected separately for each, doubling
    // the per-write work. distinctUntilChanged on each Room source
    // suppresses Room's spurious re-emissions on unrelated table
    // writes; the row-build runs on Default so it never lands on Main.
    val rows by remember {
        combine(
            db.messages().conversationLatest().distinctUntilChanged(),
            db.unreads().stream().distinctUntilChanged(),
        ) { messages, unreads ->
            val unreadMap = HashMap<String, Int>(unreads.size)
            for (u in unreads) unreadMap[u.whom] = u.count
            // Defensive: guarantee one row per whom. The DAO query already
            // enforces this, but any future change must not be allowed to
            // reach LazyColumn's duplicate-key crash.
            val seen = HashSet<String>(messages.size)
            val result = ArrayList<Pair<MessageEntity, Int>>(messages.size)
            for (m in messages) {
                if (seen.add(m.whom)) {
                    result.add(m to (unreadMap[m.whom] ?: 0))
                }
            }
            snap.rows = result
            result
        }.flowOn(Dispatchers.Default)
    }.collectAsState(initial = snap.rows)

    // Mention-bearing unread rows from %activity. We collect the full
    // entities (not just counts) so the Mentions tab can render rows
    // for whoms even when the local messages cache hasn't yet seen
    // them — e.g. a reply-mention on a notebook post or a new chat
    // we haven't opened. SQL-side `notifyCount > 0` filter keeps the
    // wire shape small and stops re-emitting on unrelated unread-only
    // updates that don't touch the mention set.
    val mentionUnreads by remember {
        db.unreads().streamWithMentions()
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyList<UnreadEntity>())
    val mentionCounts = remember(mentionUnreads) {
        val out = HashMap<String, Int>(mentionUnreads.size)
        for (u in mentionUnreads) out[u.whom] = u.notifyCount
        out
    }

    val contactMap by remember {
        contactMapFlow(
            db.contacts().stream(),
            db.clubs().stream(),
            db.groups().streamGroups(),
            db.groups().streamChannelGroups(),
        ).onEach { snap.contactMap = it }
    }.collectAsState(initial = snap.contactMap)

    val drafts by drafts.state.collectAsState()
    val updateStatus by updateState.status.collectAsState()

    // distinctUntilChanged on the folder/member/order streams: same
    // Room invalidation-tracker concern as the row flow above. Without
    // it, every messages write was re-emitting the folders membership
    // (which doesn't depend on the messages table) and triggering the
    // membersByWhom / folderRows / homeRows derivations to recompute.
    val folders by remember {
        db.folders().streamFolders()
            .distinctUntilChanged()
            .onEach { snap.folders = it }
    }.collectAsState(initial = snap.folders)
    val members by remember {
        db.folders().streamMembers()
            .distinctUntilChanged()
            .onEach { snap.members = it }
    }.collectAsState(initial = snap.members)
    val groupOrders by remember {
        db.groupOrders().stream()
            .distinctUntilChanged()
            .onEach { snap.groupOrders = it }
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
    // Long-press on a tab chip → "Mark all read" confirmation dialog.
    // Cleared once the user confirms or cancels.
    var confirmMarkAllRead by remember { mutableStateOf(false) }
    var confirmDeleteFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    // Edit mode: when on, rows are drag-reorderable (long-press anywhere
    // on the row picks up the drag) and the long-press action sheet is
    // suppressed. When off, long-press opens the action sheet as normal.
    var editMode by remember { mutableStateOf(false) }

    // Keyboard-shortcut request handlers. focusSearchRequest navigates to
    // the full SearchScreen (no inline search field exists here). showNewDmRequest
    // opens the New-DM dialog via the existing onNewMessage callback.
    LaunchedEffect(focusSearchRequest) {
        if (focusSearchRequest) {
            onOpenSearch()
            onFocusSearchHandled()
        }
    }
    LaunchedEffect(showNewDmRequest) {
        if (showNewDmRequest) {
            onNewMessage()
            onShowNewDmHandled()
        }
    }

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
        groupChannelOrder,
    ) {
        if (selectedFolderId != null) emptyList()
        else buildHomeRows(
            allConvs = rows,
            contactMap = contactMap,
            expandedGroups = expandedGroups,
            allUnreads = allUnreads,
            groupOrderFlags = groupOrders.map { it.flag },
            groupChannelOrder = groupChannelOrder,
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
        groupChannelOrder,
    ) {
        val fid = selectedFolderId ?: return@remember emptyList<HomeRow>()
        val folderMembers = members.filter { it.folderId == fid }
        buildFolderRows(
            members = folderMembers,
            allConvs = rows,
            contactMap = contactMap,
            expandedGroups = expandedGroups,
            allUnreads = allUnreads,
            groupChannelOrder = groupChannelOrder,
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
                repo.settingsSync?.reorderGroupOrdersLocal(order)
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
                repo.settingsSync?.reorderFolderMembersLocal(folderId, order)
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
            // repo.pushScope (not the composition scope) so a
            // rotation or quick navigation away mid-drag doesn't
            // cancel the push before it runs. Local Room reorder
            // is already committed by this point; we just need the
            // ship to learn about it.
            repo.pushScope.launch {
                if (folderId != null) {
                    runCatching { repo.settingsSync?.pushFolderMembersOrder(folderId) }
                }
                if (pushGroups) {
                    runCatching { repo.settingsSync?.pushGroupOrders() }
                }
            }
        }
    }

    Box(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Indeterminate progress strip while the repo is doing its
        // first-run bootstrap (init-posts + activity scries on a
        // freshly-opened ship). Without it, the 10-30s of silent
        // network work on a fresh `~/.config/Talon` reads as a hung
        // app — the user has no way to tell "still loading" from
        // "your ship has no chats." Disappears as soon as the
        // bootstrap completes (success or error).
        val bootstrapping by repo.bootstrapping.collectAsState()
        if (bootstrapping) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Full-color brand mark — keep as `Image` (Icon would tint
            // every non-transparent pixel with the surface color and
            // flatten the multi-color logo into a silhouette).
            androidx.compose.foundation.Image(
                painter = io.nisfeb.talon.ui.talonLogoPainter(),
                contentDescription = "Switch ship",
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onOpenShipSwitcher() },
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
                // Colored dot in the user's chosen accent — same
                // value as `colorScheme.primary` since App.kt's
                // TalonTheme override drives the theme primary
                // with the active ship's color (Profile mode), the
                // user's hex (Custom mode), or brand (off / Brand).
                val accent = MaterialTheme.colorScheme.primary
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
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
            // Freshness-dot inputs. Per-feature data flows joined with
            // the menuSeen state so a tap-through actually clears the
            // dot — leaving "Today's brief" lit 24/7 just because a
            // digest exists for today helps no one (the user said
            // exactly that). The marker writes happen in the per-item
            // onClick branches below.
            val seenState by menuSeen.state.collectAsState()
            val pendingInvites = repo.invitesFlow.collectAsState().value
                ?: emptyList()
            val latestDigest by remember(db, activeShip) {
                db.dailyDigests().streamLatestForShip(activeShip ?: "")
            }.collectAsState(initial = null)
            val statusFeedRows by remember(db) {
                db.contacts().streamStatusFeed()
            }.collectAsState(initial = emptyList())
            val invitesSnapshot = remember(pendingInvites) {
                io.nisfeb.talon.ui.invitesSnapshot(pendingInvites.map { it.flag })
            }
            val hasFreshDigest = latestDigest?.dateLocal?.let {
                it != seenState.lastSeenDigestDate
            } == true
            val hasFreshStatuses = remember(statusFeedRows, seenState.lastSeenStatusesMs, activeShip) {
                statusFeedRows.any { c ->
                    (c.statusUpdatedMs ?: 0L) > seenState.lastSeenStatusesMs &&
                        !c.status.isNullOrBlank() &&
                        c.ship != activeShip
                }
            }
            val hasPendingInvites = pendingInvites.isNotEmpty() &&
                invitesSnapshot != seenState.lastSeenInvitesSnapshot
            // Roll up the three per-item flags into a single hint on
            // the ellipsis itself. When true, the user has *something*
            // worth opening the menu for; the per-item dots inside
            // tell them which entry.
            //
            // Each freshness signal is gated by kebabItems membership
            // — Phase 4 lets the user move items to the rail, and a
            // fresh signal for an item that's NOT in the kebab
            // shouldn't pip the kebab (the user reaches it via the
            // rail instead). The rail itself doesn't carry badges
            // today; that's a separate follow-up.
            val anyMenuBadge =
                (hasFreshDigest && RailItem.TodaysBrief in kebabItems) ||
                (hasFreshStatuses && RailItem.Statuses in kebabItems) ||
                (hasPendingInvites && RailItem.Invites in kebabItems)
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Box {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        if (anyMenuBadge) MenuBadgeDot(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 1.dp, end = 1.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (RailItem.Profile in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("My profile") },
                            onClick = {
                                menuOpen = false
                                onOpenSelfProfile()
                            },
                        )
                    }
                    if (RailItem.Statuses in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Statuses") },
                            trailingIcon = {
                                if (hasFreshStatuses) MenuBadgeDot()
                            },
                            onClick = {
                                menuOpen = false
                                menuSeen.markStatusesSeenAt(System.currentTimeMillis())
                                onOpenStatusFeed()
                            },
                        )
                    }
                    if (RailItem.Bookmarks in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Bookmarks") },
                            onClick = {
                                menuOpen = false
                                onOpenBookmarks()
                            },
                        )
                    }
                    if (RailItem.Activity in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Activity") },
                            onClick = {
                                menuOpen = false
                                onOpenActivity()
                            },
                        )
                    }
                    if (RailItem.Watchwords in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Watchwords") },
                            onClick = {
                                menuOpen = false
                                onOpenWatchwords()
                            },
                        )
                    }
                    if (RailItem.TodaysBrief in kebabItems && digestEnabled) {
                        DropdownMenuItem(
                            text = { Text("Today's brief") },
                            trailingIcon = {
                                if (hasFreshDigest) MenuBadgeDot()
                            },
                            onClick = {
                                menuOpen = false
                                latestDigest?.dateLocal?.let {
                                    menuSeen.markDigestSeen(it)
                                }
                                onOpenDigest()
                            },
                        )
                    }
                    if (RailItem.Administration in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Administration") },
                            onClick = {
                                menuOpen = false
                                onOpenAdministration()
                            },
                        )
                    }
                    if (RailItem.Invites in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Invites") },
                            trailingIcon = {
                                if (hasPendingInvites) MenuBadgeDot()
                            },
                            onClick = {
                                menuOpen = false
                                menuSeen.markInvitesSeen(invitesSnapshot)
                                onOpenInvites()
                            },
                        )
                    }
                    if (RailItem.Settings in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuOpen = false
                                onOpenSettings()
                            },
                        )
                    }
                    // Sign out is always rendered last, never gated by kebabItems —
                    // it's not a RailItem and never lives on the rail.
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
        batteryBanner?.invoke()
        val totalUnread = remember(unreadRows) { unreadRows.sumOf { it.second } }
        val totalMentions = remember(mentionUnreads) {
            mentionUnreads.sumOf { it.notifyCount }
        }
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
            onLongPress = { f ->
                // Edit-mode long-press still opens rename (the
                // pencil affordance fires the same handler). Outside
                // edit mode, long-press is the "mark all read"
                // shortcut so a freshly-installed client can clear
                // a backlog of stale unreads in one tap.
                if (editMode) renamingFolder = f
                else confirmMarkAllRead = true
            },
            onSpecialLongPress = { confirmMarkAllRead = true },
            onCreateNew = { creatingFolder = true },
        )
        HorizontalDivider()
        UpdateBanner(
            status = updateStatus,
            onTap = {
                when (val s = updateStatus) {
                    is UpdateStatus.Available ->
                        updateState.startDownload(s.manifest)
                    is UpdateStatus.Ready ->
                        updateState.launchInstaller(s.apkPath)
                    is UpdateStatus.Failed -> {
                        val m = s.manifest ?: return@UpdateBanner
                        updateState.startDownload(m)
                    }
                    else -> Unit
                }
            },
            onDismiss = { updateState.dismiss() },
        )
        // Mentions-tab list: drive off the unreads table directly, not
        // the messages-derived `rows`. A whom can carry notifyCount > 0
        // (drives the badge total) without having any cached message —
        // e.g. a reply-mention on a notebook post, or a new chat. The
        // older filter-against-`rows` approach hid those rows, so the
        // badge counted mentions the tab couldn't show. Pair each
        // entity with its cached MessageEntity when one exists; the
        // renderer falls back to a contact-only placeholder otherwise.
        val rowsByWhom = remember(rows) {
            rows.associate { it.first.whom to it.first }
        }
        val mentionRows = remember(mentionUnreads, rowsByWhom) {
            mentionUnreads
                .sortedByDescending { it.recencyMs }
                .map { u -> u to rowsByWhom[u.whom] }
        }
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
                // `mentionRows` is hoisted above the LazyColumn so the
                // memoization works (LazyListScope isn't a Composable).
                // Each row is (UnreadEntity, MessageEntity?); the second
                // element is null when we haven't cached any message
                // for that whom yet, in which case we render a
                // placeholder so the user can still tap through.
                if (mentionRows.isEmpty()) {
                    item(key = "__mentions_empty") {
                        SpecialEmpty("No mentions.")
                    }
                } else {
                    items(
                        items = mentionRows,
                        key = { (u, _) -> "men:${u.whom}" },
                        contentType = { "men" },
                    ) { (u, m) ->
                        if (m != null) {
                            ConversationRow(
                                m = m,
                                unread = u.notifyCount,
                                contactMap = contactMap,
                                draft = drafts[u.whom],
                                onClick = onRowOpen,
                                onLongClick = onRowLongPress,
                            )
                        } else {
                            MentionPlaceholderRow(
                                whom = u.whom,
                                unread = u.notifyCount,
                                recencyMs = u.recencyMs,
                                contactMap = contactMap,
                                onClick = onRowOpen,
                                onLongClick = onRowLongPress,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            } else if (selectedFolderId != null) {
                // Folder view — user's curated mix of group heads (with
                // collapsible children) and loose conversation rows.
                // GroupHead + its consecutive GroupChild rows are
                // bundled into a single lazy item with an
                // AnimatedVisibility wrapping the children, so
                // expansion clips the children to the animated height
                // and the next item below relayouts smoothly without
                // a placement-animation lag (which caused visible
                // overlap during expansion).
                var i = 0
                while (i < folderRows.size) {
                    val row = folderRows[i]
                    when (row) {
                        is HomeRow.Header -> {
                            item(key = row.key, contentType = "Header") {
                                SectionHeader(row.label)
                            }
                            i++
                        }
                        is HomeRow.GroupHead -> {
                            val children = mutableListOf<HomeRow.GroupChild>()
                            var j = i + 1
                            while (j < folderRows.size) {
                                val next = folderRows[j]
                                if (next is HomeRow.GroupChild && next.groupFlag == row.flag) {
                                    children += next
                                    j++
                                } else break
                            }
                            val childrenSnapshot = children.toList()
                            item(key = row.key, contentType = "GroupHead") {
                                ReorderableItem(reorderState, key = row.key) { _ ->
                                    Column {
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
                                        AnimatedVisibility(
                                            visible = row.expanded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut(),
                                        ) {
                                            Column {
                                                childrenSnapshot.forEach { child ->
                                                    GroupChannelRow(
                                                        whom = child.whom,
                                                        m = child.m,
                                                        unread = child.unread,
                                                        contactMap = contactMap,
                                                        draft = drafts[child.whom],
                                                        onClick = onRowOpen,
                                                        onLongClick = onRowLongPress,
                                                    )
                                                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            i = j
                        }
                        is HomeRow.GroupChild -> {
                            // Consumed by the preceding GroupHead above.
                            i++
                        }
                        is HomeRow.Flat -> {
                            item(key = row.key, contentType = "Flat") {
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
                            i++
                        }
                    }
                }
            } else {
                // All view — structured home list. See folder-view
                // branch above for why GroupHead bundles its children
                // into a single lazy item with AnimatedVisibility.
                var i = 0
                while (i < homeRows.size) {
                    val row = homeRows[i]
                    when (row) {
                        is HomeRow.Header -> {
                            item(key = row.key, contentType = "Header") {
                                SectionHeader(row.label)
                            }
                            i++
                        }
                        is HomeRow.GroupHead -> {
                            val children = mutableListOf<HomeRow.GroupChild>()
                            var j = i + 1
                            while (j < homeRows.size) {
                                val next = homeRows[j]
                                if (next is HomeRow.GroupChild && next.groupFlag == row.flag) {
                                    children += next
                                    j++
                                } else break
                            }
                            val childrenSnapshot = children.toList()
                            item(key = row.key, contentType = "GroupHead") {
                                ReorderableItem(reorderState, key = row.key) { _ ->
                                    Column {
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
                                        AnimatedVisibility(
                                            visible = row.expanded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut(),
                                        ) {
                                            Column {
                                                childrenSnapshot.forEach { child ->
                                                    GroupChannelRow(
                                                        whom = child.whom,
                                                        m = child.m,
                                                        unread = child.unread,
                                                        contactMap = contactMap,
                                                        draft = drafts[child.whom],
                                                        onClick = onRowOpen,
                                                        onLongClick = onRowLongPress,
                                                    )
                                                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            i = j
                        }
                        is HomeRow.GroupChild -> {
                            // Consumed by the preceding GroupHead above.
                            i++
                        }
                        is HomeRow.Flat -> {
                            item(key = row.key, contentType = "Flat") {
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
                            i++
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
                    if (checked) repo.settingsSync?.addFolderMember(folder.id, whom)
                    else repo.settingsSync?.removeFolderMember(folder.id, whom)
                }
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = repo.settingsSync?.createFolder(name, folders.size)
                    if (id != null) repo.settingsSync?.addFolderMember(id, whom)
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
                    if (checked) repo.settingsSync?.addGroupToFolder(folder.id, flag)
                    else repo.settingsSync?.removeGroupFromFolder(folder.id, flag)
                }
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = repo.settingsSync?.createFolder(name, folders.size)
                    if (id != null) repo.settingsSync?.addGroupToFolder(id, flag)
                }
            },
            onDismiss = { folderSheetGroup = null },
            onMarkGroupRead = {
                // Resolve the group's channels to whoms and mark all
                // read in parallel. We pull from contactMap (which
                // already has the channel list per group) so we don't
                // need an extra DAO round-trip.
                val whoms = contactMap.channelGroups
                    .filter { it.groupFlag == flag }
                    .map { it.nest }
                scope.launch { runCatching { repo.markAllRead(whoms) } }
            },
            onLeaveGroup = {
                scope.launch { runCatching { repo.leaveGroup(flag) } }
            },
        )
    }

    if (confirmMarkAllRead) {
        // Compute the unread set right at confirm time so the dialog
        // count reflects the current snapshot. We mark every whom in
        // `rows` with unread > 0 — the user's stated goal is clearing
        // a backlog of stale unreads, and pokes against zero-unread
        // whoms are wasted work.
        val unreadWhoms = rows.filter { (_, count) -> count > 0 }
            .map { (m, _) -> m.whom }
        AlertDialog(
            onDismissRequest = { confirmMarkAllRead = false },
            title = { Text("Mark all as read?") },
            text = {
                Text(
                    if (unreadWhoms.isEmpty()) "No unread chats."
                    else "Clears the unread badge on ${unreadWhoms.size} " +
                        if (unreadWhoms.size == 1) "chat." else "chats.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = unreadWhoms.isNotEmpty(),
                    onClick = {
                        confirmMarkAllRead = false
                        scope.launch {
                            runCatching { repo.markAllRead(unreadWhoms) }
                        }
                    },
                ) { Text("Mark read") }
            },
            dismissButton = {
                TextButton(onClick = { confirmMarkAllRead = false }) { Text("Cancel") }
            },
        )
    }

    renamingFolder?.let { folder ->
        FolderRenameDialog(
            folder = folder,
            onRename = { newName ->
                scope.launch { repo.settingsSync?.renameFolder(folder.id, newName) }
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
                        repo.settingsSync?.deleteFolder(folder.id)
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
                    repo.settingsSync?.createFolder(name, folders.size)
                }
                creatingFolder = false
            },
            onDismiss = { creatingFolder = false },
        )
    }
}

@Composable
internal fun ShipSwitcherDrawer(
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
                    androidx.compose.foundation.Image(
                        painter = io.nisfeb.talon.ui.talonLogoPainter(),
                        contentDescription = null,
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
    /** Long-press on the All / Unread / Mentions special tabs.
     *  Same intent as [onLongPress] for folder chips — surfaces the
     *  "mark all read" affordance regardless of which tab the user
     *  reached for. */
    onSpecialLongPress: () -> Unit,
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
                    onLongClick = onSpecialLongPress,
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
                    onLongClick = onSpecialLongPress,
                )
            }
        }
        item(key = "__all") {
            TabChip(
                text = "All",
                selected = specialActive && selectedSpecial == SpecialTab.All,
                onClick = { onSelectSpecial(SpecialTab.All) },
                onLongClick = onSpecialLongPress,
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
            .combinedClickableWithSecondary(
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
        Modifier.combinedClickableWithSecondary(
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
                    preview.applyEmojiSpans(),
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

// Mentions tab fallback row for whoms we have no cached MessageEntity
// for. Renders the same shape as ConversationRow (avatar, title,
// channel-type badge, recency, unread count) minus the message
// preview. Tapping still routes through onClick so the user can open
// the chat and let the messages cache catch up.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MentionPlaceholderRow(
    whom: String,
    unread: Int,
    recencyMs: Long,
    contactMap: ContactMap,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
) {
    val title = remember(whom, contactMap) { contactMap.conversationLabel(whom) }
    val timestamp = remember(recencyMs) {
        if (recencyMs > 0L) formatRelative(recencyMs) else ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickableWithSecondary(
                onClick = { onClick(whom) },
                onLongClick = { onLongClick(whom) },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(
            label = title,
            url = contactMap.conversationAvatar(whom),
            colorHex = contactMap.conversationColor(whom),
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
                ChannelTypeBadge(whom)
            }
            if (timestamp.isNotEmpty()) {
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}

// java.time.format.DateTimeFormatter is immutable and thread-safe;
// SimpleDateFormat is neither, and chat-list rebuilds happen
// concurrently from multiple flow emissions on Dispatchers.Default —
// the SDF version was a thread-safety bug waiting to fire.
/**
 * 8.dp filled dot in the theme's primary color — used as a "fresh
 * content" cue on the More-menu trailing slot and as a corner badge
 * on the ellipsis IconButton itself. Keeps the ellipsis's own glyph
 * intact (no overlay over the dots) by aligning to TopEnd rather
 * than centering.
 */
@Composable
private fun MenuBadgeDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

private val TIME_TODAY: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
private val DATE_OLD: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())

private fun formatRelative(ms: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ms
    val instant = java.time.Instant.ofEpochMilli(ms)
    return when {
        diff < 24 * 3600_000L -> TIME_TODAY.format(instant)
        else -> DATE_OLD.format(instant)
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
        Modifier.combinedClickableWithSecondary(
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
            // Always the down chevron; rotation alone communicates state
            // (0° expanded = points down, -90° collapsed = points right).
            // Swapping the icon AND rotating produced an up-pointing arrow
            // when expanded.
            imageVector = Icons.Filled.ExpandMore,
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
            .combinedClickableWithSecondary(
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
                    "${authorLabel ?: ""} · $timestamp · $preview".applyEmojiSpans(),
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
    // flag and would double-report. Indices are LazyColumn item
    // indices; GroupChild rows share their parent GroupHead's lazy
    // index since the head + its children render as one bundled item.
    val unreadIndices = remember(homeRows) {
        val expandedFlags = homeRows.asSequence()
            .filterIsInstance<HomeRow.GroupHead>()
            .filter { it.expanded }
            .map { it.flag }
            .toSet()
        val rowToLazy = IntArray(homeRows.size)
        var lazyIdx = -1
        homeRows.forEachIndexed { i, row ->
            if (row is HomeRow.GroupChild) {
                rowToLazy[i] = lazyIdx
            } else {
                lazyIdx++
                rowToLazy[i] = lazyIdx
            }
        }
        homeRows.mapIndexedNotNull { i, row ->
            val hasUnread = when (row) {
                is HomeRow.Flat -> row.unread > 0
                // Children of collapsed groups are hidden in the
                // renderer; the head's totalUnread already covers
                // them, so don't double-count here.
                is HomeRow.GroupChild -> row.unread > 0 && row.groupFlag in expandedFlags
                is HomeRow.GroupHead -> row.totalUnread > 0 && row.flag !in expandedFlags
                is HomeRow.Header -> false
            }
            if (hasUnread) rowToLazy[i] else null
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
