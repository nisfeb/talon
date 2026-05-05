package io.nisfeb.talon.ui

import io.nisfeb.talon.urbit.MediaCategory

/**
 * What the right column ([DesktopShell.rightSidebar] on wide, full-
 * screen on compact) is showing right now. Computed at render time
 * from App.kt's flat state vars; mutual exclusion is enforced at
 * write sites in App.kt — opening a thread clears group-info state
 * and vice versa.
 *
 * `null` = pane closed; on wide that means no fourth column is
 * rendered; on compact that means we're in the chat detail view.
 */
sealed interface RightPaneContent {
    data class Thread(
        val whom: String,
        val parentId: String,
        val replyAnchor: String?,
    ) : RightPaneContent

    data class GroupInfo(val whom: String) : RightPaneContent

    data class GroupInfoDrilldown(
        val whom: String,
        val category: MediaCategory,
    ) : RightPaneContent
}
