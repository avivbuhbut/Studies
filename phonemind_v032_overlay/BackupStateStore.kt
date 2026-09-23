package com.aviv.phonemind

import android.content.Context
import org.json.JSONArray

class BackupStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "drive_persistent_backup",
        Context.MODE_PRIVATE
    )

    fun activate(categories: Set<String>, deleteAfter: Boolean) {
        val arr = JSONArray()
        categories.sorted().forEach { arr.put(it) }
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_CATEGORIES, arr.toString())
            .putBoolean(KEY_DELETE_AFTER, deleteAfter)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_MESSAGE, "ממתין ל-Wi-Fi")
            .apply()
    }

    fun isActive(): Boolean = prefs.getBoolean(KEY_ACTIVE, false)

    fun categories(): Set<String> {
        val raw = prefs.getString(KEY_CATEGORIES, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun deleteAfter(): Boolean = prefs.getBoolean(KEY_DELETE_AFTER, false)

    fun setLastMessage(message: String) {
        prefs.edit()
            .putString(KEY_LAST_MESSAGE, message)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    fun lastMessage(): String? = prefs.getString(KEY_LAST_MESSAGE, null)

    fun complete() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_LAST_MESSAGE, "הגיבוי הושלם")
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    fun cancel() {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_LAST_MESSAGE, "הגיבוי נעצר על ידי המשתמש")
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val KEY_ACTIVE = "active"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_DELETE_AFTER = "delete_after"
        private const val KEY_STARTED_AT = "started_at"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_LAST_MESSAGE = "last_message"
    }
}
