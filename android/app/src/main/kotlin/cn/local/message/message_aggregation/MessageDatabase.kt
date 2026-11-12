package cn.local.message.message_aggregation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessageDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "inbox.db", null, 3) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
            sender TEXT NOT NULL, receivedAt INTEGER NOT NULL, isRead INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0, target TEXT NOT NULL, accountId TEXT NOT NULL DEFAULT '',
            localStateAt INTEGER NOT NULL DEFAULT 0, isDeleted INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX message_time ON messages(receivedAt DESC)")
        createAliases(db)
        createPins(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN localStateAt INTEGER NOT NULL DEFAULT 0")
            createAliases(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
            createPins(db)
        }
    }
    private fun createPins(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pinned_conversations (conversationKey TEXT PRIMARY KEY)")
    }
    private fun conversationKey(source: String, title: String, id: String): String {
        val name = if (source == "qq") title.replace(Regex("\\s*[（(]\\d+条(?:新)?消息[）)]$"), "").trim() else title.trim()
        return if (source in listOf("wechat", "qq") && name.isNotEmpty() && name != "新消息")
            "$source\u0000$name" else "$source\u0000$id"
    }
    private fun createAliases(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE message_aliases (
            aliasId TEXT PRIMARY KEY, messageId TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE)""")
    }
    private fun resolveId(db: SQLiteDatabase, id: String): String? = db.rawQuery(
        "SELECT id FROM messages WHERE id = ? UNION ALL " +
            "SELECT messageId FROM message_aliases WHERE aliasId = ? LIMIT 1", arrayOf(id, id)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    fun insert(id: String, source: String, title: String, body: String, sender: String,
               receivedAt: Long, target: String, accountId: String = ""): Boolean {
        val values = ContentValues().apply {
            put("id", id); put("source", source); put("title", title.take(1000)); put("body", body.take(16000))
            put("sender", sender.take(1000)); put("receivedAt", receivedAt); put("target", target)
            put("accountId", accountId)
        }
        // Replayed events must not reset local read/archive decisions.
        return writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    @Synchronized fun list(): List<Map<String, Any>> {
        val db = readableDatabase
        val pins = db.rawQuery("SELECT conversationKey FROM pinned_conversations", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return db.rawQuery("SELECT * FROM messages WHERE isDeleted = 0 ORDER BY receivedAt DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val row = cursor.columnNames.associateWith { name ->
                        val index = cursor.getColumnIndexOrThrow(name)
                        when (name) {
                            "receivedAt", "localStateAt" -> cursor.getLong(index)
                            "isRead", "archived", "isDeleted" -> cursor.getInt(index) != 0
                            else -> cursor.getString(index)
                        }
                    }.toMutableMap()
                    row["isPinned"] = conversationKey(row["source"] as String, row["title"] as String, row["id"] as String) in pins
                    add(row)
                }
            }
        }
    }
    @Synchronized fun setPinned(id: String, pinned: Boolean) {
        val db = writableDatabase
        val target = resolveId(db, id) ?: throw IllegalArgumentException("该消息已不存在，请刷新列表")
        val key = db.rawQuery("SELECT source, title FROM messages WHERE id = ? AND isDeleted = 0", arrayOf(target)).use {
            require(it.moveToFirst()) { "该消息已删除，请刷新列表" }
            conversationKey(it.getString(0), it.getString(1), target)
        }
        if (pinned) db.execSQL("INSERT OR IGNORE INTO pinned_conversations VALUES (?)", arrayOf(key))
        else db.delete("pinned_conversations", "conversationKey = ?", arrayOf(key))
    }
    @Synchronized fun deleteMessages(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids.distinct()) {
                val target = resolveId(db, id) ?: continue
                // Retain only delivery identity so periodic replay cannot resurrect deleted content.
                db.update("messages", ContentValues().apply {
                    put("isDeleted", 1); put("localStateAt", System.currentTimeMillis())
                    put("title", ""); put("body", ""); put("sender", ""); put("target", "")
                }, "id = ?", arrayOf(target))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun update(id: String, read: Boolean?, archived: Boolean?) {
        val values = ContentValues().apply {
            read?.let { put("isRead", if (it) 1 else 0) }
            archived?.let { put("archived", if (it) 1 else 0) }
        }
        if (values.size() > 0) {
            val db = writableDatabase
            val target = resolveId(db, id) ?: throw IllegalArgumentException("该消息已不存在，请刷新列表")
            values.put("localStateAt", System.currentTimeMillis())
            db.update("messages", values, "id = ?", arrayOf(target))
        }
    }
    @Synchronized fun updateMessages(ids: List<String>, read: Boolean?, archived: Boolean?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.distinct().forEach { update(it, read, archived) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun markAllRead(source: String?): Int = writableDatabase.update("messages",
        ContentValues().apply { put("isRead", 1); put("localStateAt", System.currentTimeMillis()) },
        if (source == null) "isRead = 0" else "isRead = 0 AND source = ?",
        source?.let { arrayOf(it) })

    // Keep the first persisted ID stable while linking provider and broadcast identities.
    // Details may still be open when the provider row becomes available.
    @Synchronized fun insertSms(id: String, sender: String, body: String, receivedAt: Long, eventId: String? = null): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val providerRecord = resolveId(db, id)
            val eventRecord = eventId?.let { resolveId(db, it) }
            val stableId = providerRecord ?: eventRecord ?: id
            var changed = false
            if (providerRecord != null && eventRecord != null && providerRecord != eventRecord) {
                // Older versions could already contain both copies. Retain the latest explicit local choice.
                db.rawQuery("SELECT isRead, archived, localStateAt, isDeleted FROM messages WHERE id IN (?, ?) " +
                    "ORDER BY localStateAt DESC, isRead DESC LIMIT 1", arrayOf(providerRecord, eventRecord)).use {
                    if (it.moveToFirst()) db.update("messages", ContentValues().apply {
                        put("isRead", it.getInt(0)); put("archived", it.getInt(1)); put("localStateAt", it.getLong(2))
                        put("isDeleted", it.getInt(3))
                    }, "id = ?", arrayOf(stableId))
                }
                db.execSQL("UPDATE message_aliases SET messageId = ? WHERE messageId = ?", arrayOf(stableId, eventRecord))
                db.delete("messages", "id = ?", arrayOf(eventRecord))
                changed = true
            }
            changed = insert(stableId, "sms", sender.ifBlank { "短信" }, body, sender, receivedAt, sender) || changed
            db.execSQL("UPDATE messages SET title = '', body = '', sender = '', target = '' WHERE id = ? AND isDeleted = 1", arrayOf(stableId))
            for (alias in listOfNotNull(id, eventId, eventRecord).distinct().filter { it != stableId }) {
                db.insertWithOnConflict("message_aliases", null, ContentValues().apply {
                    put("aliasId", alias); put("messageId", stableId)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
            return changed
        } finally { db.endTransaction() }
    }
    @Synchronized fun removeAccount(id: String) = writableDatabase.delete("messages", "accountId = ?", arrayOf(id))
    companion object {
        @Volatile private var instance: MessageDatabase? = null
        fun get(context: Context): MessageDatabase = instance ?: synchronized(this) {
            instance ?: MessageDatabase(context.applicationContext).also { instance = it }
        }
    }
}
