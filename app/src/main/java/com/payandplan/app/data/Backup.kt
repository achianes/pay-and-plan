package com.payandplan.app.data

import android.content.Context
import android.content.Intent
import com.payandplan.app.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Everything in one .zip the user saves wherever they like (Google Drive, another cloud, the
 * phone): the database, the attached files and the settings. The system file picker talks to
 * Drive, so the app needs no Google account, key or project of its own. The server login is
 * never in it.
 */
object Backup {
    private const val MARKER = "payandplan-backup.json"
    private const val DB = "payandplan.db"
    private const val FILES = "attachments"

    fun export(ctx: Context, db: AppDatabase, out: OutputStream) {
        // fold the write-ahead log into the main file, so one file is the whole database
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val prefs = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val values = JSONObject()
        prefs.all.forEach { (k, v) ->
            val o = JSONObject()
            when (v) {
                is String -> o.put("s", v)
                is Boolean -> o.put("b", v)
                is Int -> o.put("i", v)
                is Long -> o.put("l", v)
                is Float -> o.put("f", v.toDouble())
                is Set<*> -> o.put("set", JSONArray(v.map { it.toString() }))
                else -> return@forEach
            }
            values.put(k, o)
        }
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write(
                JSONObject().put("version", 1).put("schema", AppDatabase.VERSION)
                    .put("created", System.currentTimeMillis()).put("prefs", values).toString().toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("db/$DB"))
            ctx.getDatabasePath(DB).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            File(ctx.filesDir, FILES).listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry("$FILES/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Replaces the data with the copy, then restarts the app so the database opens afresh.
     * Throws IOException, before touching anything, when the file is not one of ours.
     */
    fun restore(ctx: Context, input: InputStream) {
        val staging = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var meta: JSONObject? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == MARKER -> meta = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    // only our two folders, plain file names: nothing can escape the app's storage
                    (name == "db/$DB" || name.startsWith("$FILES/")) &&
                        !name.contains("..") && name.count { it == '/' } == 1 -> {
                        val f = File(staging, name)
                        f.parentFile?.mkdirs()
                        f.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val m = meta ?: throw IOException("This is not a Pay & Plan copy")
        val dbFile = File(staging, "db/$DB")
        if (!dbFile.exists()) throw IOException("The copy has no database")
        if (m.optInt("schema") > AppDatabase.VERSION) throw IOException("This copy comes from a newer Pay & Plan: update the app first")

        AppDatabase.close()
        val target = ctx.getDatabasePath(DB)
        listOf("", "-wal", "-shm", "-journal").forEach { File(target.path + it).delete() }
        dbFile.copyTo(target, overwrite = true)

        val files = File(ctx.filesDir, FILES).apply { deleteRecursively(); mkdirs() }
        File(staging, FILES).listFiles()?.forEach { it.copyTo(File(files, it.name), overwrite = true) }

        val values = m.getJSONObject("prefs")
        val edit = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().clear()
        values.keys().forEach { k ->
            val o = values.getJSONObject(k)
            when {
                o.has("s") -> edit.putString(k, o.getString("s"))
                o.has("b") -> edit.putBoolean(k, o.getBoolean("b"))
                o.has("i") -> edit.putInt(k, o.getInt("i"))
                o.has("l") -> edit.putLong(k, o.getLong("l"))
                o.has("f") -> edit.putFloat(k, o.getDouble("f").toFloat())
                o.has("set") -> o.getJSONArray("set").let { a -> edit.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        edit.commit()
        staging.deleteRecursively()
    }

    /** A fresh process: the repository, the database and the alarms all start from the copy. */
    fun restart(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) ctx.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}
