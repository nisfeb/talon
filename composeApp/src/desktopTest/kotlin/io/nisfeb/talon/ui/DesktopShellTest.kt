package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `wide window renders rail with all icons`() = runComposeUiTest {
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
                )
            }
        }
        onNodeWithContentDescription("Chats").assertExists()
        onNodeWithContentDescription("Statuses").assertExists()
        onNodeWithContentDescription("Bookmarks").assertExists()
        onNodeWithContentDescription("Activity").assertExists()
        onNodeWithContentDescription("Settings").assertExists()
        onNodeWithText("LIST").assertExists()
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
    fun `clicking a modal item fires onItemClicked with the right enum`() = runComposeUiTest {
        var clicked: RailItem? = null
        setContent {
            Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = listOf(RailItem.Chats, RailItem.Settings),
                    onItemClicked = { clicked = it },
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        onNodeWithContentDescription("Settings").performClick()
        assertEquals(RailItem.Settings, clicked)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking a pane-tab item fires onItemClicked with the right enum`() = runComposeUiTest {
        var clicked: RailItem? = null
        setContent {
            Box(Modifier.size(width = 1200.dp, height = 800.dp)) {
                DesktopShell(
                    activeRailTab = RailTab.Chats,
                    enabledItems = RailItem.entries.toList(),
                    onItemClicked = { clicked = it },
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                    listFraction = 0.30f,
                    onListFractionChange = {},
                )
            }
        }
        onNodeWithContentDescription("Bookmarks").performClick()
        assertEquals(RailItem.Bookmarks, clicked)
    }
}
