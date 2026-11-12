package cn.local.message.message_aggregation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import android.util.Log

object SmsSync {
    fun eventId(sender: String, sentAt: Long, body: String) =
        "sms:event:" + MessageRules.fingerprint(sender, sentAt.toString(), body)

    @Synchronized fun collect(context: Context) {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("sms", false) ||
            context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("sms_sync", Context.MODE_PRIVATE)
        val previous = prefs.getLong("last_id", -1)
        val scanStartedAt = System.currentTimeMillis()
        // Revisit a short time window: providers may commit rows out of ID order or reuse IDs.
        // Existing provider aliases make overlap safe for local read choices.
        val overlap = prefs.getLong("last_scan_at", scanStartedAt - TimeUnit.DAYS.toMillis(7)) - TimeUnit.MINUTES.toMillis(5)
        // Initial import: unread messages from the last seven days; subsequent import: every new inbox row.
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        var highest = previous
        var changed = false
        try {
            if (previous < 0) {
                checkNotNull(context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf("_id"), null, null, "_id DESC")) { "短信收件箱暂时不可用" }.use {
                    if (it.moveToFirst()) highest = it.getLong(0)
                }
            }
            checkNotNull(context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "read", "date_sent"),
                if (previous < 0) "date >= ?" else "_id > ? OR date >= ?",
                if (previous < 0) arrayOf(cutoff.toString()) else arrayOf(previous.toString(), overlap.toString()), "_id ASC")) {
                "短信收件箱暂时不可用"
            }.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    highest = maxOf(highest, id)
                    if (previous < 0 && cursor.getInt(4) != 0) continue
                    val sender = cursor.getString(1).orEmpty()
                    val body = cursor.getString(2).orEmpty()
                    val sentAt = cursor.getLong(5)
                    changed = MessageDatabase.get(context).insertSms("sms:$id", sender, body, cursor.getLong(3),
                        sentAt.takeIf { it > 0 }?.let { eventId(sender, it, body) }) || changed
                }
            }
            // Do not replay old messages when initially the inbox is empty.
            check(prefs.edit().putLong("last_id", maxOf(highest, 0)).putLong("last_scan_at", scanStartedAt).commit())
        } finally {
            // Earlier rows are already committed even if a later provider read fails.
            if (changed) BridgeEvents.changed()
        }
    }
}
class SmsReceiver : BroadcastReceiver() {
    companion object { private val writer = Executors.newSingleThreadExecutor() }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("sms", false)) return
        val pending = goAsync()
        val app = context.applicationContext
        val receivedAt = System.currentTimeMillis()
        writer.execute {
            var changed = false
            try {
                // Persist the broadcast before depending on a deferred worker or the default app's provider write.
                synchronized(SmsSync) {
                    if (app.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("sms", false) &&
                        app.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
                        parts.groupBy { it.originatingAddress.orEmpty() }.forEach { (sender, messages) ->
                            val body = messages.joinToString("") { it.messageBody.orEmpty() }
                            val sentAt = messages.first().timestampMillis
                            val eventId = SmsSync.eventId(sender, sentAt, body)
                            // Persist immediately. Provider-first delivery resolves through the same alias table.
                            changed = MessageDatabase.get(app).insertSms(eventId, sender, body, receivedAt) || changed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SmsReceiver", "SMS broadcast persistence failed", e)
            } finally {
                if (changed) BridgeEvents.changed()
                try {
                    WorkManager.getInstance(app).enqueue(OneTimeWorkRequestBuilder<SmsWorker>()
                        .setInitialDelay(3, TimeUnit.SECONDS).build())
                } catch (e: RuntimeException) {
                    Log.w("SmsReceiver", "Provider reconciliation could not be scheduled", e)
                } finally { pending.finish() }
            }
        }
    }
}
class SmsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        SmsSync.collect(applicationContext)
        Result.success()
    }
        catch (_: SecurityException) { Result.failure() }
        catch (_: Exception) { Result.retry() }
}
