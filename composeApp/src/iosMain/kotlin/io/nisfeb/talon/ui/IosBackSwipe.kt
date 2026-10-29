package io.nisfeb.talon.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.nisfeb.talon.ui.theme.ThemePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The swipe back as the screen follows it. While a back swipe is under
 * way the app slides right with the finger, as a message follows the
 * finger in a reply swipe; let go past the mark and it goes back at
 * once, short of it and it springs back. Talon's own handlers report
 * the progress ([PlatformBackHandler]), so the back of one of
 * Material's sheets does not move the screen under it.
 */
internal object IosBackSwipe {
    /** How far the screen has followed the finger: 0 to 1 of its width. */
    val progress = Animatable(0f)
    var scope: CoroutineScope? = null

    fun follow(p: Float) { scope?.launch { progress.snapTo(p.coerceIn(0f, 1f)) } }
    fun settle() { scope?.launch { progress.animateTo(0f, spring()) } }
    fun done() { scope?.launch { progress.snapTo(0f) } }
}

/**
 * The app, under the swipe back. iOS's own edge swipe reaches the
 * handlers by itself (Compose's edge-pan recognizer); this adds a swipe
 * right from anywhere else, as a reply swipe starts anywhere on a
 * message. Not within [EDGE_BAND] of the left edge, which is iOS's, so
 * one swipe never starts two backs; and not at the top of the back
 * stack ([BackDepth] none), where the swipe opens the sections drawer.
 */
@Composable
fun IosBackSwipeHost(theme: ThemePreference, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    DisposableEffect(scope) {
        IosBackSwipe.scope = scope
        onDispose { if (IosBackSwipe.scope === scope) IosBackSwipe.scope = null }
    }
    val input = remember { DirectNavigationEventInput() }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    DisposableEffect(dispatcher) {
        dispatcher?.addInput(input)
        onDispose { dispatcher?.removeInput(input) }
    }
    val mode by theme.mode.collectAsState()
    val dark = when (mode) {
        ThemePreference.Mode.Dark -> true
        ThemePreference.Mode.Light -> false
        else -> isSystemInDarkTheme()
    }
    // What shows beside a sliding screen: not the screen behind it,
    // which is not drawn, but a plain panel in the app's light or dark.
    BoxWithConstraints(Modifier.fillMaxSize().background(if (dark) Color.Black else Color(0xFFD1D1D6))) {
        val width = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                // Before the layer, so the finger is read where it is,
                // not where the sliding screen has put it.
                .swipeBackAnywhere(input, width)
                .graphicsLayer { translationX = IosBackSwipe.progress.value * width },
        ) { content() }
    }
}

private fun Modifier.swipeBackAnywhere(input: DirectNavigationEventInput, width: Float): Modifier = pointerInput(input, width) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (BackDepth.depth == 0 || down.position.x < EDGE_BAND.toPx() || width <= 0f) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var dx = 0f
        var dy = 0f
        // The screen's children go first: a list scrolling, a reply
        // swipe (leftward only), anything sideways that scrolls.
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            dx += change.positionChange().x
            dy += change.positionChange().y
            if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
            if (dx < -slop) return@awaitEachGesture
            if (dx > slop) {
                change.consume()
                break
            }
        }
        val velocity = VelocityTracker()
        var travelled = dx
        input.backStarted(edgeEvent(down.position.x, down.position.y, travelled / width))
        val lifted = horizontalDrag(down.id) { change ->
            velocity.addPosition(change.uptimeMillis, change.position)
            travelled += change.positionChange().x
            change.consume()
            input.backProgressed(edgeEvent(change.position.x, change.position.y, travelled / width))
        }
        val dpPerSec = velocity.calculateVelocity().x / density
        if (lifted && backSwipeCommits((travelled / width).coerceIn(0f, 1f), dpPerSec)) input.backCompleted() else input.backCancelled()
    }
}

/** A swipe from the left, as iOS's own edge swipe reports one. */
private fun edgeEvent(x: Float, y: Float, progress: Float) =
    NavigationEvent(touchX = x, touchY = y, progress = progress.coerceIn(0f, 1f), swipeEdge = NavigationEvent.EDGE_LEFT)

/** The outer band of the left edge, where iOS's own edge swipe starts; the width Talon's old strip needed to clear it. */
private val EDGE_BAND = 32.dp
