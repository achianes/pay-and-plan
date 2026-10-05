package com.payandplan.app.util

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("payplan_prefs", Context.MODE_PRIVATE)

    /**
     * The server token lives in a file of its own: the phone's backup and the saved copy take
     * payplan_prefs, never this one, so a login does not travel to Google's cloud or a zip.
     */
    private val auth = context.getSharedPreferences(AUTH_FILE, Context.MODE_PRIVATE)

    init {
        // older builds kept the token next to everything else
        sp.getString("token", null)?.let { old ->
            if (old.isNotBlank()) auth.edit().putString("token", old).apply()
            sp.edit().remove("token").apply()
        }
    }

    /** No account, no server: everything stays on this phone. */
    var localMode: Boolean
        get() = sp.getBoolean("local_mode", false)
        set(v) = sp.edit().putBoolean("local_mode", v).apply()

    // ---------------------------------------------------------------- account

    var serverUrl: String
        get() = sp.getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) = sp.edit().putString("server_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = auth.getString("token", "") ?: ""
        set(v) = auth.edit().putString("token", v).apply()

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

    /** Write an expense the calendar did not expect straight into its day. */
    var autoAddExpenses: Boolean
        get() = sp.getBoolean("auto_add_expenses", true)
        set(v) = sp.edit().putBoolean("auto_add_expenses", v).apply()

    /** Under this, an expense joins the day's single "small expenses" entry instead of its own. */
    var smallExpenseCents: Long
        get() = sp.getLong("small_expense_cents", 1000L)
        set(v) = sp.edit().putLong("small_expense_cents", v).apply()

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
        auth.edit().remove("token").apply()
        sp.edit()
            .remove("user_id").remove("user_name").remove("user_email")
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
        /** Nothing by default: a server is only for those who run their own. */
        const val DEFAULT_SERVER = ""
        const val FILE = "payplan_prefs"
        const val AUTH_FILE = "payplan_auth"
    }
}
