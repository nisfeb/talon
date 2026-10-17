package io.nisfeb.talon.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Archie, Talon's voice assistant: a small owl on a branch, flat and in
 * one colour so [androidx.compose.material3.Icon] tints it like any other
 * icon. Named for Archimedes, whose bronze sphere of the heavens was the
 * first orrery, and who is Merlin's owl in The Sword in the Stone.
 *
 * Drawn by hand on a 24 unit grid. The eyes, their pupils, the sparkle in
 * each, the beak and the wing edges are holes cut by the even-odd rule,
 * so the surface shows through them.
 */
val ArchieMark: ImageVector by lazy {
    ImageVector.Builder(name = "ArchieMark", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                // The body, with an ear tuft at each top corner.
                "M3.6 2.4 Q6.6 3.4 8.2 5.2 Q12 4.1 15.8 5.2 Q17.4 3.4 20.4 2.4 Q19.9 5.6 20.2 8.4 " +
                    "Q21.2 14.2 18.8 18.2 Q16.4 21.3 12 21.3 Q7.6 21.3 5.2 18.2 Q2.8 14.2 3.8 8.4 Q4.1 5.6 3.6 2.4 Z " +
                    // The eyes, the pupils, and a sparkle in each.
                    "M11.9 10.2 A3.2 3.2 0 1 0 5.5 10.2 A3.2 3.2 0 1 0 11.9 10.2 Z " +
                    "M18.5 10.2 A3.2 3.2 0 1 0 12.1 10.2 A3.2 3.2 0 1 0 18.5 10.2 Z " +
                    "M11.1 10.6 A1.9 1.9 0 1 0 7.3 10.6 A1.9 1.9 0 1 0 11.1 10.6 Z " +
                    "M16.7 10.6 A1.9 1.9 0 1 0 12.9 10.6 A1.9 1.9 0 1 0 16.7 10.6 Z " +
                    "M10.4 9.9 A0.6 0.6 0 1 0 9.2 9.9 A0.6 0.6 0 1 0 10.4 9.9 Z " +
                    "M16.0 9.9 A0.6 0.6 0 1 0 14.8 9.9 A0.6 0.6 0 1 0 16.0 9.9 Z " +
                    // The beak and the wing edges.
                    "M11.1 12.7 L12.9 12.7 L12 14.6 Z " +
                    "M5.6 13.8 Q6.0 17.2 8.6 19.6 Q6.4 18.8 5.3 16.6 Z " +
                    "M18.4 13.8 Q18.0 17.2 15.4 19.6 Q17.6 18.8 18.7 16.6 Z",
            ),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        // The branch.
        .addPath(
            pathData = addPathNodes("M4.2 22 L19.8 22 A0.7 0.7 0 0 1 19.8 23.4 L4.2 23.4 A0.7 0.7 0 0 1 4.2 22 Z"),
            fill = SolidColor(Color.Black),
        )
        .build()
}

/** What Talon's voice assistant is called wherever it is named. */
const val ASSISTANT_NAME = "Archie"
