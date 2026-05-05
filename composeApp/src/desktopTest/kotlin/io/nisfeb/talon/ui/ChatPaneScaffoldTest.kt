package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class ChatPaneScaffoldTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow window renders only detail when set`() = runComposeUiTest {
        setContent {
            // 600dp < 840dp threshold → stacked behaviour
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 600.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                )
            }
        }
        onNodeWithText("DETAIL").assertExists()
        onNodeWithText("LIST").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `narrow window renders list when detail is null`() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 600.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(list = { Text("LIST") }, detail = null)
            }
        }
        onNodeWithText("LIST").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window renders both panes when detail is set`() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 1200.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(
                    list = { Text("LIST") },
                    detail = { Text("DETAIL") },
                )
            }
        }
        onNodeWithText("LIST").assertExists()
        onNodeWithText("DETAIL").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `wide window with null detail renders empty pane copy`() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 1200.dp, height = 800.dp),
            ) {
                ChatPaneScaffold(list = { Text("LIST") }, detail = null)
            }
        }
        onNodeWithText("LIST").assertExists()
        onNodeWithText("Select a chat to begin").assertExists()
    }
}
