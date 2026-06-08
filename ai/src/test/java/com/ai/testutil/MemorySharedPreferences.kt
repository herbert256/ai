package com.ai.testutil

import android.content.SharedPreferences

/**
 * A pure-JVM in-memory [SharedPreferences] for unit tests. The app's unit
 * tests run without Robolectric (`isReturnDefaultValues = true`), so the real
 * Android prefs return only defaults — this fake gives load/save code an
 * actual backing store to round-trip through.
 *
 * Shared by every settings-persistence test (usage stats, GeneralSettings
 * parity, …) so the fake lives in exactly one place.
 */
class MemorySharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(values)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor = apply {
            key?.let {
                removals += it
                pending.remove(it)
            }
        }
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean = true
            .also { apply() }
        override fun apply() {
            if (clear) target.clear()
            removals.forEach { target.remove(it) }
            pending.forEach { (key, value) ->
                if (value == null) target.remove(key) else target[key] = value
            }
        }

        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
            key?.let {
                pending[it] = value
                removals.remove(it)
            }
        }
    }
}
