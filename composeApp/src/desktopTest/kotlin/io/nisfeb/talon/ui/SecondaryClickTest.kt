package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the right-click stacked-popup bug fixed in
 * 0.8.11. When the user right-clicked a reaction chip inside a
 * message bubble, BOTH popups appeared: the chip's reactions list
 * stacked underneath the bubble's action sheet. Cause: the desktop
 * `onSecondaryClick` actual listened on `PointerEventPass.Initial`
 * (outer→inner) and never checked if a child had already consumed
 * the event, so both handlers fired.
 *
 * Fix: switched to `PointerEventPass.Main` (inner→outer) plus an
 * `isConsumed` skip — inner consumes first, outer sees the
 * consumed event and bails.
 *
 * This test pins the contract: a right-click on a nested
 * onSecondaryClick handler fires the inner handler exactly once
 * and the outer handler not at all.
 */
class SecondaryClickTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `right-click on inner handler does not propagate to outer`() = runComposeUiTest {
        var outerHits = 0
        var innerHits = 0
        setContent {
            Box(
                Modifier
                    .testTag("outer")
                    .size(200.dp, 200.dp)
                    .onSecondaryClick { outerHits++ },
            ) {
                Box(
                    Modifier
                        .testTag("inner")
                        .size(60.dp, 60.dp)
                        .onSecondaryClick { innerHits++ },
                ) {
                    Text("chip")
                }
            }
        }
        onNodeWithTag("inner").performMouseInput { rightClick() }
        assertEquals(1, innerHits, "inner onSecondaryClick should fire on right-click")
        assertEquals(0, outerHits, "outer onSecondaryClick must NOT fire when child consumes — was the 0.8.11 bug")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `right-click outside the inner box still reaches the outer handler`() = runComposeUiTest {
        var outerHits = 0
        var innerHits = 0
        setContent {
            Box(
                Modifier
                    .testTag("outer")
                    .size(200.dp, 200.dp)
                    .onSecondaryClick { outerHits++ },
            ) {
                // Inner box only covers the top-left corner; clicking
                // elsewhere on the outer box should still fire the
                // outer handler.
                Box(
                    Modifier
                        .testTag("inner")
                        .size(40.dp, 40.dp)
                        .onSecondaryClick { innerHits++ },
                )
            }
        }
        // Right-click in a region not covered by the inner box — the
        // performMouseInput receiver targets the outer node directly,
        // and rightClick() defaults to the node's centre.
        onNodeWithTag("outer").performMouseInput { rightClick() }
        assertEquals(0, innerHits)
        assertEquals(1, outerHits)
    }
}
