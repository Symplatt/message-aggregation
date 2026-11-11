package cn.local.message.message_aggregation

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object ListenerConnection {
    private val handler = Handler(Looper.getMainLooper())
    private var recovery: Runnable? = null

    fun allowed(context: Context): Boolean {
        val component = ComponentName(context, MessageNotificationListener::class.java)
        return if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }
    }

    fun wanted(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return allowed(context) && (prefs.getBoolean("wechat", true) || prefs.getBoolean("qq", true))
    }

    fun connected() {
        recovery?.let { handler.removeCallbacks(it) }
        recovery = null
    }

    fun ensure(context: Context) {
        val app = context.applicationContext
        handler.post {
            val component = ComponentName(app, MessageNotificationListener::class.java)
            // Migrate a component left disabled by an interrupted older recovery.
            if (app.packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                app.packageManager.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            if (MessageNotificationListener.connected || !wanted(app)) {
                connected()
                return@post
            }
            if (recovery != null) return@post
            var attempts = 0
            val task = object : Runnable {
                override fun run() {
                    if (recovery !== this) return
                    if (MessageNotificationListener.connected || !wanted(app)) {
                        connected()
                        return
                    }
                    attempts++
                    Log.i("ListenerConnection", "Recovery attempt $attempts")
                    try {
                        // Disabling the component can make Android remove its notification access.
                        // Never toggle it to force a reset; use the supported rebind API only.
                        NotificationListenerService.requestRebind(component)
                    } catch (e: RuntimeException) { Log.w("ListenerConnection", "Recovery failed", e) }
                    if (recovery === this) handler.postDelayed(this, minOf(60000L, 5000L * attempts))
                }
            }
            recovery = task
            handler.post(task)
        }
    }

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (wanted(context)) {
            manager.enqueueUniquePeriodicWork("listener_connection", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ListenerConnectionWorker>(15, TimeUnit.MINUTES).build())
        } else manager.cancelUniqueWork("listener_connection")
    }
}

class ListenerConnectionWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!ListenerConnection.wanted(applicationContext)) return Result.success()
        ListenerConnection.ensure(applicationContext)
        // Keep the worker alive through the first reset, even if there is no activity process to host the retry.
        repeat(15) {
            if (isStopped) return Result.retry()
            if (MessageNotificationListener.connected || !ListenerConnection.wanted(applicationContext)) return Result.success()
            try { Thread.sleep(1000) }
            catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result.retry()
            }
        }
        return Result.retry()
    }
}
