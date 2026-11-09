package io.nisfeb.talon.ui.screens
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.nisfeb.talon.data.latestPerConversation
import io.nisfeb.talon.ui.reorderHandle
import kotlin.concurrent.Volatile
import io.nisfeb.talon.util.ConcurrentMap
import io.nisfeb.talon.util.formatMonthDay
import io.nisfeb.talon.util.formatClock
import io.nisfeb.talon.util.nowMs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import io.nisfeb.talon.ui.combinedClickableWithSecondary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.nisfeb.talon.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.nisfeb.talon.update.UpdateStatus
import io.nisfeb.talon.ui.MentionMatcher
import io.nisfeb.talon.urbit.StoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import io.nisfeb.talon.ui.icons.TalonIcons

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DmListScreen(
    db: AppDatabase,
    /** Orrery is on; off, its tray of noticed claims is not shown. */
    orreryOn: Boolean = true,
    repo: TlonChatRepo,
    drafts: DraftStore,
    updateState: UpdateState,
    onOpenConversation: (whom: String) -> Unit,
    onOpenSearch: () -> Unit,
    /** Opt-in assistant entry point. Null hides the affordance — passed
     *  non-null only when the "Ask your Urbit" feature is on and a key
     *  is configured, so it stays invisible during rc rollout. */
    onOpenAssistant: (() -> Unit)? = null,
    onNewMessage: () -> Unit,
    onOpenSelfProfile: () -> Unit,
    onOpenStatusFeed: () -> Unit,
    onOpenMail: () -> Unit = {},
    partyLinesTab: (@Composable () -> Unit)? = null,
    partyLinesOccupied: Boolean = false,
    /** The followed-threads collection, shown as a conditional tab when the
     *  owner follows at least one thread (null otherwise). [threadsUnread]
     *  counts the followed threads with new replies, for the tab label. */
    threadsTab: (@Composable () -> Unit)? = null,
    threadsUnread: Int = 0,
    onOpenBookmarks: () -> Unit,
    onOpenActivity: () -> Unit,
    onOpenCalendar: () -> Unit = {},
    /** Open the curated Contacts (book) screen. Always shown in the
     *  overflow menu (not a rail item). */
    onOpenContacts: () -> Unit = {},
    /** Open the home page. Null where the host has no Home surface,
     *  which hides the entry rather than offering a dead one. */
    onOpenHome: (() -> Unit)? = null,
    /** Open Orrery's actions. Null where Orrery is off. */
    onOpenActions: (() -> Unit)? = null,
    /** Per-ship persistent "I've seen this" timestamps for the More
     *  menu's freshness dots. Tap-throughs on Statuses / Invites mark
     *  the corresponding entry seen so the pip clears even though the
     *  underlying data is still there.
     *  Defaults to NoopMenuSeenStore for tests / hosts that haven't
     *  wired persistence yet. */
    menuSeen: io.nisfeb.talon.ui.MenuSeenStore =
        io.nisfeb.talon.ui.NoopMenuSeenStore,
    onOpenAdministration: () -> Unit = {},
    onOpenInvites: () -> Unit = {},
    onOpenSettings: () -> Unit,
    /** Opens the screen that chooses the sidebar's and kebab's
     *  contents; offered from the kebab, where a missing section is
     *  noticed. Null where the host has no such screen. */
    onOpenSidebarSettings: (() -> Unit)? = null,
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
    /** Signs the lapsed login in again, from the sync status ([io.nisfeb.talon.ui.SyncStatusBanner]); null offers none. */
    onSignIn: (() -> Unit)? = null,
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
    /** When a detail pane sits beside this list (the wide split
     *  layout), a jump to a group from elsewhere also opens its most
     *  recently active channel there. A tap on a card only unfolds
     *  it. Off by default because on compact layouts "open"
     *  navigates away from the list the user was in the middle of
     *  browsing. */
    autoOpenOnExpand: Boolean = false,
    /** The conversation open beside the list, whose row keeps a soft
     *  accent for as long as it has the focus. Null where the chat
     *  replaces the list rather than sitting beside it. */
    openWhom: String? = null,
    /** How the home list's Groups section and any custom folder's
     *  top-level entries sort. Same UiSettings-driven plumbing as
     *  [groupChannelOrder]. */
    folderItemOrder: io.nisfeb.talon.ui.FolderItemOrder =
        io.nisfeb.talon.ui.FolderItemOrder.Manual,
    /** When set to true by a keyboard-shortcut handler upstream, open
     *  the search screen and reset the flag. DmListScreen has no
     *  inline search field — Ctrl+K navigates to the full SearchScreen
     *  via [onOpenSearch]. */
    focusSearchRequest: Boolean = false,
    onFocusSearchHandled: () -> Unit = {},
    /** When non-null (set after tapping a group in search), switch to
     *  the main home view, expand that group's channels, and scroll
     *  it into view. Cleared via [onRevealGroupHandled] once done. */
    revealGroupFlag: String? = null,
    onRevealGroupHandled: () -> Unit = {},
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
    // Pending DM requests — strangers who opened a DM we haven't accepted.
    // Rendered as a "Requests" section at the top of the list.
    val allDmInvites by remember { db.dmInvites().stream() }
        .collectAsState(initial = emptyList())
    // With only people added (2026-11-08), anyone else's request folds under
    // one line, closed, below the rest: there, not in the way.
    val reach = io.nisfeb.talon.ui.LocalUiSettings.current?.reach?.collectAsState()?.value ?: io.nisfeb.talon.ui.ReachSettings()
    val book by repo.bookContacts.collectAsState()
    val (dmInvites, heldInvites) = remember(allDmInvites, reach, book) {
        if (reach.whoCanMessage == io.nisfeb.talon.ui.WhoCanMessage.Anyone) allDmInvites to emptyList()
        else allDmInvites.partition { it.ship in book || it.ship in reach.favourites }
    }
    var heldOpen by remember { mutableStateOf(false) }
    // What the orrery triage noticed in what people said, waiting for a word.
    val noticed by remember(activeShip, orreryOn) {
        activeShip?.takeIf { orreryOn }?.let { db.orreryNoticed().pending(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    val rows by remember {
        combine(
            db.latestPerConversation().distinctUntilChanged(),
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
    }.collectAsStateWithLifecycle(initialValue = snap.rows)

    // Mention-bearing unread rows from %activity. We collect the full
    // entities (not just counts) so the Mentions tab can render rows
    // for whoms even when the local messages cache hasn't yet seen
    // them — e.g. a reply-mention on a notebook post or a new chat
    // we haven't opened. SQL-side `notifyCount > 0` filter keeps the
    // wire shape small and stops re-emitting on unrelated unread-only
    // updates that don't touch the mention set.
    val notifyUnreads by remember {
        db.unreads().streamWithMentions()
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = emptyList<UnreadEntity>())

    // True-mention filter: %activity's `notify-count` covers both
    // @-mentions and replies to our own posts. Users only want true
    // @-mentions in the Mentions tab, so filter `notifyUnreads` down
    // to whoms whose recent local messages actually contain a
    // patp-bounded `~ourpatp` reference. Falls back to "include" when
    // we have zero cached messages for the whom — otherwise a new
    // mention ping on a fresh chat would be hidden until the user
    // fetched messages.
    var mentionUnreads by remember { mutableStateOf<List<UnreadEntity>>(emptyList()) }
    LaunchedEffect(notifyUnreads, activeShip) {
        val patp = activeShip?.removePrefix("~")
        if (patp.isNullOrBlank()) {
            mentionUnreads = notifyUnreads.filter { it.mentions > 0 }
            return@LaunchedEffect
        }
        // Off the main thread: up to 50 posts a chat, every unread change.
        mentionUnreads = kotlinx.coroutines.withContext(Dispatchers.Default) {
            val filtered = mutableListOf<UnreadEntity>()
            for (u in notifyUnreads) {
                if (u.mentions == 0) continue
                // A scan the next emission replaced must stop, not read as
                // nothing cached: plain runCatching showed the stale list.
                val recent = io.nisfeb.talon.util.runSuspendCatching {
                    db.messages().latestAnyFor(u.whom, MENTION_SCAN_LIMIT)
                }.getOrDefault(emptyList())
                val include = if (recent.isEmpty()) {
                    true
                } else {
                    recent.any { m -> io.nisfeb.talon.ui.MentionMatcher.mentionsIn(m.contentJson, patp) }
                }
                if (include) filtered.add(u)
            }
            filtered
        }
    }
    val mentionCounts = remember(mentionUnreads) {
        val out = HashMap<String, Int>(mentionUnreads.size)
        for (u in mentionUnreads) out[u.whom] = u.notifyCount
        out
    }

    val contactMap by io.nisfeb.talon.ui.rememberContactMap(db)

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
    // What the menu's "Invite a friend" did, shown for a moment under the header.
    var inviteNote by remember { mutableStateOf<String?>(null) }
    var selectedFolderId by remember {
        mutableStateOf(snap.selectedFolderId)
    }
    var selectedSpecial by remember {
        mutableStateOf(snap.selectedSpecial)
    }
    var initialTabApplied by remember {
        mutableStateOf(snap.initialTabApplied)
    }
    var selectedHomeTab by remember {
        // A persisted party tab is only restorable where the caller
        // supplies the pane; elsewhere (tests, screenshots) fall back
        // to Groups rather than strand the list on a hidden tab.
        mutableStateOf(
            if (partyLinesTab == null && snap.selectedHomeTab == HomeTab.PartyLines) HomeTab.Groups
            else if (threadsTab == null && snap.selectedHomeTab == HomeTab.Threads) HomeTab.Groups
            else snap.selectedHomeTab
        )
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
    LaunchedEffect(selectedFolderId, selectedSpecial, initialTabApplied, selectedHomeTab) {
        snap.selectedFolderId = selectedFolderId
        snap.selectedSpecial = selectedSpecial
        snap.initialTabApplied = initialTabApplied
        snap.selectedHomeTab = selectedHomeTab
    }
    var folderSheetWhom by remember { mutableStateOf<String?>(null) }
    // Separate state for group-kind folder assignment — different write path.
    var folderSheetGroup by remember { mutableStateOf<String?>(null) }
    var renamingFolder by remember { mutableStateOf<FolderEntity?>(null) }
    // Long-press on a tab chip → "Mark all read" confirmation dialog.
    // Cleared once the user confirms or cancels. A folder chip carries
    // its folder through so only that folder's chats are cleared —
    // unread state is unrecoverable once poked, so scope matters.
    var confirmMarkAllRead by remember { mutableStateOf(false) }
    var confirmMarkFolderRead by remember { mutableStateOf<FolderEntity?>(null) }
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
        groupChannelOrder, folderItemOrder,
    ) {
        if (selectedFolderId != null) emptyList()
        else buildHomeRows(
            allConvs = rows,
            contactMap = contactMap,
            expandedGroups = expandedGroups,
            allUnreads = allUnreads,
            groupOrderFlags = groupOrders.map { it.flag },
            groupChannelOrder = groupChannelOrder,
            folderItemOrder = folderItemOrder,
        )
    }
    // Snapshot the effective group order at this moment so the reorder
    // callback can compute new orderings without needing to replicate
    // buildHomeRows' sorting logic.
    val effectiveGroupOrder = remember(homeRows) {
        homeRows.filterIsInstance<HomeRow.GroupHead>().map { it.flag }
    }
    // The All view is two tabs, not two stacked sections. The tabs are
    // the headers, so the rows lose theirs; everything else about the
    // home list — ordering, expansion, reorder — is unchanged.
    val visibleHomeRows = remember(homeRows, selectedHomeTab) {
        when (selectedHomeTab) {
            HomeTab.Groups -> homeRows.filter { it is HomeRow.GroupHead || it is HomeRow.GroupChild }
            HomeTab.Dms -> homeRows.filterIsInstance<HomeRow.Flat>()
            HomeTab.PartyLines -> emptyList()
            HomeTab.Threads -> emptyList()
        }
    }
    val groupsUnread = remember(homeRows) {
        homeRows.filterIsInstance<HomeRow.GroupHead>().sumOf { it.totalUnread }
    }
    val dmsUnread = remember(homeRows) {
        homeRows.filterIsInstance<HomeRow.Flat>().sumOf { it.unread }
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
        groupChannelOrder, folderItemOrder,
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
            folderItemOrder = folderItemOrder,
        )
    }

    // Reuse a singleton LazyListState so the user's scroll position in
    // the home list survives navigating into a chat and back out.
    val listState = snap.listState
    // At the top, what moves up stays in view. A conversation that moved
    // above the first row with new messages landed half out of sight: five
    // unread in one group, and only the jump chip said so. Off the top while
    // the row that was first is still on screen, and nobody scrolled: the
    // list goes back to the top. Any scroll of the user's own ends it.
    LaunchedEffect(listState) {
        var topKey: Any? = null
        snapshotFlow {
            // The position from firstVisibleItemIndex: the visible items
            // include one sitting in the top padding, above the real first.
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) to
                listState.layoutInfo.visibleItemsInfo
        }.collect { (at, visible) ->
            val (index, offset, scrolling) = at
            when {
                scrolling -> topKey = null
                index == 0 && offset == 0 -> topKey = visible.firstOrNull { it.index == 0 }?.key
                // Requested, not scrolled: this can run inside a layout pass.
                topKey != null && visible.any { it.key == topKey } -> listState.requestScrollToItem(0)
                else -> topKey = null
            }
        }
    }

    // Leaving a tab remembers where it was; arriving restores it.
    val switchHomeTab: (HomeTab) -> Unit = { tab ->
        if (tab != selectedHomeTab) {
            val here = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            when (selectedHomeTab) {
                HomeTab.Groups -> snap.groupsScroll = here
                HomeTab.Dms -> snap.dmsScroll = here
                HomeTab.PartyLines -> Unit
                HomeTab.Threads -> Unit
            }
            selectedHomeTab = tab
        }
    }
    // Only on a change of tab. Coming back from a chat is not one: the
    // list state outlived the chat and still stands where it was, and
    // restoring here put it back to where the tab was last *left* --
    // the top, for anyone who never switched tabs.
    var restoredTab by remember { mutableStateOf(selectedHomeTab) }
    LaunchedEffect(selectedHomeTab) {
        if (selectedHomeTab == restoredTab) return@LaunchedEffect
        restoredTab = selectedHomeTab
        val saved = when (selectedHomeTab) {
            HomeTab.Groups -> snap.groupsScroll
            HomeTab.Dms -> snap.dmsScroll
            HomeTab.PartyLines -> null
            HomeTab.Threads -> null
        } ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(saved.first, saved.second) }
    }

    // Reveal-a-group from search. Re-keyed on homeRows so it settles
    // across the recompositions caused by switching to the All view
    // and expanding: each pass either advances one step (switch view →
    // return; then expand + scroll) or no-ops. Setting expandedGroups
    // to a structurally-equal set is a no-op under mutableStateOf's
    // default equality, so there's no re-run loop.
    // Keyed on the view selectors as well: the first pass switches the
    // view and returns, and only a re-run on that change can do the
    // expand and scroll. Keyed on the rows alone, the first tap from
    // search switched the tab and stopped, and only a second tap landed.
    androidx.compose.runtime.LaunchedEffect(revealGroupFlag, homeRows, selectedFolderId, selectedSpecial, selectedHomeTab) {
        val flag = revealGroupFlag ?: return@LaunchedEffect
        if (selectedFolderId != null || selectedSpecial != SpecialTab.All ||
            selectedHomeTab != HomeTab.Groups
        ) {
            selectedFolderId = null
            selectedSpecial = SpecialTab.All
            selectedHomeTab = HomeTab.Groups
            return@LaunchedEffect
        }
        expandedGroups = expandedGroups + flag
        // Same landing as a manual expand: on a wide layout the group's
        // most recent channel opens beside the list and glows.
        if (autoOpenOnExpand && !editMode) {
            homeRows.filterIsInstance<HomeRow.GroupChild>()
                .filter { it.groupFlag == flag }
                .maxByOrNull { it.m?.sentMs ?: 0L }
                ?.let { onRowOpen(it.whom) }
        }
        // Lazy-item index of the group head: each Header / GroupHead /
        // Flat is one item; GroupChild rows are bundled into their
        // head's item, so they don't count. -1 when the group isn't in
        // the list (not a member / not synced) — clear and give up.
        var idx = -1
        var count = 0
        for (r in visibleHomeRows) {
            when (r) {
                is HomeRow.GroupHead -> {
                    if (r.flag == flag) { idx = count; break }
                    count++
                }
                is HomeRow.Header, is HomeRow.Flat -> count++
                is HomeRow.GroupChild -> Unit
            }
        }
        if (idx >= 0) runCatching { listState.animateScrollToItem(idx) }
        onRevealGroupHandled()
    }
    // Tracks what was touched during a drag so onDragStopped knows what
    // to push to %settings. Null means "nothing pending".
    var pendingPushFolderId by remember { mutableStateOf<Long?>(null) }
    var pendingPushGroupOrders by remember { mutableStateOf(false) }

    // Reordering is only real when the list is in manual order and
    // there's a settings sink to persist it. Under "Most recent" the
    // list re-sorts the instant you let go, and with no sink the write
    // is dropped — either way the grip promises something that can't
    // happen, so it isn't shown (bug: drag handles on the All list).
    val canReorder =
        repo.settingsSync != null &&
            folderItemOrder == io.nisfeb.talon.ui.FolderItemOrder.Manual

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

    // The search bar stays tucked away until pulled for: past the top of
    // the list, by a finger or the wheel. See [io.nisfeb.talon.ui.RevealOnPull].
    val searchBarPx = with(androidx.compose.ui.platform.LocalDensity.current) { SEARCH_BAR_HEIGHT.toPx() }
    val searchReveal = remember(searchBarPx) { io.nisfeb.talon.ui.RevealOnPull(searchBarPx) }
    Box(modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
    Column(modifier = Modifier.fillMaxSize().nestedScroll(searchReveal)) {
        // Indeterminate progress strip while the repo is doing its
        // first-run bootstrap (init-posts + activity scries on a
        // freshly-opened ship). Without it, the 10-30s of silent
        // network work on a fresh `~/.config/Talon` reads as a hung
        // app — the user has no way to tell "still loading" from
        // "your ship has no chats." Disappears as soon as the
        // bootstrap completes (success or error).
        //
        // A text caption sits next to the bar so the loading state is
        // obvious — bare progress strips at the screen edge are easy
        // to miss, and users were restarting the app thinking groups
        // hadn't loaded when really they just needed to wait for the
        // (slower) groups scry to finish.
        val bootstrapping by repo.bootstrapping.collectAsState()
        if (bootstrapping) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    "Syncing chats and groups…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
                // A screen reader cannot pull the list: search is offered here.
                .semantics {
                    customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("Search") { onOpenSearch(); true })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The hamburger takes the left, where the logo used to be.
            // Nothing on desktop: the rail is already the way to every
            // section, and the name of the app is not news to anybody
            // running it.
            io.nisfeb.talon.ui.NavIcon(onBack = null)
            if (allShips.size > 1 && activeShip != null) {
                // Prefer nickname, but collapse to patp when more than
                // one logged-in ship shares the same nickname so the
                // label actually disambiguates.
                val activeNick = shipNicknames[activeShip]
                val collision = activeNick != null &&
                    allShips.count { shipNicknames[it] == activeNick } > 1
                // Falling back to the @p made this the one place a
                // comet still showed its fifty-six characters. It falls
                // back to the word name now, and to the unabridged one
                // where two signed-in ships would otherwise read alike,
                // since a label that cannot tell them apart is the
                // thing this branch exists to avoid.
                val label = when {
                    activeNick != null && !collision -> activeNick
                    // A sign-out or forget can drop the active ship from
                    // the list mid-compose; getValue would throw there.
                    else -> io.nisfeb.talon.ui.shipHandles(allShips)[activeShip] ?: activeShip
                }
                // No dot before the name: an accent-coloured dot here
                // said nothing (removed 2026-11-05).
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    // Gives way to the buttons in a narrow list pane.
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            // Whether this device hears from its ship: a Windows user's sync
            // stopped for three days with nothing to say so (2026-10-29).
            activeShip?.let { io.nisfeb.talon.ui.SyncStatusIcon(repo, it, onSignIn) }
            // Assistant moved off the top bar — it's now a rail / kebab item
            // ("A"), unifying it with the other panel-based features.
            // The pencil starts a new chat or group (it was a reorder
            // toggle nobody could read; the + button it replaces is gone).
            // While reordering, opened from the menu, it is the way out.
            if (editMode) {
                io.nisfeb.talon.ui.IconButton(tip = "Finish reordering", onClick = { editMode = false }) {
                    Icon(
                        imageVector = Icons.Filled.Done,
                        contentDescription = "Finish reordering",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // The search bar is pulled out of the list on a touch
                // screen; a wheel past the top never reaches it, so a
                // pointer gets search here, as before the bar.
                if (!io.nisfeb.talon.ui.isTouchPrimary) {
                    io.nisfeb.talon.ui.IconButton(tip = "Search", onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search messages")
                    }
                }
                // A phone has no More menu, where reordering moved: its
                // way in sits beside the pencil, ungated as the pencil was.
                if (io.nisfeb.talon.ui.isDrawerNavigation) {
                    io.nisfeb.talon.ui.IconButton(tip = "Reorder", onClick = { editMode = true }) {
                        Icon(io.nisfeb.talon.ui.icons.TalonIcons.DragHandle, contentDescription = "Reorder")
                    }
                }
                io.nisfeb.talon.ui.IconButton(tip = "New chat or group", onClick = onNewMessage) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .border(1.5.dp, LocalContentColor.current, RoundedCornerShape(8.dp)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "New message",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            var menuOpen by remember { mutableStateOf(false) }
            var peopleOpen by remember(menuOpen) { mutableStateOf(false) }
            val inviteFriend = io.nisfeb.talon.ui.rememberInviteFriend(activeShip)
            // Freshness-dot inputs. Per-feature data flows joined with
            // the menuSeen state so a tap-through actually clears the
            // dot. The marker writes happen in the per-item onClick
            // branches below.
            val seenState by menuSeen.state.collectAsState()
            // Status-feed "seen" syncs across the user's devices via
            // %settings; merge that high-water mark with the local one so
            // viewing statuses on any device clears the fresh dot on all.
            val statusesSeenFlow = remember(repo) {
                repo.settingsSync?.statusesSeenMs
                    ?: kotlinx.coroutines.flow.MutableStateFlow(0L)
            }
            val syncedStatusesSeenMs by statusesSeenFlow.collectAsState()
            val pendingInvites = repo.invitesFlow.collectAsState().value
                ?: emptyList()
            val statusFeedRows by remember(db) {
                db.contacts().streamStatusFeed()
            }.collectAsState(initial = emptyList())
            val invitesSnapshot = remember(pendingInvites) {
                io.nisfeb.talon.ui.invitesSnapshot(pendingInvites.map { it.flag })
            }
            val effectiveStatusesSeenMs =
                maxOf(seenState.lastSeenStatusesMs, syncedStatusesSeenMs)
            val hasFreshStatuses = remember(statusFeedRows, effectiveStatusesSeenMs, activeShip) {
                statusFeedRows.any { c ->
                    (c.statusUpdatedMs ?: 0L) > effectiveStatusesSeenMs &&
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
                (hasFreshStatuses && RailItem.Statuses in kebabItems) ||
                (hasPendingInvites && RailItem.Invites in kebabItems)
            // Desktop only. A phone reaches every section through
            // the drawer, and two ways to one set of sections is
            // one way too many.
            if (!io.nisfeb.talon.ui.isDrawerNavigation) Box {
                io.nisfeb.talon.ui.IconButton(tip = "More", onClick = { menuOpen = true }) {
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
                    // One click to invite a friend: the share sheet, or the
                    // invite copied where there is none.
                    DropdownMenuItem(
                        text = { Text("Invite a friend") },
                        onClick = {
                            menuOpen = false
                            inviteNote = inviteFriend()
                        },
                    )
                    // Reordering groups and folders, which the header's
                    // pencil used to toggle.
                    DropdownMenuItem(
                        text = { Text("Reorder") },
                        onClick = {
                            menuOpen = false
                            editMode = true
                        },
                    )
                    // Assistant — opt-in, so gated on onOpenAssistant being
                    // wired (null when unsupported / off / no key).
                    if (RailItem.Assistant in kebabItems) {
                        onOpenAssistant?.let { open ->
                            DropdownMenuItem(
                                text = { Text("Assistant") },
                                onClick = {
                                    menuOpen = false
                                    open()
                                },
                            )
                        }
                    }
                    // First in the list where a host has one at all.
                    // A wide window reaches Home from the rail; a narrow
                    // one has no rail, so this is the way in.
                    if (RailItem.Home in kebabItems) onOpenHome?.let { open ->
                        DropdownMenuItem(
                            text = { Text("Home") },
                            onClick = {
                                menuOpen = false
                                open()
                            },
                        )
                    }
                    if (RailItem.Profile in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("My profile") },
                            onClick = {
                                menuOpen = false
                                onOpenSelfProfile()
                            },
                        )
                    }
                    if (RailItem.Mail in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Mail") },
                            onClick = {
                                menuOpen = false
                                onOpenMail()
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
                    if (RailItem.Calendar in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Calendar") },
                            onClick = {
                                menuOpen = false
                                onOpenCalendar()
                            },
                        )
                    }
                    // People and the list's own layout, as one entry that
                    // opens in place: statuses, contacts and what the
                    // sidebar shows. Sign out lives with the ships, in the
                    // switcher behind the logo.
                    DropdownMenuItem(
                        text = { Text("People & sidebar") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (hasFreshStatuses && RailItem.Statuses in kebabItems) MenuBadgeDot()
                                Text(if (peopleOpen) " \u25B4" else " \u25B8")
                            }
                        },
                        onClick = { peopleOpen = !peopleOpen },
                    )
                    if (peopleOpen) {
                        if (RailItem.Statuses in kebabItems) {
                            DropdownMenuItem(
                                text = { Text("Statuses") },
                                trailingIcon = {
                                    if (hasFreshStatuses) MenuBadgeDot()
                                },
                                contentPadding = PaddingValues(start = 32.dp, end = 12.dp),
                                onClick = {
                                    menuOpen = false
                                    val now = nowMs()
                                    menuSeen.markStatusesSeenAt(now)
                                    repo.pushScope.launch {
                                        runCatching { repo.settingsSync?.pushStatusesSeen(now) }
                                    }
                                    onOpenStatusFeed()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Contacts") },
                            contentPadding = PaddingValues(start = 32.dp, end = 12.dp),
                            onClick = {
                                menuOpen = false
                                onOpenContacts()
                            },
                        )
                        if (onOpenSidebarSettings != null) {
                            DropdownMenuItem(
                                text = { Text(if (io.nisfeb.talon.ui.isDrawerNavigation) "Edit menu" else "Edit sidebar") },
                                contentPadding = PaddingValues(start = 32.dp, end = 12.dp),
                                onClick = {
                                    menuOpen = false
                                    onOpenSidebarSettings()
                                },
                            )
                        }
                    }
                    if (RailItem.Actions in kebabItems) onOpenActions?.let { open ->
                        DropdownMenuItem(
                            text = { Text(io.nisfeb.talon.ui.railLabel(RailItem.Actions)) },
                            onClick = {
                                menuOpen = false
                                open()
                            },
                        )
                    }
                    if (RailItem.Administration in kebabItems) {
                        DropdownMenuItem(
                            text = { Text("Group Moderation") },
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
                }
            }
            // The brand mark on the right, where the ellipsis was, and
            // it is what opens the ship picker. Where the rail shows, the
            // rail holds it, at its foot, instead.
            if (!io.nisfeb.talon.ui.LocalRailShown.current) {
                val open = Modifier.padding(end = 4.dp).size(32.dp).clickable { onOpenShipSwitcher() }
                activeShip?.let { io.nisfeb.talon.ui.ShipMark(it, size = 32.dp, modifier = open, contentDescription = "Switch ship") }
                    ?: io.nisfeb.talon.ui.TalonLogo(contentDescription = "Switch ship", modifier = open)
            }
        }
        activeShip?.let { io.nisfeb.talon.ui.SyncStatusBanner(repo, it, onSignIn) }
        inviteNote?.let { note ->
            LaunchedEffect(note) { kotlinx.coroutines.delay(5_000); inviteNote = null }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
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
                else confirmMarkFolderRead = f
            },
            onSpecialLongPress = { confirmMarkAllRead = true },
            onCreateNew = { creatingFolder = true },
            creating = creatingFolder,
            onCreateFolder = { name ->
                scope.launch { createFolderLogged(repo, name, folders.size, null) }
                creatingFolder = false
            },
            onDismissCreate = { creatingFolder = false },
        )
        if (selectedFolderId == null && selectedSpecial == SpecialTab.All) {
            // Groups and DMs always, then Party lines and Threads where a
            // caller supplies them (the latter only when a thread is followed).
            val homeTabs = buildList {
                add(HomeTab.Groups)
                add(HomeTab.Dms)
                if (partyLinesTab != null) add(HomeTab.PartyLines)
                if (threadsTab != null) add(HomeTab.Threads)
            }
            io.nisfeb.talon.ui.AdaptiveTabRow(
                labels = homeTabs.map { t ->
                    when (t) {
                        HomeTab.Groups -> if (groupsUnread > 0) "Groups · $groupsUnread" else "Groups"
                        HomeTab.Dms -> if (dmsUnread > 0) "DMs · $dmsUnread" else "DMs"
                        HomeTab.PartyLines -> "Party lines"
                        HomeTab.Threads -> if (threadsUnread > 0) "Threads · $threadsUnread" else "Threads"
                    }
                },
                selected = homeTabs.indexOf(selectedHomeTab).coerceAtLeast(0),
                onSelect = { switchHomeTab(homeTabs[it]) },
                // Someone is on one of the lines right now.
                dots = if (partyLinesOccupied && partyLinesTab != null) {
                    setOf(homeTabs.indexOf(HomeTab.PartyLines))
                } else {
                    emptySet()
                },
            )
        }
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
        io.nisfeb.talon.ui.PermitsBanner(repo.shipHttp, repo.shipBaseUrl)
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
        // The list and the chips that point at unread rows beyond its
        // edges share a box, so the chips sit against the list's own top
        // and bottom rather than a guessed distance from the screen's.
        Box(Modifier.weight(1f).fillMaxWidth()) {
        // The party tab is a different list, not a slice of the home
        // rows, so it stands in for the whole LazyColumn. Guarded on the
        // view selectors too: selectedHomeTab survives a switch to a
        // folder, where the tab strip that would let you leave is hidden.
        if (partyLinesTab != null && selectedHomeTab == HomeTab.PartyLines &&
            selectedFolderId == null && selectedSpecial == SpecialTab.All
        ) partyLinesTab()
        else if (threadsTab != null && selectedHomeTab == HomeTab.Threads &&
            selectedFolderId == null && selectedSpecial == SpecialTab.All
        ) threadsTab()
        // Braced: everything down to the list's search bar is the chats'
        // own list. Unbraced, the else took only the next line, and the
        // list was drawn over the threads and party tabs, taking their taps.
        else {
        // A wheel's scroll ends with no fling: settle the bar when the list rests.
        LaunchedEffect(listState.isScrollInProgress) { if (!listState.isScrollInProgress) searchReveal.settle() }
        // Message search, as a small rounded bar over the top of the list,
        // tucked away until pulled for: the first letter typed opens the
        // results with it, and emptying them comes back here. Over the list,
        // not above it: only the list's top padding grows as it slides in,
        // so nothing moves under the finger that pulls it (a layout that
        // moved fed the drag its own movement, without end).
        var searchText by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue()) }
        val shown = searchReveal.shownPx
        val shownDp = with(androidx.compose.ui.platform.LocalDensity.current) { shown.toDp() }
        Box {
        LazyColumn(
            state = listState,
            // Room over the first row for as much of the search bar as shows.
            contentPadding = PaddingValues(top = 8.dp + shownDp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Claims the triage noticed, above everything: each is one tap
            // to confirm or discard, and a kind confirmed enough times
            // stops asking.
            if (noticed.isNotEmpty()) {
                item(key = "__noticed_header", contentType = "req_header") {
                    Text(
                        "Noticed",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(items = noticed, key = { "noticed:${it.id}" }, contentType = { "noticed" }) { n ->
                    NoticedRow(
                        n = n,
                        contactMap = contactMap,
                        onConfirm = { repo.pushScope.launch { io.nisfeb.talon.orrery.OrreryRepo.confirm(db, n.id) } },
                        onDiscard = { repo.pushScope.launch { io.nisfeb.talon.orrery.OrreryRepo.discard(db, n.id) } },
                    )
                }
            }

            // Pending DM requests pinned to the top — Accept opens the
            // conversation, Decline dismisses it. Always shown (any tab)
            // so a new DM can't hide behind a folder/special selection.
            if (allDmInvites.isNotEmpty()) {
                item(key = "__dm_requests_header", contentType = "req_header") {
                    Text(
                        "Requests",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(
                    items = dmInvites,
                    key = { "req:${it.ship}" },
                    contentType = { "req" },
                ) { invite ->
                    DmRequestRow(
                        ship = invite.ship,
                        contactMap = contactMap,
                        onOpen = { currentOnOpenConversation(invite.ship) },
                        onAccept = {
                            repo.pushScope.launch {
                                runCatching { repo.acceptDmInvite(invite.ship) }
                            }
                            currentOnOpenConversation(invite.ship)
                        },
                        onDecline = {
                            repo.pushScope.launch {
                                runCatching { repo.declineDmInvite(invite.ship) }
                            }
                        },
                    )
                }
                if (heldInvites.isNotEmpty()) {
                    item(key = "__held_requests", contentType = "req_held") {
                        val n = heldInvites.size
                        Text(
                            (if (heldOpen) "Hide " else "") + "$n from people you haven't added",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                                .clickable(onClickLabel = if (heldOpen) "Hide them" else "Show them") { heldOpen = !heldOpen }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                    if (heldOpen) items(
                    items = heldInvites,
                    key = { "held:${it.ship}" },
                    contentType = { "req" },
                ) { invite ->
                    DmRequestRow(
                        ship = invite.ship,
                        contactMap = contactMap,
                        onOpen = { currentOnOpenConversation(invite.ship) },
                        onAccept = {
                            repo.pushScope.launch {
                                runCatching { repo.acceptDmInvite(invite.ship) }
                            }
                            currentOnOpenConversation(invite.ship)
                        },
                        onDecline = {
                            repo.pushScope.launch {
                                runCatching { repo.declineDmInvite(invite.ship) }
                            }
                        },
                    )
                }
                }
            }
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
                            open = m.whom == openWhom,
                        )
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
                                open = m.whom == openWhom,
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
                if (folderRows.isEmpty()) {
                    // A fresh folder has zero members and the add
                    // gesture (long-press a chat) is undiscoverable —
                    // say so instead of rendering a blank list.
                    item(key = "__folder_empty") {
                        SpecialEmpty("This folder is empty. ${io.nisfeb.talon.ui.holdWord} a chat or group to add it.")
                    }
                }
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
                                // enabled = editMode: outside reorder mode the
                                // item is inert (no drag). `enabled` does NOT
                                // switch off the library's item animation, so
                                // that is passed explicitly below.
                                ReorderableItem(
                                    reorderState,
                                    key = row.key,
                                    enabled = editMode && canReorder,
                                    // The library animates placement AND fades
                                    // removed items out even when disabled; a
                                    // fade-out that never finishes leaves ghost
                                    // rows drawn over the new list (seen when
                                    // switching the Groups/DMs tabs). Only edit
                                    // mode wants the animation.
                                    animateItemModifier = if (editMode) Modifier.animateItem() else Modifier,
                                ) { _ ->
                                    GroupCard(
                                        row = row,
                                        children = childrenSnapshot,
                                        contactMap = contactMap,
                                        ourShip = activeShip,
                                        draftOf = { drafts[it] },
                                        openWhom = openWhom,
                                        onOpen = onRowOpen,
                                        // The toggle itself, not the head's lambda:
                                        // that one opens a chat on expanding, and
                                        // showing the channels must not.
                                        onShowAll = onGroupHeadToggle,
                                        onLongClick = if (editMode) null else onGroupHeadLongPress,
                                        onChannelLongPress = onRowLongPress,
                                        editMode = editMode,
                                        dragHandleModifier = if (!canReorder) null else reorderHandle(
                                            onDragStarted = {
                                                hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                                                expandedGroups = expandedGroups - row.flag
                                            },
                                            onDragStopped = { onDragStopped() },
                                        ),
                                    )
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
                                // enabled = editMode: outside reorder mode the
                                // item is inert (no drag). `enabled` does NOT
                                // switch off the library's item animation, so
                                // that is passed explicitly below.
                                ReorderableItem(
                                    reorderState,
                                    key = row.key,
                                    enabled = editMode && canReorder,
                                    // The library animates placement AND fades
                                    // removed items out even when disabled; a
                                    // fade-out that never finishes leaves ghost
                                    // rows drawn over the new list (seen when
                                    // switching the Groups/DMs tabs). Only edit
                                    // mode wants the animation.
                                    animateItemModifier = if (editMode) Modifier.animateItem() else Modifier,
                                ) { _ ->
                                    ConversationRow(
                                        m = row.m,
                                        unread = row.unread,
                                        contactMap = contactMap,
                                        draft = drafts[row.m.whom],
                                        onClick = onRowOpen,
                                        onLongClick = onRowLongPress,
                                        open = row.m.whom == openWhom,
                                        editMode = editMode,
                                        dragHandleModifier = if (!canReorder) null else reorderHandle(
                                            onDragStarted = {
                                                hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = { onDragStopped() },
                                        ),
                                    )
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
                if (visibleHomeRows.isEmpty()) {
                    // First-launch limbo: scries haven't returned any
                    // rows yet. The top-of-screen sync caption is
                    // visible, but users were missing it on tall
                    // screens — a centered loading panel makes
                    // "still loading" unmistakable. Once bootstrap
                    // completes on a ship with zero conversations,
                    // point at the FAB instead of a blank list.
                    item(key = "__home_loading") {
                        // A first read that failed is not an empty ship.
                        val loadFailed by repo.loadFailed.collectAsState()
                        if (loadFailed && !bootstrapping && homeRows.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Couldn't load your chats.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { repo.retryChats() }) { Text("Try again") }
                            }
                        } else SpecialEmpty(
                            when {
                                bootstrapping && homeRows.isEmpty() -> "Loading your chats and groups…"
                                selectedHomeTab == HomeTab.Groups -> "No groups yet."
                                else -> "No direct messages yet. ${io.nisfeb.talon.ui.tapWord} + to start one."
                            }
                        )
                    }
                }
                var i = 0
                while (i < visibleHomeRows.size) {
                    val row = visibleHomeRows[i]
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
                            while (j < visibleHomeRows.size) {
                                val next = visibleHomeRows[j]
                                if (next is HomeRow.GroupChild && next.groupFlag == row.flag) {
                                    children += next
                                    j++
                                } else break
                            }
                            val childrenSnapshot = children.toList()
                            item(key = row.key, contentType = "GroupHead") {
                                // enabled = editMode: outside reorder mode the
                                // item is inert (no drag). `enabled` does NOT
                                // switch off the library's item animation, so
                                // that is passed explicitly below.
                                ReorderableItem(
                                    reorderState,
                                    key = row.key,
                                    enabled = editMode && canReorder,
                                    // The library animates placement AND fades
                                    // removed items out even when disabled; a
                                    // fade-out that never finishes leaves ghost
                                    // rows drawn over the new list (seen when
                                    // switching the Groups/DMs tabs). Only edit
                                    // mode wants the animation.
                                    animateItemModifier = if (editMode) Modifier.animateItem() else Modifier,
                                ) { _ ->
                                    GroupCard(
                                        row = row,
                                        children = childrenSnapshot,
                                        contactMap = contactMap,
                                        ourShip = activeShip,
                                        draftOf = { drafts[it] },
                                        openWhom = openWhom,
                                        onOpen = onRowOpen,
                                        // The toggle itself, not the head's lambda:
                                        // that one opens a chat on expanding, and
                                        // showing the channels must not.
                                        onShowAll = onGroupHeadToggle,
                                        onLongClick = if (editMode) null else onGroupHeadLongPress,
                                        onChannelLongPress = onRowLongPress,
                                        editMode = editMode,
                                        dragHandleModifier = if (!canReorder) null else reorderHandle(
                                            onDragStarted = {
                                                hapticRoot.performHapticFeedback(HapticFeedbackType.LongPress)
                                                expandedGroups = expandedGroups - row.flag
                                            },
                                            onDragStopped = { onDragStopped() },
                                        ),
                                    )
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
                                    open = row.m.whom == openWhom,
                                )
                            }
                            i++
                        }
                    }
                }
            }
        }
        if (shown > 0f) Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(shownDp)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surface),
        ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = { typed ->
                // An input method's word goes over once it is committed:
                // handed over while composing, its first keystroke went
                // raw ("n" for に, a lone jamo) and the rest was lost.
                if (typed.composition == null && typed.text.isNotBlank()) {
                    SearchDraft.query = typed.text
                    SearchDraft.fromBar = true
                    searchText = androidx.compose.ui.text.input.TextFieldValue()
                    onOpenSearch()
                } else searchText = typed
            },
            placeholder = { Text("Search", maxLines = 1) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            // Its own height, sliding down from under the header as it shows.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .heightIn(min = 40.dp)
                .semantics { contentDescription = "Search" },
        )
        }
        }
        } // the chats' own list
        // Only valid for the All tab: the Unread / Mentions tabs render
        // a different flat list, so unreadIndices (built from homeRows)
        // would not line up with listState. Folder views are short
        // enough not to need the hint.
        if (selectedFolderId == null && selectedSpecial == SpecialTab.All) {
            UnreadOffscreenIndicators(
                homeRows = visibleHomeRows,
                listState = listState,
                onScrollTo = { idx ->
                    scope.launch { listState.animateScrollToItem(idx) }
                },
            )
        }
        }
    }
        // No floating + any more: the header's pencil starts a new chat.
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
                scope.launch { createFolderLogged(repo, name, folders.size, whom) }
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
                    createFolderLogged(repo, name, folders.size, addGroup = flag)
                }
            },
            onDismiss = { folderSheetGroup = null },
            onMarkGroupRead = {
                // Resolve the group's channels to whoms and mark them
                // read in turn. We pull from contactMap (which
                // already has the channel list per group) so we don't
                // need an extra DAO round-trip.
                val whoms = contactMap.channelGroups
                    .filter { it.groupFlag == flag }
                    .map { it.nest }
                repo.markAllRead(whoms)
            },
            onLeaveGroup = { repo.leaveGroup(flag) },
        )
    }

    val markReadFolder = confirmMarkFolderRead
    if (confirmMarkAllRead || markReadFolder != null) {
        // Compute the unread set right at confirm time so the dialog
        // count reflects the current snapshot. We mark every whom in
        // `rows` with unread > 0 — the user's stated goal is clearing
        // a backlog of stale unreads, and pokes against zero-unread
        // whoms are wasted work. When a folder chip was long-pressed,
        // scope down to that folder's members, expanding group-kind
        // members to their channel whoms via contactMap.
        val folderWhoms = markReadFolder?.let { folder ->
            members.filter { it.folderId == folder.id }
                .flatMap { m ->
                    if (m.kind == FolderMemberEntity.KIND_GROUP) {
                        contactMap.channelGroups
                            .filter { it.groupFlag == m.whom }
                            .map { it.nest }
                    } else listOf(m.whom)
                }
                .toSet()
        }
        // And every chat whose only news is a reaction the ship still counts
        // on the iPhone's icon: no unread here, but not read there either
        // (sneagan, 2026-10-19: "mark all read should also clear the reactions").
        val unreadWhoms = rows.filter { (m, count) -> count > 0 || repo.shipStillCounts(m.whom) }
            .map { (m, _) -> m.whom }
            .filter { folderWhoms == null || it in folderWhoms }
        val dismiss = {
            confirmMarkAllRead = false
            confirmMarkFolderRead = null
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = {
                Text(
                    if (markReadFolder != null) "Mark '${markReadFolder.name}' as read?"
                    else "Mark all chats as read?"
                )
            },
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
                        dismiss()
                        repo.markAllRead(unreadWhoms)
                    },
                ) { Text("Mark read") }
            },
            dismissButton = {
                TextButton(onClick = dismiss) { Text("Cancel") }
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
                io.nisfeb.talon.ui.DestructiveTextButton(onClick = {
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

}

@Composable
internal fun ShipSwitcherDrawer(
    ships: List<String>,
    activeShip: String?,
    nicknames: Map<String, String>,
    onPick: (String) -> Unit,
    onAdd: () -> Unit,
    /** Drop a ship's saved session, keeping what it cached so signing
     *  back in does not start from nothing. Null where unwired. */
    onSignOut: ((String) -> Unit)? = null,
    /** Drop the session and everything stored under it. */
    onForget: ((String) -> Unit)? = null,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf<String?>(null) }
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
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    io.nisfeb.talon.ui.ShipMark(ship, size = 22.dp)
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
                                io.nisfeb.talon.ui.shipHandle(ship),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                io.nisfeb.talon.ui.shipHandle(ship),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = if (selected) FontWeight.SemiBold
                                    else FontWeight.Normal,
                                ),
                            )
                        }
                    }
                    if (onSignOut != null || onForget != null) {
                        Spacer(Modifier.weight(1f))
                        Box {
                            io.nisfeb.talon.ui.IconButton(tip = "What to do with ${io.nisfeb.talon.ui.shipHandle(ship)}", onClick = { menuFor = ship }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "What to do with ${io.nisfeb.talon.ui.shipHandle(ship)}",
                                )
                            }
                            DropdownMenu(
                                expanded = menuFor == ship,
                                onDismissRequest = { menuFor = null },
                            ) {
                                onSignOut?.let { act ->
                                    DropdownMenuItem(
                                        text = { Text("Sign out") },
                                        onClick = { menuFor = null; act(ship) },
                                    )
                                }
                                onForget?.let {
                                    DropdownMenuItem(
                                        text = { Text("Sign out and delete data") },
                                        onClick = { menuFor = null; confirmForget = ship },
                                    )
                                }
                            }
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
            // Signing out lives here, with the ships, not in the chat
            // list's menu: it is about which ship this device is on.
            if (onSignOut != null && activeShip != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSignOut(activeShip) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Sign out of ${io.nisfeb.talon.ui.shipHandle(activeShip)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    // Deleting cannot be undone and the button sits next to one that
    // can, so it asks — and says what goes and what does not.
    confirmForget?.let { ship ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text("Delete ${io.nisfeb.talon.ui.shipHandle(ship)}'s data?") },
            text = {
                Text(
                    "Signs out and removes everything stored on this device for " +
                        "${io.nisfeb.talon.ui.shipHandle(ship)}: its chats, groups and unread marks. Nothing on the " +
                        "ship itself is touched, and signing in again downloads it " +
                        "afresh.",
                )
            },
            confirmButton = {
                io.nisfeb.talon.ui.DestructiveTextButton(onClick = { confirmForget = null; onForget?.invoke(ship) }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = null }) { Text("Cancel") }
            },
        )
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
    /** The new-folder popover is open, under the + that opened it. */
    creating: Boolean = false,
    onCreateFolder: (String) -> Unit = {},
    onDismissCreate: () -> Unit = {},
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
            Box {
                TabChip(
                    text = "+",
                    selected = false,
                    onClick = onCreateNew,
                )
                FolderCreatePopover(expanded = creating, onCreate = onCreateFolder, onDismiss = onDismissCreate)
            }
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
            .clip(MaterialTheme.shapes.small)
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
            io.nisfeb.talon.ui.NotificationBadge(badgeCount, Modifier.padding(start = 6.dp))
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
                io.nisfeb.talon.ui.DestructiveTextButton(onClick = onDelete) {
                    Text("Delete")
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

/**
 * Naming a new folder: a small popover hanging under the + that opened
 * it, a field and a Create, rather than a dialog over the whole window.
 * Enter creates; Escape or a click outside closes it.
 */
@Composable
private fun FolderCreatePopover(expanded: Boolean, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(expanded) { mutableStateOf("") }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "New folder",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(expanded) { if (expanded) runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (name.isNotBlank()) onCreate(name.trim()) }),
                modifier = Modifier.width(180.dp).focusRequester(focus),
            )
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * A pending DM request row: name, with Decline / Accept; a tap reads it
 * first, guarded ([io.nisfeb.talon.ui.LocalGuardedContent]). The
 * stranger's picture is not fetched: a monogram.
 */
@Composable
private fun DmRequestRow(
    ship: String,
    contactMap: ContactMap,
    onOpen: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val label = remember(ship, contactMap) { contactMap.displayName(ship) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Read the request", onClick = onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(label = label, url = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "wants to message you",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDecline) {
            Text("Decline", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onAccept) { Text("Accept") }
    }
}

/** One noticed claim: who, what, and the words it came from. */
@Composable
private fun NoticedRow(
    n: io.nisfeb.talon.data.OrreryNoticedEntity,
    contactMap: ContactMap,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    fun who(id: String): String = when {
        id == "person/me" -> "You"
        id.startsWith("person/") -> id.removePrefix("person/").let { s -> contactMap.nickname("~$s") ?: "~$s" }
        else -> id.substringAfter('/')
    }
    val value = remember(n.valueJson) {
        runCatching {
            val e = kotlinx.serialization.json.Json.parseToJsonElement(n.valueJson)
            (e as? kotlinx.serialization.json.JsonObject)?.get("ref")?.let { r -> who(r.jsonPrimitive.content) }
                ?: e.jsonPrimitive.content
        }.getOrDefault(n.valueJson)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${who(n.subject)}: ${n.attr} is $value",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                n.snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDiscard) {
            Text("Discard", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onConfirm) { Text("Confirm") }
    }
}

@Composable
private fun ConversationRow(
    m: MessageEntity,
    unread: Int,
    contactMap: ContactMap,
    draft: String?,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    /** Open beside the list: see [focusGlow]. */
    open: Boolean = false,
    editMode: Boolean = false,
    dragHandleModifier: Modifier? = null,
) {
    val glow = focusGlow(open)
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
    val density = io.nisfeb.talon.ui.LocalChatDensity.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(glow)
            .then(rowClickModifier)
            .padding(horizontal = 16.dp, vertical = density.listRowVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The shared messenger row: a round avatar, the name in bold with
        // the time at the far right, and under it a two-line preview with
        // the red unread count beside it.
        Avatar(
            label = title,
            url = contactMap.conversationAvatar(m.whom),
            colorHex = contactMap.conversationColor(m.whom),
            // The density setting's size, as the group rows take it.
            size = density.listAvatarSize,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // The name and its badge take all the room the time leaves:
                // two weights side by side split it, and cut a long name at
                // half the row while the time floated.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ChannelTypeBadge(m.whom)
                }
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    if (!draftPreview.isNullOrEmpty()) {
                        Text(
                            "Draft: $draftPreview",
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (preview.isNotEmpty()) {
                        // Who said it, where more than one person talks.
                        val who = if (m.whom.contains('/') || !m.whom.startsWith("~")) "$authorLabel: " else ""
                        Text(
                            (who + preview).applyEmojiSpans(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (unread > 0) io.nisfeb.talon.ui.NotificationBadge(unread, Modifier.padding(top = 2.dp))
            }
        }
        if (editMode && dragHandleModifier != null) {
            Icon(
                imageVector = TalonIcons.DragHandle,
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
            io.nisfeb.talon.ui.NotificationBadge(unread)
        }
    }
}

/**
 * 8.dp filled dot in the theme's primary color — used as a "fresh
 * content" cue on the More-menu trailing slot and as a corner badge
 * on the ellipsis IconButton itself. Keeps the ellipsis's own glyph
 * intact (no overlay over the dots) by aligning to TopEnd rather
 * than centering.
 */
@Composable
internal fun MenuBadgeDot(modifier: Modifier = Modifier) {
    io.nisfeb.talon.ui.NotificationDot(modifier)
}

/** Per-whom message scan depth for the Mentions tab's true-mention
 *  filter. Bounds the DB read so a ship with many ping-bearing chats
 *  doesn't pay an unbounded scan; 50 covers the common case of
 *  "did I get mentioned recently here?" without sliding off old
 *  pages of cached history. */
private const val MENTION_SCAN_LIMIT = 50

private fun formatRelative(ms: Long): String {
    val diff = nowMs() - ms
    return when {
        diff < 24 * 3600_000L -> formatClock(ms)
        else -> formatMonthDay(ms)
    }
}

/** Derived "special" tabs that aren't user-created folders. */
internal enum class SpecialTab { All, Unread, Mentions }

/** The All view's two peers. The tabs are the section headers now. */
internal enum class HomeTab { Groups, Dms, PartyLines, Threads }

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
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A group as a card, the home list's Cards view (sneagan, 2026-11-06,
 * after the bubbles pleased the people who asked for a friendlier groups
 * list): its picture larger, the newest words with who said them, and a
 * line naming only the channels with something new ([newInLine]). A tap
 * anywhere on it unfolds its channels as the list's own channel rows, and
 * a group of one channel opens it (sneagan, 2026-11-08, when the cards
 * became the only groups view). Tried
 * and taken off: the faces of recent speakers, and channels as chips,
 * which readers disliked (sneagan, 2026-11-06).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupCard(
    row: HomeRow.GroupHead,
    children: List<HomeRow.GroupChild>,
    contactMap: ContactMap,
    ourShip: String?,
    draftOf: (String) -> String?,
    openWhom: String?,
    onOpen: (String) -> Unit,
    onShowAll: (String) -> Unit,
    onLongClick: ((String) -> Unit)?,
    onChannelLongPress: (String) -> Unit,
    editMode: Boolean,
    dragHandleModifier: Modifier?,
) {
    val glow = focusGlow(!row.expanded && children.any { it.whom == openWhom })
    val news = remember(children, contactMap) { newInLine(children) { contactMap.channelShortName(it) } }
    val clicks = if (editMode && dragHandleModifier != null) dragHandleModifier
    else Modifier.combinedClickableWithSecondary(
        // A group of one channel has nothing to unfold: the card is the
        // way in. Any other unfolds or folds its channels.
        onClick = { if (children.size > 1) onShowAll(row.flag) else children.firstOrNull()?.let { onOpen(it.whom) } },
        onLongClick = onLongClick?.let { { it(row.flag) } },
        onClickLabel = if (children.size > 1) (if (row.expanded) "Hide channels" else "Show channels") else null,
    )
    // Edge to edge, each against the next with a fine rule between
    // (sneagan, 2026-11-06), rather than rounded cards apart.
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth().background(glow).then(clicks).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val isHexTint = row.image?.startsWith("#") == true
            Avatar(
                label = row.title,
                url = if (isHexTint) null else row.image,
                colorHex = if (isHexTint) row.image else null,
                size = 52.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (row.totalUnread > 0) io.nisfeb.talon.ui.NotificationBadge(row.totalUnread)
                    if (children.size > 1) ChannelsToggle(children.size, row.expanded)
                }
                CardLatest(children, contactMap, ourShip, draftOf)
                if (news != null) {
                    Text(
                        news,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (editMode && dragHandleModifier != null) {
                Icon(
                    imageVector = TalonIcons.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically).size(20.dp),
                )
            }
        }
        // Opened, the list's own channel rows, set in under the text.
        if (row.expanded && children.size > 1) {
            children.forEach { child ->
                GroupChannelRow(
                    whom = child.whom,
                    m = child.m,
                    unread = child.unread,
                    contactMap = contactMap,
                    draft = draftOf(child.whom),
                    onClick = onOpen,
                    onLongClick = onChannelLongPress,
                    open = child.whom == openWhom,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

/** "N channels", and which way a tap on the card turns: shows its channel rows, or puts them away. */
@Composable
private fun ChannelsToggle(count: Int, expanded: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count channels",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = TalonIcons.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f },
        )
    }
}

/** The card's line of news: a draft waiting, else the newest words and who said them. */
@Composable
private fun CardLatest(
    children: List<HomeRow.GroupChild>,
    contactMap: ContactMap,
    ourShip: String?,
    draftOf: (String) -> String?,
) {
    val drafted = children.firstNotNullOfOrNull { c -> draftOf(c.whom)?.trim()?.takeIf { it.isNotEmpty() }?.let { c.whom to it } }
    val newest = children.mapNotNull { it.m }.maxByOrNull { it.sentMs }
    when {
        drafted != null -> Text(
            "Draft in ${contactMap.channelShortName(drafted.first)}: ${drafted.second.replace('\n', ' ').take(160)}",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        newest != null -> {
            val who = if (newest.author == ourShip) "You" else contactMap.displayName(newest.author)
            val words = remember(newest.id, newest.contentJson, newest.title) {
                StoryCache.previewFor(newest).take(200).replace('\n', ' ')
            }
            Text(
                listOf(if (words.isBlank()) "$who posted" else "$who: $words", formatRelative(newest.sentMs))
                    .joinToString(" · ").applyEmojiSpans(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        else -> Text(
            "Nothing said yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * The soft accent behind the row of the conversation open beside the
 * list. It was a flash that faded when a group expand landed on a
 * channel; kept, it says at a glance which one has the focus. It eases
 * in and out as the focus moves.
 */
@Composable
private fun focusGlow(open: Boolean): Color =
    animateColorAsState(
        if (open) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(durationMillis = 300),
        label = "focus",
    ).value

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
    /** Open beside the list: see [focusGlow]. */
    open: Boolean = false,
) {
    val shortName = remember(whom) { contactMap.channelShortName(whom) }
    val glow = focusGlow(open)
    val preview = m?.let {
        remember(it.id, it.contentJson, it.title) {
            StoryCache.previewFor(it).take(200).replace('\n', ' ')
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
            .background(glow)
            .combinedClickableWithSecondary(
                onClick = { onClick(whom) },
                onLongClick = { onLongClick(whom) },
            )
            // Set in under the card's text.
            .padding(start = 80.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
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
                overflow = TextOverflow.Ellipsis,
            )
            if (!draftPreview.isNullOrEmpty()) {
                Text(
                    "Draft: $draftPreview",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (timestamp != null) {
                // Blank preview (image-only post) drops its slot rather
                // than leaving a dangling separator.
                Text(
                    listOfNotNull(authorLabel, timestamp, preview?.takeIf { it.isNotBlank() })
                        .joinToString(" · ").applyEmojiSpans(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (unread > 0) {
            io.nisfeb.talon.ui.NotificationBadge(unread)
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
                .padding(top = 12.dp),
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

/** Drop a ship's cached rows, previews and counts. Without this a
 *  ship whose data was just erased came back in the same process with
 *  every row it used to have. */
fun forgetHomeListSnapshot(ship: String) = HomeListSnapshot.forget(ship)

/**
 * Forget a conversation's unread count in the cached chat list.
 *
 * The cache exists so switching ships does not paint the list
 * empty-of-unreads while Room catches up, and it is filled by the
 * list's own flow. But the list is torn down the moment somebody
 * opens a conversation, so the flow stops collecting and the cache
 * freezes with the counts as they were just before. Reading the
 * conversation clears the badge in the database at once; the cache
 * never hears. Coming back replayed the stale counts, so the unread
 * dots appeared for the length of one Room round trip and then went
 * out -- for messages that had already been read.
 *
 * Called from the one place that marks a conversation read, so every
 * route in (opening it, a deep link, the assistant) is covered.
 */
fun noteConversationRead(whom: String) {
    val snap = HomeListSnapshot.active ?: return
    val rows = snap.rows
    if (rows.none { it.first.whom == whom && it.second != 0 }) return
    snap.rows = rows.map { if (it.first.whom == whom) it.first to 0 else it }
}

/**
 * Per-ship home-list cache. Keyed on the active ship so switching
 * between accounts keeps each one's cached rows (and unread counts)
 * until the new ship's Room query round-trips. Without this, a fresh
 * switch paints the chat list empty-of-unreads for a beat while Room
 * catches up behind whatever repo.start is doing on IO.
 */
internal object HomeListSnapshot {
    private val perShip = ConcurrentMap<String, ShipSnapshot>()

    @Volatile var active: ShipSnapshot? = null

    fun forget(ship: String) {
        val gone = perShip.remove(ship)
        if (active === gone) active = null
    }

    fun bind(ship: String?): ShipSnapshot {
        val s = if (ship == null) ShipSnapshot() else perShip.getOrPut(ship) { ShipSnapshot() }
        active = s
        return s
    }
}

@Composable
private fun ChannelTypeBadge(whom: String) {
    val (label, bg) = when {
        whom.startsWith("diary/") -> "Bulletin" to MaterialTheme.colorScheme.tertiaryContainer
        whom.startsWith("notes/") -> "Notebook" to MaterialTheme.colorScheme.tertiaryContainer
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
    @Volatile var expandedGroups: Set<String> = emptySet()
    @Volatile var groupOrders: List<io.nisfeb.talon.data.GroupOrderEntity> = emptyList()
    @Volatile var folders: List<FolderEntity> = emptyList()
    @Volatile var members: List<FolderMemberEntity> = emptyList()
    @Volatile var selectedFolderId: Long? = null
    @Volatile var selectedSpecial: SpecialTab = SpecialTab.All
    @Volatile var initialTabApplied: Boolean = false
    @Volatile var selectedHomeTab: HomeTab = HomeTab.Groups
    /** Each tab's scroll position (first visible index, offset), saved
     *  when leaving it and restored on return. One [listState] serves
     *  both tabs: swapping list states under a live lazy list left its
     *  placement animations stranded — groups drawn over each other —
     *  and the reorder is bound to this one state anyway. */
    @Volatile var groupsScroll: Pair<Int, Int> = 0 to 0
    @Volatile var dmsScroll: Pair<Int, Int> = 0 to 0
    val listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.LazyListState()
}

/**
 * Create a folder and report what happened.
 *
 * All three call sites used a bare `scope.launch`, so a null settings
 * sink and a throwing insert looked identical from the outside: the
 * folder just never appeared. On iOS that cost a round trip to a user
 * to learn which of the two it was.
 */
private suspend fun createFolderLogged(
    repo: TlonChatRepo,
    name: String,
    sortOrder: Int,
    addWhom: String? = null,
    addGroup: String? = null,
) {
    val sync = repo.settingsSync
    if (sync == null) {
        io.nisfeb.talon.util.Log.e(
            "Folders",
            "cannot create \"$name\": this build has no settings sink",
        )
        return
    }
    // Off the composition scope: these callers are rememberCoroutineScope,
    // which dispatches on main.
    runCatching {
        kotlinx.coroutines.withContext(io.nisfeb.talon.util.ioDispatcher) {
        val id = sync.createFolder(name, sortOrder)
        io.nisfeb.talon.util.Log.i("Folders", "created \"$name\" as id=$id")
        if (addWhom != null) sync.addFolderMember(id, addWhom)
        if (addGroup != null) sync.addGroupToFolder(id, addGroup)
        id
        }
    }.onFailure {
        io.nisfeb.talon.util.Log.e("Folders", "creating \"$name\" failed", it)
    }
}

/** The chat list's search bar, all of it: the field and its margin. */
private val SEARCH_BAR_HEIGHT = 64.dp
