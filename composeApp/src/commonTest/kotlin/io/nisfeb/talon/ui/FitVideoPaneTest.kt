package io.nisfeb.talon.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** A 1:1 call's picture pane: as large as fits, in the picture's shape, never past its cap. */
class FitVideoPaneTest {
    @Test
    fun `the pane fits the width and the cap, keeping the picture's shape`() {
        // A wide window: the cap decides, and the pane is narrower than the window.
        assertEquals(DpSize(640.dp, 480.dp), fitVideoPane(1000.dp, 4f / 3f))
        assertEquals(480.dp, fitVideoPane(1000.dp, 16f / 9f).height)
        // A phone: the width decides.
        assertEquals(DpSize(360.dp, 202.5.dp), fitVideoPane(360.dp, 16f / 9f))
        // A portrait caller on a phone: the cap again.
        assertEquals(DpSize(270.dp, 480.dp), fitVideoPane(360.dp, 9f / 16f))
    }
}
