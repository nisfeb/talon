package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class ChatPaneScaffoldTest {

    // "the icon when dragging the UI segments like the group list or the
    // thread view is a hand instead of a resize cursor". The cursor as the
    // window gets it: a scene whose platform keeps the icon Compose sets.
    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun `the divider between panes shows the resize arrow, not the hand`() {
        var icon: androidx.compose.ui.input.pointer.PointerIcon? = null
        val context = object : androidx.compose.ui.platform.PlatformContext by androidx.compose.ui.platform.PlatformContext.Empty() {
            override fun setPointerIcon(pointerIcon: androidx.compose.ui.input.pointer.PointerIcon) { icon = pointerIcon }
        }
        val scene = androidx.compose.ui.scene.CanvasLayersComposeScene(
            size = androidx.compose.ui.unit.IntSize(400, 200),
            coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined,
            platformContext = context,
        )
        val canvas = org.jetbrains.skia.Surface.makeRasterN32Premul(400, 200).canvas.asComposeCanvas()
        var frame = 0L
        fun render() = repeat(3) { scene.render(canvas, (++frame) * 16_000_000L) }
        try {
            // Density 1: the list is 100px, the handle the 6px after it.
            scene.setContent {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.foundation.layout.Box(Modifier.size(100.dp))
                    PaneDragHandle(onDragDelta = {})
                }
            }
            render()
            scene.sendPointerEvent(
                androidx.compose.ui.input.pointer.PointerEventType.Move,
                androidx.compose.ui.geometry.Offset(103f, 50f),
                type = androidx.compose.ui.input.pointer.PointerType.Mouse,
            )
            render()
            kotlin.test.assertEquals(ResizeLeftRightIcon, icon)
        } finally {
            scene.close()
        }
    }

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
