package io.nisfeb.talon.util

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One field of a [StateFlow], as a [StateFlow].
 *
 * `map` gives a plain flow, which a switch cannot read a current value
 * from, and `stateIn` wants a scope to live in. A setting that is one
 * field of a larger configuration needs neither: its value is always
 * there to be read, and it changes when the field does.
 */
fun <T, R> mapState(source: StateFlow<T>, transform: (T) -> R): StateFlow<R> =
    object : StateFlow<R> {
        override val value: R get() = transform(source.value)
        override val replayCache: List<R> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<R>): Nothing {
            source.map(transform).distinctUntilChanged().collect(collector)
            error("a state flow never completes")
        }
    }
