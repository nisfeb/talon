package io.nisfeb.talon.call

/** No native call screen on Android — incoming calls render in-app. */
actual fun bindNativeCallActions(controller: CallController) = Unit
