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
    fun clear() { synchronized(lock) { map.clear() } }
}

class ConcurrentSet<E> {
    private val lock = SynchronizedObject()
    private val set = HashSet<E>()

    fun add(element: E): Boolean = synchronized(lock) { set.add(element) }
    fun contains(element: E): Boolean = synchronized(lock) { set.contains(element) }
    fun remove(element: E): Boolean = synchronized(lock) { set.remove(element) }
    fun clear() { synchronized(lock) { set.clear() } }
}
