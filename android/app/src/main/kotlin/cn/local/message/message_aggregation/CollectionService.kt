package cn.local.message.message_aggregation

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.*
import android.provider.Telephony
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// A visible, user-enabled collector keeps recovery independent of Flutter and periodic job delays.
class CollectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val collecting = AtomicBoolean(false)
    private var observing = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { collectSms() }
    }
    private val check = object : Runnable {
        override fun run() {
            if (!wanted(this@CollectionService)) { stopSelf(); return }
            ListenerConnection.ensure(this@CollectionService)
            MessageNotificationListener.replayActive()
            // Permission can change between checking it and registering the provider observer.
            try { updateObserver() }
            catch (e: RuntimeException) { Log.w("SmsSync", "Observer unavailable", e) }
            collectSms()
            handler.postDelayed(this, 15000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("collection", "后台接收", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(1001, Notification.Builder(this, "collection")
            .setSmallIcon(cn.local.message.message_aggregation.R.drawable.ic_aggregation)
            .setContentTitle("讯息聚合").setContentText("后台接收已开启")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build())
        Log.i("CollectionService", "Collector started")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(check)
        check.run()
        return START_STICKY
    }
    private fun updateObserver() {
        val enabled = smsWanted(this)
        if (enabled && !observing) {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
            observing = true
        } else if (!enabled && observing) {
            contentResolver.unregisterContentObserver(observer)
            observing = false
        }
    }
    private fun collectSms() {
        if (writer.isShutdown || !smsWanted(this) || !collecting.compareAndSet(false, true)) return
        writer.execute {
            try { SmsSync.collect(this) }
            catch (e: Exception) { Log.w("SmsSync", "Collector sync failed", e) }
            finally { collecting.set(false) }
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (observing) runCatching { contentResolver.unregisterContentObserver(observer) }
        writer.shutdown()
        Log.i("CollectionService", "Collector stopped")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object {
        private fun smsWanted(context: Context) =
            context.getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sms", false) &&
                context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        fun wanted(context: Context) = ListenerConnection.wanted(context) || smsWanted(context)
        fun update(context: Context) {
            val intent = Intent(context, CollectionService::class.java)
            if (!wanted(context)) { context.stopService(intent); return }
            try { context.startForegroundService(intent) }
            catch (e: RuntimeException) { Log.w("CollectionService", "Collector start unavailable", e) }
        }
    }
}

class CollectionRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            ListenerConnection.schedule(context)
            CollectionService.update(context)
        }
    }
}
