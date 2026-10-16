package io.nisfeb.talon.ui

import androidx.compose.runtime.Composable

/**
 * One spoken instruction, as text. Returns the thing to call to start
 * listening, or null where [isDictationSupported] is false; [onResult]
 * gets the recognised text once, when the speaker stops.
 */
@Composable
expect fun rememberDictation(onResult: (String) -> Unit): (() -> Unit)?

/**
 * Buttons that set up quick dictation's ways in (a Quick Settings tile,
 * a home-screen widget, Talon as the assistant), where
 * [isQuickDictationSupported]. Nothing elsewhere.
 */
@Composable
expect fun QuickDictationButtons()
