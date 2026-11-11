package cn.local.message.message_aggregation

import android.Manifest
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import androidx.work.*
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val connectionHandler = Handler(Looper.getMainLooper())
    private val checkConnection = object : Runnable {
        override fun run() {
            ListenerConnection.ensure(this@MainActivity)
            connectionHandler.postDelayed(this, 30000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("messages", "消息通知", NotificationManager.IMPORTANCE_DEFAULT))
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notificationPermissionRequested", false)) {
            prefs.edit().putBoolean("notificationPermissionRequested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 72)
        }
    }
    override fun onResume() {
        super.onResume()
        connectionHandler.removeCallbacks(checkConnection)
        checkConnection.run()
        ListenerConnection.schedule(this)
        scheduleSms()
        CollectionService.update(this)
    }
    override fun onPause() {
        connectionHandler.removeCallbacks(checkConnection)
        super.onPause()
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "message_aggregation/events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { BridgeEvents.sink = events }
                override fun onCancel(arguments: Any?) { BridgeEvents.sink = null }
            })
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "message_aggregation/native")
            .setMethodCallHandler { call, result -> handle(call, result) }
    }
    override fun onDestroy() { BridgeEvents.sink = null; executor.shutdown(); networkExecutor.shutdown(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) {
            scheduleSms()
            CollectionService.update(this)
            executor.execute { runCatching { SmsSync.collect(this) }; BridgeEvents.changed() }
        }
    }
    private fun background(result: MethodChannel.Result, network: Boolean = false, action: () -> Any?) {
        (if (network) networkExecutor else executor).execute {
            try { val value = action(); runOnUiThread { result.success(value) } }
            catch (e: Exception) {
                val message = when (e) {
                    is IllegalArgumentException -> e.message ?: "输入无效"
                    is SecurityException -> "系统未授予所需权限，请在接入管理中检查"
                    else -> "操作未完成，请检查权限、网络或本地存储后重试"
                }
                runOnUiThread { result.error("operation_failed", message, null) }
            }
        }
    }
    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "load" -> background(result) {
                    mapOf("messages" to MessageDatabase.get(this).list(), "status" to status(), "icons" to sourceIcons())
                }
                "status" -> background(result) { status() }
                "updateMessage" -> background(result) {
                    MessageDatabase.get(this).update(requireNotNull(call.argument<String>("id")), call.argument("isRead"), call.argument("archived"))
                    null
                }
                "updateMessages" -> background(result) {
                    val ids = requireNotNull(call.argument<List<String>>("ids"))
                    require(ids.isNotEmpty())
                    MessageDatabase.get(this).updateMessages(ids, call.argument("isRead"), call.argument("archived"))
                    null
                }
                "markAllRead" -> background(result) {
                    val source = call.argument<String>("source")
                    require(source == null || source in listOf("wechat", "qq", "sms", "qqmail", "netease", "gmail"))
                    MessageDatabase.get(this).markAllRead(source)
                }
                "pinMessage" -> background(result) {
                    MessageDatabase.get(this).setPinned(requireNotNull(call.argument<String>("id")), call.argument<Boolean>("pinned") == true)
                    null
                }
                "deleteMessages" -> background(result) {
                    val ids = requireNotNull(call.argument<List<String>>("ids"))
                    require(ids.isNotEmpty())
                    MessageDatabase.get(this).deleteMessages(ids)
                    null
                }
                "notificationSettings" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); result.success(null)
                }
                "appSettings" -> {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))); result.success(null)
                }
                "requestSms" -> {
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("sms", true).apply()
                    requestPermissions(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS), 71)
                    result.success(null)
                }
                "setSource" -> {
                    val source = requireNotNull(call.argument<String>("source"))
                    require(source in listOf("wechat", "qq", "sms"))
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean(source, call.argument<Boolean>("enabled") == true).apply()
                    scheduleSms(); ListenerConnection.schedule(this); ListenerConnection.ensure(this)
                    CollectionService.update(this); result.success(null)
                }
                "sync" -> background(result, network = true) { SmsSync.collect(this); MailSync.syncAll(this) }
                "saveAccount" -> background(result, network = true) {
                    synchronized(MailSync.lock) {
                        val address = requireNotNull(call.argument<String>("address")).trim().lowercase()
                        val provider = requireNotNull(call.argument<String>("provider"))
                        val secret = requireNotNull(call.argument<String>("secret")).trim()
                        require(secret.isNotEmpty()) { "请输入邮箱授权码" }
                        MessageRules.mailHost(provider, address)
                        val account = MailAccount(MessageRules.fingerprint(provider, address), provider, address, secret)
                        try { MailSync.validate(account) }
                        catch (e: Exception) { throw IllegalArgumentException(MailSync.friendlyError(e)) }
                        AccountStore(this).save(account)
                        MailSync.schedule(this)
                        MailSync.syncAll(this)
                        null
                    }
                }
                "removeAccount" -> background(result, network = true) {
                    synchronized(MailSync.lock) {
                        val id = requireNotNull(call.argument<String>("id"))
                        AccountStore(this).remove(id)
                        MessageDatabase.get(this).removeAccount(id)
                        val editor = getSharedPreferences("mail_sync", MODE_PRIVATE).edit()
                        listOf("_uid", "_validity", "_success", "_error").forEach { editor.remove(id + it) }
                        editor.commit()
                        if (AccountStore(this).accounts().isEmpty()) WorkManager.getInstance(this).cancelUniqueWork("mail_sync")
                        null
                    }
                }
                "openMessage" -> { result.success(openMessage(call)) }
                else -> result.notImplemented()
            }
        } catch (_: Exception) { result.error("unavailable", "系统无法执行此操作，请检查相关应用是否已安装", null) }
    }
    private fun scheduleSms() {
        val manager = WorkManager.getInstance(this)
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("sms", false)) {
            manager.enqueueUniquePeriodicWork("sms_sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SmsWorker>(15, TimeUnit.MINUTES).build())
        } else manager.cancelUniqueWork("sms_sync")
    }
    private val iconCache by lazy { loadIcons() }
    private fun sourceIcons(): Map<String, ByteArray> = iconCache
    private fun loadIcons(): Map<String, ByteArray> {
        val packages = mapOf(
            "wechat" to listOf("com.tencent.mm"), "qq" to listOf("com.tencent.mobileqq"),
            "qqmail" to listOf("com.tencent.androidqqmail"),
            "netease" to listOf("com.netease.mail", "com.netease.mobimail"),
            "gmail" to listOf("com.google.android.gm"),
            "sms" to listOfNotNull(Telephony.Sms.getDefaultSmsPackage(this))
        )
        return buildMap {
            packages.forEach { (source, candidates) ->
                for (pkg in candidates) {
                    val bytes = runCatching {
                        val drawable = packageManager.getApplicationIcon(pkg)
                        val bitmap = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, 64, 64)
                        drawable.draw(android.graphics.Canvas(bitmap))
                        java.io.ByteArrayOutputStream().use { output ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                            bitmap.recycle()
                            output.toByteArray()
                        }
                    }.getOrNull()
                    if (bytes != null) { put(source, bytes); break }
                }
            }
        }
    }
    private fun status(): Map<String, Any> {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val sync = getSharedPreferences("mail_sync", MODE_PRIVATE)
        val allowed = ListenerConnection.allowed(this)
        val accounts = run {
            // Display metadata never waits for network synchronization or decrypts credentials.
            AccountStore(this).metadata().map { a ->
                mapOf("id" to a.id, "provider" to a.provider, "address" to a.address,
                    "lastSync" to sync.getLong(a.id + "_success", 0), "error" to sync.getString(a.id + "_error", "").orEmpty())
            }
        }
        return mapOf(
            "notificationAccess" to allowed, "listenerConnected" to MessageNotificationListener.connected,
            "smsRead" to (checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED),
            "smsReceive" to (checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED),
            "wechat" to prefs.getBoolean("wechat", true), "qq" to prefs.getBoolean("qq", true),
            "sms" to prefs.getBoolean("sms", false), "accounts" to accounts
        )
    }
    private fun openMessage(call: MethodCall): String {
        val source = call.argument<String>("source").orEmpty()
        val target = call.argument<String>("target").orEmpty()
        if (source in listOf("wechat", "qq")) {
            MessageNotificationListener.actions[target]?.let {
                // Android 14+ requires sender opt-in to lend the visible activity's launch privileges.
                // Service/broadcast notification trampolines cannot open an activity reliably; use the launcher below.
                if (it.isActivity) {
                    try {
                        val options = ActivityOptions.makeBasic()
                        if (Build.VERSION.SDK_INT >= 36) {
                            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                        } else if (Build.VERSION.SDK_INT >= 34) {
                            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        }
                        it.send(this, 0, null, null, null, null, options.toBundle())
                        return "已请求打开原通知对应页面"
                    } catch (_: Exception) { }
                }
            }
        }
        if (source == "sms") {
            val intent = if (target.isNotBlank()) Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", target, null))
                else Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING)
            startActivity(intent); return "已打开短信应用"
        }
        val packages = when (source) {
            "wechat" -> listOf("com.tencent.mm")
            "qq" -> listOf("com.tencent.mobileqq")
            "qqmail" -> listOf("com.tencent.androidqqmail")
            "netease" -> listOf("com.netease.mail", "com.netease.mobimail")
            "gmail" -> listOf("com.google.android.gm")
            else -> emptyList()
        }
        packages.forEach { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it); return "已打开原应用，请在应用内查找消息"
        } }
        return "未找到对应应用，请先安装"
    }
}
