package cn.local.message.message_aggregation

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MessageNotificationListener : NotificationListenerService() {
    private val writer = Executors.newSingleThreadExecutor()
    override fun onListenerConnected() {
        connected = true
        activeNotifications?.forEach { onNotificationPosted(it) }
        BridgeEvents.changed()
    }
    override fun onListenerDisconnected() { connected = false; actions.clear(); BridgeEvents.changed() }
    override fun onDestroy() { connected = false; actions.clear(); writer.shutdown(); super.onDestroy() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { actions.remove(sbn.key) }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val source = MessageRules.notificationPackages[sbn.packageName] ?: return
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean(source, true)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        n.contentIntent?.let { actions[sbn.key] = it }
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        @Suppress("DEPRECATION")
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)).orEmpty()
        writer.execute {
            // Recheck after queueing in case collection was disabled meanwhile.
            if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean(source, true)) return@execute
            val db = MessageDatabase.get(this)
            if (messages.isNotEmpty()) {
                messages.forEach { message ->
                    val body = message.text?.toString().orEmpty()
                    if (body.isNotBlank()) {
                        val sender = if (android.os.Build.VERSION.SDK_INT >= 28) message.senderPerson?.name?.toString().orEmpty()
                            else @Suppress("DEPRECATION") (message.sender?.toString().orEmpty())
                        val id = MessageRules.fingerprint(source, sbn.key, message.timestamp.toString(), sender, body)
                        db.insert(id, source, title.ifBlank { sender.ifBlank { "新消息" } }, body, sender,
                            message.timestamp.takeIf { it > 0 } ?: sbn.postTime, sbn.key)
                    }
                }
            } else {
                val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (title.isNotBlank() || body.isNotBlank()) {
                    val time = n.`when`.takeIf { it > 0 } ?: sbn.postTime
                    db.insert(MessageRules.fingerprint(source, sbn.key, time.toString(), title, body),
                        source, title.ifBlank { "新消息" }, body, title, time, sbn.key)
                }
            }
            BridgeEvents.changed()
        }
    }
    companion object {
        @Volatile var connected = false
        val actions = ConcurrentHashMap<String, PendingIntent>()
    }
}
