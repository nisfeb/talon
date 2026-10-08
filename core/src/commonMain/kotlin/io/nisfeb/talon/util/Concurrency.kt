package io.nisfeb.talon.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Minimal multiplatform stand-ins for the handful of
 * `java.util.concurrent.ConcurrentHashMap` operations commonMain used
 * (`ConcurrentHashMap` is JVM-only). Lock-guarded rather than lock-free
 * — correct on every target and fine at our sizes: these hold a few
 * dozen entries with low contention (an SSE listener plus a couple of
 * coroutines). Reach for something fancier only if profiling says so.
 */
class ConcurrentMap<K, V> {
    private val lock = SynchronizedObject()
    private val map = HashMap<K, V>()

    operator fun get(key: K): V? = synchronized(lock) { map[key] }
    operator fun set(key: K, value: V) { synchronized(lock) { map[key] = value } }
    fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    fun getOrPut(key: K, default: () -> V): V =
        synchronized(lock) { map.getOrPut(key, default) }
    /** [key]'s value replaced by [f] of it, in one step; null removes it. Returns what [f] made. */
    fun update(key: K, f: (V?) -> V?): V? = synchronized(lock) {
        f(map[key]).also { if (it == null) map.remove(key) else map[key] = it }
    }
    fun clear() { synchronized(lock) { map.clear() } }
}

/**
 * One [V] for the [K] in use, made on first ask and remade when the key
 * changes: a flow several screens share for the database signed in to.
 * One slot, so switching ships drops the old one rather than keeping it.
 */
class OneSlot<K : Any, V : Any>(private val make: (K) -> V) {
    private val lock = SynchronizedObject()
    private var key: K? = null
    private var value: V? = null

    fun of(k: K): V = synchronized(lock) {
        value?.takeIf { key === k } ?: make(k).also { key = k; value = it }
    }
}

class ConcurrentSet<E> {
    private val lock = SynchronizedObject()
    private val set = HashSet<E>()

    fun add(element: E): Boolean = synchronized(lock) { set.add(element) }
    fun contains(element: E): Boolean = synchronized(lock) { set.contains(element) }
    fun remove(element: E): Boolean = synchronized(lock) { set.remove(element) }
    fun clear() { synchronized(lock) { set.clear() } }
    fun toList(): List<E> = synchronized(lock) { set.toList() }
}
