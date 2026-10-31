package cn.local.message.message_aggregation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.work.*
import java.util.concurrent.TimeUnit

object SmsSync {
    @Synchronized fun collect(context: Context) {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("sms", false) ||
            context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("sms_sync", Context.MODE_PRIVATE)
        val previous = prefs.getLong("last_id", -1)
        // Initial import: unread messages from the last seven days; subsequent import: every new inbox row.
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        var highest = previous
        if (previous < 0) {
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf("_id"), null, null, "_id DESC")?.use {
                if (it.moveToFirst()) highest = it.getLong(0)
            }
        }
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "read"),
            if (previous < 0) "date >= ?" else "_id > ?",
            arrayOf(if (previous < 0) cutoff.toString() else previous.toString()), "_id ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                highest = maxOf(highest, id)
                if (previous < 0 && cursor.getInt(4) != 0) continue
                val sender = cursor.getString(1).orEmpty()
                MessageDatabase.get(context).insert("sms:$id", "sms", sender.ifBlank { "短信" },
                    cursor.getString(2).orEmpty(), sender, cursor.getLong(3), sender)
            }
        }
        // Do not replay old messages when initially the inbox is empty.
        prefs.edit().putLong("last_id", maxOf(highest, 0)).commit()
    }
}
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("sms", false)) return
        // The default SMS handler owns the provider write. Read after that write, using provider IDs for deduplication.
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SmsWorker>()
            .setInitialDelay(3, TimeUnit.SECONDS).build())
    }
}
class SmsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try { SmsSync.collect(applicationContext); BridgeEvents.changed(); Result.success() }
        catch (_: SecurityException) { Result.failure() }
        catch (_: Exception) { Result.retry() }
}
