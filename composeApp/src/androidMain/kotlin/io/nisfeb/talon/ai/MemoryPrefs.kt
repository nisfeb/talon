package io.nisfeb.talon.ai

import android.content.SharedPreferences

/**
 * Settings that last the session, for a device whose encrypted store
 * will not open.
 *
 * Not a file. What this holds is the owner's API keys, and writing
 * those in the clear to recover from a broken keystore would be a
 * worse bargain than losing them: the ship has them where sync is on,
 * and typing them again is a minute. What matters is that the app runs
 * at all, which it did not when the store threw while the application
 * object was still being built.
 */
internal class MemoryPrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
        l?.let { listeners += it }
    }

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
        l?.let { listeners -= it }
    }

    override fun edit(): SharedPreferences.Editor = Edit()

    private inner class Edit : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var wipe = false

        private fun put(key: String?, v: Any?): SharedPreferences.Editor {
            key?.let { pending[it] = v }
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, REMOVED)
        override fun clear(): SharedPreferences.Editor { wipe = true; return this }

        override fun commit(): Boolean {
            val changed = mutableListOf<String>()
            synchronized(values) {
                if (wipe) {
                    changed += values.keys
                    values.clear()
                }
                for ((k, v) in pending) {
                    if (v === REMOVED) values.remove(k) else values[k] = v
                    changed += k
                }
            }
            listeners.toList().forEach { l -> changed.forEach { l.onSharedPreferenceChanged(this@MemoryPrefs, it) } }
            return true
        }

        override fun apply() { commit() }
    }

    private companion object {
        /** Distinguishes "take it out" from "set it to null". */
        private val REMOVED = Any()
    }
}
