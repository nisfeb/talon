package io.nisfeb.talon.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * One line that shrinks to fit rather than wrapping: a button label on
 * a narrow phone, where "Message" sharing a row with two other buttons
 * broke onto a second line. Full size wherever it fits.
 */
@Composable
fun FitText(text: String, modifier: Modifier = Modifier, minSize: TextUnit = 10.sp) {
    val style = LocalTextStyle.current
    BasicText(
        text,
        modifier,
        style = style.copy(color = style.color.takeOrElse { LocalContentColor.current }),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minSize,
            maxFontSize = style.fontSize.takeIf { it.isSpecified } ?: 14.sp,
            stepSize = 0.5.sp,
        ),
    )
}
