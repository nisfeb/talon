package io.nisfeb.talon.call

/** No native call screen on desktop — incoming calls render in-app. */
actual fun bindNativeCallActions(controller: CallController) = Unit
