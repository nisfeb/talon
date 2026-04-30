package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Android Autofill Framework hook for a TextField. Pre-Stage-F
 * production used `Modifier.semantics { contentType = ContentType.X }`
 * which is gated behind a still-internal CMP 1.7 API. The older
 * AutofillType-based API is still public (just experimental) and
 * triggers the same Autofill Framework path that password managers
 * (Bitwarden, 1Password, Google Password Manager) integrate with.
 *
 * Usage (Android host):
 *   LoginScreen(
 *       usernameAutofillModifier = rememberAutofillModifier(
 *           types = listOf(AutofillType.Username),
 *           onFill = { /* TextField onValueChange equivalent */ },
 *       ),
 *       …
 *   )
 *
 * The fill callback receives the autofill-suggested string. Caller
 * threads it into the same setter the user's keyboard typing would.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberAutofillModifier(
    types: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val node = remember(types) {
        AutofillNode(autofillTypes = types, onFill = onFill)
    }
    DisposableEffect(node, tree) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }
    return Modifier
        .onGloballyPositioned { coords ->
            node.boundingBox = coords.boundsInWindow()
        }
        .onFocusChanged { state ->
            if (state.isFocused) autofill?.requestAutofillForNode(node)
            else autofill?.cancelAutofillForNode(node)
        }
}
