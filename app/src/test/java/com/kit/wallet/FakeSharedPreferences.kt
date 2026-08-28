package com.kit.wallet

import android.content.SharedPreferences

/**
 * In-memory [SharedPreferences] honouring the commit contract, with a switch that makes
 * every commit fail so the fail-closed paths can be exercised.
 */
internal class FakeSharedPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    var failCommits = false

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        throw UnsupportedOperationException()

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            throw UnsupportedOperationException()

        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }

        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }

        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }

        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }

        override fun remove(key: String?) = apply { removals.add(key!!) }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            if (failCommits) return false
            if (clearAll) values.clear()
            removals.forEach(values::remove)
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
