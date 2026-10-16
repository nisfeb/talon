package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/** No recogniser on the JVM; the assistant is typed to on desktop. */
@Composable
actual fun rememberDictation(onResult: (String) -> Unit): (() -> Unit)? = null

/** No quick dictation on desktop ([isQuickDictationSupported]). */
@Composable
actual fun QuickDictationButtons() {}
