package com.payandplan.app.util

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("payplan_prefs", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- account

    var serverUrl: String
        get() = sp.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    var userId: String
        get() = sp.getString("user_id", "") ?: ""
        set(v) = sp.edit().putString("user_id", v).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v).apply()

    var userEmail: String
        get() = sp.getString("user_email", "") ?: ""
        set(v) = sp.edit().putString("user_email", v).apply()

    var calendarId: String
        get() = sp.getString("calendar_id", "") ?: ""
        set(v) = sp.edit().putString("calendar_id", v).apply()

    /** cached calendars + members, as the JSON the server sent */
    var calendarsJson: String
        get() = sp.getString("calendars_json", "[]") ?: "[]"
        set(v) = sp.edit().putString("calendars_json", v).apply()

    /**
      * Schema the local cache was built with. When Room throws the cache away on an upgrade
      * the sync cursors have to go with it, otherwise the app asks the server for "changes
      * since yesterday" against an empty database and looks empty.
      */
    /** The server said it can read receipts; without it the scanner card stays hidden. */
    var receiptsEnabled: Boolean
        get() = sp.getBoolean("receipts_enabled", false)
        set(v) = sp.edit().putBoolean("receipts_enabled", v).apply()

    /** Shopping list shown as photo tiles instead of rows. */
    var listMosaic: Boolean
        get() = sp.getBoolean("list_mosaic", false)
        set(v) = sp.edit().putBoolean("list_mosaic", v).apply()

    var schemaVersion: Int
        get() = sp.getInt("schema_version", 0)
        set(v) = sp.edit().putInt("schema_version", v).apply()

    /** Forgets how far the sync got, so the next one pulls the whole calendar again. */
    fun clearSyncCursors() {
        val editor = sp.edit()
        sp.all.keys.filter { it.startsWith("since_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun lastSync(calendarId: String): Long = sp.getLong("since_$calendarId", 0L)
    fun setLastSync(calendarId: String, value: Long) =
        sp.edit().putLong("since_$calendarId", value).apply()

    fun signOut() {
        sp.edit()
            .remove("token").remove("user_id").remove("user_name").remove("user_email")
            .remove("calendar_id").remove("calendars_json")
            .apply()
    }

    // ---------------------------------------------------------------- defaults

    var currency: String
        get() = sp.getString("currency", "EUR") ?: "EUR"
        set(v) = sp.edit().putString("currency", v).apply()

    /** minutes from midnight */
    var defaultTime: Int
        get() = sp.getInt("default_time", 9 * 60)
        set(v) = sp.edit().putInt("default_time", v).apply()

    var defaultRemindDaysBefore: Int
        get() = sp.getInt("remind_days", 1)
        set(v) = sp.edit().putInt("remind_days", v).apply()

    /** minutes between nag repeats, 0 = single shot */
    var defaultNagMinutes: Int
        get() = sp.getInt("nag_minutes", 60)
        set(v) = sp.edit().putInt("nag_minutes", v).apply()

    var requireReceipt: Boolean
        get() = sp.getBoolean("require_receipt", true)
        set(v) = sp.edit().putBoolean("require_receipt", v).apply()

    var weekStartsMonday: Boolean
        get() = sp.getBoolean("week_monday", true)
        set(v) = sp.edit().putBoolean("week_monday", v).apply()

    companion object {
        const val DEFAULT_SERVER = "https://pay.achianes.net"
    }
}
