package cn.local.message.message_aggregation

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object ListenerConnection {
    private val handler = Handler(Looper.getMainLooper())
    private var recovery: Runnable? = null
    private var rebindPending = false

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
        // An old connection callback may arrive while the unbind is still being processed.
        // Always finish the matching rebind before cancelling recovery.
        if (rebindPending) return
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
            var lastResetAt: Long? = null
            val task = object : Runnable {
                override fun run() {
                    if (recovery !== this) return
                    if (rebindPending) {
                        rebindPending = false
                        if (!wanted(app)) { connected(); return }
                        // This must run even if a late callback temporarily reports connected.
                        requestRebind(component)
                        handler.postDelayed(this, 5000)
                        return
                    }
                    if (MessageNotificationListener.connected || !wanted(app)) {
                        connected()
                        return
                    }
                    attempts++
                    Log.i("ListenerConnection", "Recovery attempt $attempts")
                    val now = SystemClock.elapsedRealtime()
                    if (Build.VERSION.SDK_INT >= 34 && attempts >= 2 &&
                        (lastResetAt == null || now - lastResetAt!! >= 60000)) {
                        try {
                            // A dead system binding can make repeated requestRebind calls no-ops.
                            // The component overload works without a live listener, preserves access,
                            // and avoids disabling the component (which can revoke access on OEM ROMs).
                            rebindPending = true
                            NotificationListenerService.requestUnbind(component)
                            lastResetAt = now
                            Log.i("ListenerConnection", "Released stale binding; rebind in 1s")
                            handler.postDelayed(this, 1000)
                            return
                        } catch (e: RuntimeException) {
                            rebindPending = false
                            Log.w("ListenerConnection", "Binding release failed", e)
                        }
                    }
                    requestRebind(component)
                    if (recovery === this) handler.postDelayed(this, minOf(60000L, 5000L * attempts))
                }
            }
            recovery = task
            handler.post(task)
        }
    }

    private fun requestRebind(component: ComponentName) {
        try {
            NotificationListenerService.requestRebind(component)
            Log.i("ListenerConnection", "Rebind requested")
        } catch (e: RuntimeException) { Log.w("ListenerConnection", "Recovery failed", e) }
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
        // Keep the worker alive through the first release/rebind when no activity is hosting recovery.
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
