package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopShellTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow window does not render rail`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(width = 600.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = RailItem.entries.toList(),
                    onItemClicked = {},
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        // Compact: rail icon labels (used as contentDescription) don't render.
        onNodeWithContentDescription("Statuses").assertDoesNotExist()
        onNodeWithContentDescription("Bookmarks").assertDoesNotExist()
        onNodeWithContentDescription("Activity").assertDoesNotExist()
        // Detail wins on compact when set.
        onNodeWithText("DETAIL").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tapping a rail icon fires onItemClicked with the tapped item`() =
        runComposeUiTest {
            var lastClicked: RailItem? = null
            setContent {
                Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                    DesktopShell(
                        activeRailTab = RailTab.Chats,
                        enabledItems = RailItem.entries.toList(),
                        onItemClicked = { lastClicked = it },
                        list = { Text("LIST") },
                        detail = null,
                        listFraction = 0.30f,
                        onListFractionChange = {},
                    )
                }
            }
            onNodeWithContentDescription("Bookmarks").performClick()
            assertEquals(RailItem.Bookmarks, lastClicked)
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window with null rightSidebar does not affect layout`() =
        runComposeUiTest {
            // Forward-compat guard: the Phase 3 sidebar slot is reserved
            // but defaults to null. A null slot must not consume any
            // horizontal space (no empty fourth column).
            setContent {
                Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                    DesktopShell(
                        activeRailTab = RailTab.Chats,
                        enabledItems = RailItem.entries.toList(),
                        onItemClicked = {},
                        list = { Text("LIST") },
                        detail = { Text("DETAIL") },
                        listFraction = 0.30f,
                        onListFractionChange = {},
                        rightSidebar = null,
                    )
                }
            }
            onNodeWithText("LIST").assertExists()
            onNodeWithText("DETAIL").assertExists()
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `rail renders only enabled items`() = runComposeUiTest {
        setContent {
            Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = listOf(RailItem.Chats, RailItem.Bookmarks),
                    onItemClicked = {},
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        onNodeWithContentDescription("Chats").assertExists()
        onNodeWithContentDescription("Bookmarks").assertExists()
        onNodeWithContentDescription("Statuses").assertDoesNotExist()
        onNodeWithContentDescription("Activity").assertDoesNotExist()
        onNodeWithContentDescription("Settings").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `rail renders icons in the order of enabledItems`() = runComposeUiTest {
        // Pin the rc19 contract: the rail follows the user's
        // configured RailItemOrder (passed in as `enabledItems`),
        // not the enum's declaration order. Caller wins.
        val order = listOf(
            RailItem.Settings,
            RailItem.Chats,
            RailItem.Profile,
            RailItem.Bookmarks,
        )
        setContent {
            Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = order,
                    onItemClicked = {},
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        // Look at vertical bounds of each rail icon. With reverseLayout
        // off (default), the topmost rendered icon has the smallest
        // `top`. Sort our rail items by their `top` and assert the
        // order matches what we passed in.
        val orderedTops: List<Pair<RailItem, Float>> = order.map { item ->
            item to onNodeWithContentDescription(railLabel(item))
                .getBoundsInRoot().top.value
        }
        // Ascending top → ascending list position.
        val sortedByTop = orderedTops.sortedBy { it.second }.map { it.first }
        assertEquals(order, sortedByTop, "rail icons should render top-down in enabledItems order")
    }

    /** The shell with tagged panes, in a window [width] wide. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.shell(
        width: () -> androidx.compose.ui.unit.Dp,
        fraction: Float = 0.30f,
        right: Boolean = true,
        rightWidth: androidx.compose.ui.unit.Dp = DEFAULT_RIGHT_PANE_WIDTH,
    ) = setContent {
        // required: the test window is only 1024dp wide.
        Box(Modifier.requiredSize(width = width(), height = 800.dp)) {
            DesktopShell(
                activeRailTab = RailTab.Chats,
                enabledItems = RailItem.entries.toList(),
                onItemClicked = {},
                list = { Box(Modifier.fillMaxSize().testTag("list")) { Text("LIST") } },
                detail = { Box(Modifier.fillMaxSize().testTag("chat")) { Text("DETAIL") } },
                listFraction = fraction,
                onListFractionChange = {},
                rightSidebar = if (right) ({ Box(Modifier.fillMaxSize().testTag("right")) { Text("RIGHT") } }) else null,
                rightPaneWidth = rightWidth,
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.bounds(tag: String) = onNodeWithTag(tag).getBoundsInRoot()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a wide window has the right pane beside the chat, and the list beside both`() = runComposeUiTest {
        shell({ 1400.dp })
        val list = bounds("list")
        val chat = bounds("chat")
        val right = bounds("right")
        assertTrue(chat.left >= list.right, "chat after list")
        assertTrue(right.left >= chat.right, "right pane after chat")
        assertEquals(DEFAULT_RIGHT_PANE_WIDTH, right.width)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrower, the right pane opens over the chat and the list stays`() = runComposeUiTest {
        shell({ 900.dp })
        onNodeWithTag("chat").assertDoesNotExist()
        val list = bounds("list")
        val right = bounds("right")
        assertTrue(right.left >= list.right, "in the chat's column, beside the list")
        // The chat's whole column: the window less the rail, list and handle.
        val column = 900.dp - RAIL_WIDTH - list.width - HANDLE_WIDTH
        assertTrue(kotlin.math.abs((column - right.width).value) <= 1f, "$column vs ${right.width}")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `just above the compact breakpoint the list stays beside the chat`() = runComposeUiTest {
        // 840 to 904 used to show the chat alone beside the rail.
        shell({ 860.dp }, right = false)
        val list = bounds("list")
        val chat = bounds("chat")
        assertEquals(MIN_LIST_WIDTH, list.width)
        assertTrue(chat.left >= list.right)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `dragging the right pane's edge stops where list and chat keep their minimums`() = runComposeUiTest {
        var stored by mutableStateOf(DEFAULT_RIGHT_PANE_WIDTH)
        setContent {
            Box(Modifier.requiredSize(width = 1400.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = RailItem.entries.toList(),
                    onItemClicked = {},
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                    rightSidebar = { Box(Modifier.fillMaxSize().testTag("right")) { Text("RIGHT") } },
                    rightPaneWidth = stored,
                    onRightPaneWidthChange = { stored = it },
                )
            }
        }
        // The handle is the 6dp just left of the pane.
        fun dragHandle(byPx: Float) {
            val x = with(density) { (bounds("right").left - 3.dp).toPx() }
            val y = with(density) { 400.dp.toPx() }
            onRoot().performTouchInput { down(Offset(x, y)); moveBy(Offset(byPx, 0f)); up() }
            waitForIdle()
        }
        dragHandle(-2000f)
        // 1400 less the rail, list and chat minimums and two handles.
        assertEquals(724.dp, stored)
        dragHandle(2000f)
        assertEquals(MIN_RIGHT_PANE_WIDTH, stored)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the chat column is never narrower than its minimum`() = runComposeUiTest {
        var window by mutableStateOf(2400.dp)
        var right by mutableStateOf(true)
        var fraction by mutableStateOf(0.5f)
        setContent {
            Box(Modifier.requiredSize(width = window, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = RailItem.entries.toList(),
                    onItemClicked = {},
                    list = { Box(Modifier.fillMaxSize().testTag("list")) { Text("LIST") } },
                    detail = { Box(Modifier.fillMaxSize().testTag("chat")) { Text("DETAIL") } },
                    listFraction = fraction,
                    onListFractionChange = {},
                    rightSidebar = if (right) ({ Box(Modifier.fillMaxSize().testTag("right")) { Text("RIGHT") } }) else null,
                    rightPaneWidth = MAX_RIGHT_PANE_WIDTH,
                )
            }
        }
        for (w in listOf(2400, 1600, 1200, 1000, 956, 955, 904, 870, 840, 839, 600, 360)) {
            for (r in listOf(true, false)) for (f in listOf(0.2f, 0.5f)) {
                window = w.dp; right = r; fraction = f
                waitForIdle()
                // The chat's column holds the chat, or the right pane over it.
                val column = if (onAllNodesWithTag("chat").fetchSemanticsNodes().isNotEmpty()) "chat" else "right"
                val width = bounds(column).width
                assertTrue(width >= MIN_CHAT_WIDTH, "window ${w}dp, right $r, fraction $f: $column is $width")
            }
        }
    }

    // An iPhone on its side, 852 by 393 with 59-point safe areas left and
    // right: every pane padded for both itself, so the list kept about half
    // its 240 points and the chat lost 59 on the side by the list
    // (sneagan, 2026-10-09). The panes stand in for the real screens, which
    // pad for the safe areas the same way.
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the side safe areas are taken once, around the rail and the panes`() = runComposeUiTest {
        val sides = androidx.compose.foundation.layout.WindowInsets(left = 59.dp, right = 59.dp)
        fun pane(tag: String) = Modifier.fillMaxSize().windowInsetsPadding(sides).testTag(tag)
        setContent {
            Box(Modifier.size(width = 852.dp, height = 393.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = RailItem.entries.toList(),
                    onItemClicked = {},
                    list = { Box(pane("list")) },
                    detail = { Box(pane("chat")) },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                    sideInsets = sides,
                )
            }
        }
        val list = onNodeWithTag("list").getBoundsInRoot()
        val chat = onNodeWithTag("chat").getBoundsInRoot()
        assertEquals(59.dp + RAIL_WIDTH, list.left, "the rail sits inside the left safe area, the list beside it")
        assertEquals(MIN_LIST_WIDTH, list.width, "the list keeps its whole width: no safe area inside it")
        assertEquals(852.dp - 59.dp, chat.right, "the chat stops at the right safe area")
        assertEquals(list.right + HANDLE_WIDTH, chat.left, "and starts right after the list's handle, with no safe area of its own")
    }
}

/** Local mirror of DesktopShell's private `railLabel(item)` so the
 *  test can find each icon by its contentDescription. Must stay in
 *  sync with the impl. */
private fun railLabel(item: RailItem): String = when (item) {
    RailItem.Home -> "Home"
    RailItem.Chats -> "Chats"
    RailItem.Mail -> "Mail"
    RailItem.Calendar -> "Calendar"
    RailItem.Statuses -> "Statuses"
    RailItem.Bookmarks -> "Bookmarks"
    RailItem.Activity -> "Activity"
    RailItem.Assistant -> "Assistant"
    RailItem.Profile -> "My profile"
    RailItem.Administration -> "Administration"
    RailItem.Invites -> "Invites"
    RailItem.Actions -> "Actions"
    RailItem.Settings -> "Settings"
}
