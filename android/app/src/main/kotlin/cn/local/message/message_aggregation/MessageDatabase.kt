package cn.local.message.message_aggregation

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessageDatabase private constructor(context: Context) : SQLiteOpenHelper(context, "inbox.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE messages (
            id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL,
            sender TEXT NOT NULL, receivedAt INTEGER NOT NULL, isRead INTEGER NOT NULL DEFAULT 0,
            archived INTEGER NOT NULL DEFAULT 0, target TEXT NOT NULL, accountId TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX message_time ON messages(receivedAt DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(id: String, source: String, title: String, body: String, sender: String,
               receivedAt: Long, target: String, accountId: String = "") {
        val values = ContentValues().apply {
            put("id", id); put("source", source); put("title", title.take(1000)); put("body", body.take(16000))
            put("sender", sender.take(1000)); put("receivedAt", receivedAt); put("target", target)
            put("accountId", accountId)
        }
        // Replayed events must not reset local read/archive decisions.
        writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun list(): List<Map<String, Any>> = readableDatabase.rawQuery("SELECT * FROM messages ORDER BY receivedAt DESC", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(cursor.columnNames.associateWith { name ->
                    val index = cursor.getColumnIndexOrThrow(name)
                    when (name) {
                        "receivedAt" -> cursor.getLong(index)
                        "isRead", "archived" -> cursor.getInt(index) != 0
                        else -> cursor.getString(index)
                    }
                })
            }
        }
    }
    fun update(id: String, read: Boolean?, archived: Boolean?) {
        val values = ContentValues().apply {
            read?.let { put("isRead", if (it) 1 else 0) }
            archived?.let { put("archived", if (it) 1 else 0) }
        }
        if (values.size() > 0) writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }
    fun removeAccount(id: String) = writableDatabase.delete("messages", "accountId = ?", arrayOf(id))
    companion object {
        @Volatile private var instance: MessageDatabase? = null
        fun get(context: Context): MessageDatabase = instance ?: synchronized(this) {
            instance ?: MessageDatabase(context.applicationContext).also { instance = it }
        }
    }
}
