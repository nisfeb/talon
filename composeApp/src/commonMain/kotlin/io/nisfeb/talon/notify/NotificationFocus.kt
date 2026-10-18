package io.nisfeb.talon.notify

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What the user is looking at, for the platform notification layer.
 * A system alert for the chat that is open in front of them is noise;
 * the repo writes [openWhom] as chats open and close.
 */
object NotificationFocus {
    @kotlin.concurrent.Volatile var openWhom: String? = null
}

/**
 * "Open this chat" from outside the composition: a tapped system
 * notification on iOS lands here and the app host navigates.
 */
object OpenChatRequests {
    /** [forShip]: which of the user's ships the notification was for.
     *  The same whom on another ship is a different conversation or
     *  none, so the host switches there before it opens anything. */
    data class Request(val whom: String, val postId: String?, val forShip: String? = null)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests

    fun request(whom: String, postId: String?, forShip: String? = null) {
        _requests.tryEmit(Request(whom, postId, forShip))
    }
}

/**
 * A clicked desktop notification, by the key it was shown with: a chat
 * (a whom has no ':') opens, and a notice's tag opens its place. Others
 * (mail, an orrery action) only bring the window up. It used to do only
 * that for every one, the chat included.
 */
fun openNotificationKey(key: String) {
    when {
        noticeLanding(key) != null -> OpenNoticeRequests.request(key)
        ':' !in key -> OpenChatRequests.request(key, null)
    }
}
