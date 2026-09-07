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
    data class Request(val whom: String, val postId: String?)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 4)
    val requests: SharedFlow<Request> = _requests

    fun request(whom: String, postId: String?) {
        _requests.tryEmit(Request(whom, postId))
    }
}
