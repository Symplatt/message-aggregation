package cn.local.message.message_aggregation

import android.app.Notification
import android.app.PendingIntent
import android.util.Log
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MessageNotificationListener : NotificationListenerService() {
    private val writer = Executors.newSingleThreadExecutor()
    private var destroyed = false
    override fun onCreate() {
        super.onCreate()
        Log.i("ListenerConnection", "Listener created")
    }
    override fun onListenerConnected() {
        if (destroyed) return
        instance = this
        connected = true
        ListenerConnection.connected()
        ListenerConnection.schedule(this)
        CollectionService.update(this)
        Log.i("ListenerConnection", "Listener connected")
        replay()
        BridgeEvents.changed()
    }
    override fun onListenerDisconnected() {
        // A late callback from an old service must not clear the replacement service's state.
        if (destroyed || (instance != null && instance !== this)) return
        instance = null
        connected = false
        actions.clear()
        Log.i("ListenerConnection", "Listener disconnected")
        BridgeEvents.changed()
        ListenerConnection.ensure(this)
    }
    override fun onDestroy() {
        destroyed = true
        if (instance === this) {
            instance = null
            connected = false
            actions.clear()
            BridgeEvents.changed()
            ListenerConnection.ensure(this)
        }
        writer.shutdown()
        Log.i("ListenerConnection", "Listener destroyed")
        super.onDestroy()
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { actions.remove(sbn.key) }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (destroyed) return
        try {
            val source = MessageRules.notificationPackages[sbn.packageName] ?: return
            if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean(source, true)) return
            val n = sbn.notification
            n.contentIntent?.let { actions[sbn.key] = it }
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            @Suppress("DEPRECATION")
            val messages = (Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)).orEmpty() +
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    extras.getParcelableArray(Notification.EXTRA_MESSAGES)).orEmpty())
                .filter { !it.text.isNullOrBlank() }
            writer.execute {
                // Recheck after queueing in case collection was disabled meanwhile.
                if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean(source, true)) return@execute
                try {
                    val db = MessageDatabase.get(this)
                    var changed = false
                    if (messages.isNotEmpty()) {
                        messages.forEach { message ->
                            val body = message.text?.toString().orEmpty()
                            if (body.isNotBlank()) {
                                val sender = if (android.os.Build.VERSION.SDK_INT >= 28) message.senderPerson?.name?.toString().orEmpty()
                                    else @Suppress("DEPRECATION") (message.sender?.toString().orEmpty())
                                val id = MessageRules.fingerprint(source, sbn.key, message.timestamp.toString(), sender, body)
                                changed = db.insert(id, source, title.ifBlank { sender.ifBlank { "新消息" } }, body, sender,
                                    message.timestamp.takeIf { it > 0 } ?: sbn.postTime, sbn.key) || changed
                            }
                        }
                    } else {
                        // Some clients put real content only in a summary or leave expanded fields empty.
                        val body = listOf(
                            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"),
                            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
                        if (title.isNotBlank() || body.isNotBlank()) {
                            val time = n.`when`.takeIf { it > 0 } ?: sbn.postTime
                            changed = db.insert(MessageRules.fingerprint(source, sbn.key, time.toString(), title, body),
                                source, title.ifBlank { "新消息" }, body, title, time, sbn.key)
                        }
                    }
                    if (changed) BridgeEvents.changed()
                } catch (e: Exception) { Log.w("MessageListener", "Notification persistence failed", e) }
            }
        } catch (e: RuntimeException) { Log.w("MessageListener", "Notification callback failed", e) }
    }
    private fun replay() {
        if (destroyed || instance !== this || !connected) return
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
            .onFailure { Log.w("ListenerConnection", "Active notification replay failed", it) }
    }
    companion object {
        private var instance: MessageNotificationListener? = null
        @Volatile var connected = false
        val actions = ConcurrentHashMap<String, PendingIntent>()
        fun replayActive() { instance?.replay() }
    }
}
