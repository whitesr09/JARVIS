package com.nshd.jarvis

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Plain (non-secret) settings. The file name is kept from the original starter. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    fun s(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    fun i(k: String, d: Int = 0): Int = sp.getInt(k, d)
    fun b(k: String, d: Boolean = false): Boolean = sp.getBoolean(k, d)
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    fun remove(k: String) = sp.edit().remove(k).apply()
}

/** API keys are encrypted with an AES-GCM key held in the Android Keystore. */
object SecureStore {
    private const val ALIAS = "zara_secret_v1"
    private const val FILE = "zara_secure"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (value.isBlank()) { sp.edit().remove(name).apply(); return }
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            sp.edit().putString(name, packed).apply()
        } catch (_: Exception) {
            // Never fall back to plain text storage.
            sp.edit().remove(name).apply()
        }
    }

    fun get(ctx: Context, name: String): String {
        val packed = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(name, null) ?: return ""
        return try {
            val parts = packed.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun has(ctx: Context, name: String) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).contains(name)
    fun clearAll(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()

    /** One-time migration of the starter's plain-text key into encrypted storage. */
    fun migrateLegacy(ctx: Context, p: Prefs) {
        val old = p.s("api_key")
        if (old.isNotBlank()) {
            put(ctx, "gemini_key", old)
            p.remove("api_key")
        }
    }
}

data class Memory(val id: Long, val fact: String, val category: String, val created: Long)
data class TaskRow(val id: Long, val at: Long, val app: String, val summary: String, val status: String, val hint: String)
data class ConvoRow(val id: Int, val preview: String, val at: Long)

class ZDb(ctx: Context) : SQLiteOpenHelper(ctx, "jarvis.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) = ensure(db)
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) = ensure(db)

    private fun ensure(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS chat(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, at INTEGER)")
        addCol(db, "chat", "convo", "INTEGER DEFAULT 1")
        db.execSQL("CREATE TABLE IF NOT EXISTS memories(id INTEGER PRIMARY KEY AUTOINCREMENT, fact TEXT UNIQUE)")
        addCol(db, "memories", "category", "TEXT DEFAULT 'Other'")
        addCol(db, "memories", "created", "INTEGER DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, app TEXT, summary TEXT, status TEXT, hint TEXT)")
    }

    private fun addCol(db: SQLiteDatabase, table: String, col: String, def: String) {
        val c = db.rawQuery("PRAGMA table_info($table)", null)
        var has = false
        while (c.moveToNext()) if (c.getString(1) == col) has = true
        c.close()
        if (!has) db.execSQL("ALTER TABLE $table ADD COLUMN $col $def")
    }

    // ---- chat ----
    fun addMsg(convo: Int, role: String, text: String) {
        writableDatabase.execSQL("INSERT INTO chat(role,text,at,convo) VALUES(?,?,?,?)", arrayOf<Any>(role, text, System.currentTimeMillis(), convo))
    }
    fun messages(convo: Int, limit: Int = 200): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val c = readableDatabase.rawQuery("SELECT role,text FROM chat WHERE convo=? ORDER BY id DESC LIMIT ?", arrayOf(convo.toString(), limit.toString()))
        while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        c.close()
        out.reverse()
        return out
    }
    fun maxConvo(): Int {
        val c = readableDatabase.rawQuery("SELECT MAX(convo) FROM chat", null)
        val v = if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 1
        c.close(); return v
    }
    fun convos(query: String = ""): List<ConvoRow> {
        val out = ArrayList<ConvoRow>()
        val q = "%" + query.trim() + "%"
        val c = readableDatabase.rawQuery(
            "SELECT convo, MIN(id), MAX(at) FROM chat WHERE convo IN (SELECT convo FROM chat WHERE text LIKE ?) GROUP BY convo ORDER BY MAX(id) DESC LIMIT 60", arrayOf(q))
        val ids = ArrayList<Triple<Int, Long, Long>>()
        while (c.moveToNext()) ids.add(Triple(c.getInt(0), c.getLong(1), c.getLong(2)))
        c.close()
        for ((convo, _, at) in ids) {
            val p = readableDatabase.rawQuery("SELECT text FROM chat WHERE convo=? AND role='user' ORDER BY id ASC LIMIT 1", arrayOf(convo.toString()))
            val preview = if (p.moveToFirst()) p.getString(0) else "(no messages)"
            p.close()
            out.add(ConvoRow(convo, preview.take(80), at))
        }
        return out
    }
    fun deleteConvo(convo: Int) { writableDatabase.execSQL("DELETE FROM chat WHERE convo=?", arrayOf<Any>(convo)) }
    fun clearAllChats() { writableDatabase.execSQL("DELETE FROM chat") }

    // ---- memory ----
    fun addMemory(fact: String, category: String): Boolean {
        val cv = ContentValues().apply { put("fact", fact.trim()); put("category", category); put("created", System.currentTimeMillis()) }
        return writableDatabase.insertWithOnConflict("memories", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun memories(query: String = "", category: String = ""): List<Memory> {
        val where = ArrayList<String>(); val args = ArrayList<String>()
        if (query.isNotBlank()) { where.add("fact LIKE ?"); args.add("%" + query.trim() + "%") }
        if (category.isNotBlank()) { where.add("category=?"); args.add(category) }
        val sql = "SELECT id,fact,COALESCE(category,'Other'),COALESCE(created,0) FROM memories" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY id DESC"
        val out = ArrayList<Memory>()
        val c = readableDatabase.rawQuery(sql, args.toTypedArray())
        while (c.moveToNext()) out.add(Memory(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
        c.close(); return out
    }
    fun updateMemory(id: Long, fact: String, category: String) {
        val cv = ContentValues().apply { put("fact", fact.trim()); put("category", category) }
        try { writableDatabase.update("memories", cv, "id=?", arrayOf(id.toString())) } catch (_: Exception) {}
    }
    fun deleteMemory(id: Long) { writableDatabase.execSQL("DELETE FROM memories WHERE id=?", arrayOf<Any>(id)) }
    fun clearMemories() { writableDatabase.execSQL("DELETE FROM memories") }

    // ---- task journal (status only, never secret content) ----
    fun logTask(app: String, summary: String, status: String, hint: String) {
        writableDatabase.execSQL("INSERT INTO tasks(at,app,summary,status,hint) VALUES(?,?,?,?,?)",
            arrayOf<Any>(System.currentTimeMillis(), app, summary.take(160), status, hint.take(200)))
    }
    fun tasks(limit: Int = 60): List<TaskRow> {
        val out = ArrayList<TaskRow>()
        val c = readableDatabase.rawQuery("SELECT id,at,COALESCE(app,''),COALESCE(summary,''),COALESCE(status,''),COALESCE(hint,'') FROM tasks ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
        while (c.moveToNext()) out.add(TaskRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)))
        c.close(); return out
    }
    fun clearTasks() { writableDatabase.execSQL("DELETE FROM tasks") }
}
