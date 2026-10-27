package io.nisfeb.talon.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import io.nisfeb.talon.ui.theme.TalonTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A user: right-clicking a group to "Mark as read" didn't always work,
 * because the pop-out is draggable and a click needed a steady pointer.
 * A mouse that moves a little between press and release still clicks.
 */
@OptIn(ExperimentalTestApi::class)
class SheetClickTest {
    @Test
    fun `a click that moves a few pixels still marks the group read`() {
        // Within the button: released outside it, any button cancels.
        for (drift in listOf(Offset(0f, 4f), Offset(0f, 12f), Offset(30f, 0f))) {
            var marked = 0
            runComposeUiTest {
                setContent {
                    TalonTheme(darkTheme = false) {
                        FolderAssignmentSheet(
                            conversationLabel = "Group · Garden",
                            folders = emptyList(),
                            selectedFolderIds = emptySet(),
                            onToggle = { _, _ -> },
                            onCreateNew = {},
                            onDismiss = {},
                            onMarkGroupRead = { marked++ },
                        )
                    }
                }
                waitForIdle()
                onNodeWithText("Mark group as read").performMouseInput {
                    moveTo(center)
                    press()
                    moveBy(drift)
                    release()
                }
                waitForIdle()
            }
            assertEquals(1, marked, "a click that drifted by $drift")
        }
    }
}
