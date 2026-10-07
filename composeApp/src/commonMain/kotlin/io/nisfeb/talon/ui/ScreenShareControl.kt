package io.nisfeb.talon.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.nisfeb.talon.call.ScreenSource
import kotlinx.coroutines.launch

/**
 * A call's screen share, as a button sees it: what can be shared, and
 * the call's own switch ([set] with null stops). One source is shared
 * at once; several are [offered] for a pick.
 */
class ScreenShareControl(
    private val sources: suspend () -> List<ScreenSource>,
    private val set: suspend (ScreenSource?) -> Boolean,
) {
    /** Sources waiting for a pick; null when no menu is open. */
    var offered by mutableStateOf<List<ScreenSource>?>(null)

    /** The last share would not start: nothing to share, or capture refused. */
    var failed by mutableStateOf(false)
        private set

    /** The share button: stop a share, or start one. */
    suspend fun press(sharing: Boolean) {
        if (sharing) return choose(null)
        val all = sources()
        when (all.size) {
            0 -> failed = true
            1 -> choose(all.single())
            else -> offered = all
        }
    }

    /** Share [source], or stop with null. */
    suspend fun choose(source: ScreenSource?) {
        offered = null
        failed = !set(source) && source != null
    }
}

/** What a source is called in the menu. */
internal fun ScreenSource.label(): String =
    title.ifBlank { if (isWindow) "Untitled window" else "Whole screen" }

/**
 * The share control's menu around a surface's own [button], which gets
 * the click. The bar and the meeting view draw different buttons.
 */
@Composable
fun ScreenShareMenu(
    control: ScreenShareControl,
    sharing: Boolean,
    button: @Composable (onClick: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box {
        button { scope.launch { control.press(sharing) } }
        DropdownMenu(expanded = control.offered != null, onDismissRequest = { control.offered = null }) {
            control.offered.orEmpty().forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.label()) },
                    onClick = { scope.launch { control.choose(source) } },
                )
            }
        }
    }
}
