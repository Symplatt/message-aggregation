package cn.local.message.message_aggregation

import android.content.Context
import androidx.work.*
import com.sun.mail.imap.IMAPFolder
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.search.FlagTerm

object MailSync {
    // Serializes foreground sync, account changes, and WorkManager to prevent removed accounts reappearing.
    val lock = Any()
    private fun connect(account: MailAccount): Store {
        val host = MessageRules.mailHost(account.provider, account.address)
        val props = Properties().apply {
            setProperty("mail.store.protocol", "imaps")
            setProperty("mail.imaps.ssl.checkserveridentity", "true")
            setProperty("mail.imaps.connectiontimeout", "15000")
            setProperty("mail.imaps.timeout", "20000")
            setProperty("mail.imaps.writetimeout", "15000")
            setProperty("mail.imaps.peek", "true")
        }
        return Session.getInstance(props).getStore("imaps").also { store ->
            try { store.connect(host, 993, account.address, account.secret) }
            catch (e: Exception) { runCatching { store.close() }; throw e }
        }
    }
    private fun openInbox(store: Store, account: MailAccount): IMAPFolder {
        val folder = store.getFolder("INBOX") as IMAPFolder
        // NetEase requires a client ID on some accounts.
        if (account.provider == "netease") runCatching { folder.id(mapOf("name" to "MessageAggregation", "version" to "0.1.0")) }
        folder.open(Folder.READ_ONLY)
        return folder
    }
    fun validate(account: MailAccount) {
        val store = connect(account)
        try { openInbox(store, account).close(false) } finally { runCatching { store.close() } }
    }
    fun friendlyError(error: Exception): String = when (error) {
        is AuthenticationFailedException -> "登录失败，请检查 IMAP 开关和邮箱授权码"
        is IllegalArgumentException -> error.message ?: "邮箱配置无效"
        else -> "连接失败，请检查网络、IMAP 开关及客户端授权设置"
    }
    fun syncAll(context: Context): List<String> = synchronized(lock) {
        val prefs = context.getSharedPreferences("mail_sync", Context.MODE_PRIVATE)
        val errors = mutableListOf<String>()
        AccountStore(context).accounts().forEach { account ->
            try {
                syncAccount(context, account)
                prefs.edit().putLong(account.id + "_success", System.currentTimeMillis())
                    .remove(account.id + "_error").commit()
            } catch (e: Exception) {
                val message = friendlyError(e)
                prefs.edit().putString(account.id + "_error", message).commit()
                errors += account.address + "：" + message
            }
        }
        BridgeEvents.changed()
        errors
    }
    private fun syncAccount(context: Context, account: MailAccount) {
        val prefs = context.getSharedPreferences("mail_sync", Context.MODE_PRIVATE)
        val store = connect(account)
        try {
            val folder = openInbox(store, account)
            try {
                val validity = folder.uidValidity
                val previousValidity = prefs.getLong(account.id + "_validity", -1)
                val previousUid = prefs.getLong(account.id + "_uid", 0)
                val initial = previousValidity != validity
                val initialHigh = if (initial && folder.messageCount > 0) folder.getUID(folder.getMessage(folder.messageCount)) else 0
                val messages = if (initial) {
                    folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false)).takeLast(200).toTypedArray()
                } else {
                    folder.getMessagesByUID(previousUid + 1, UIDFolder.LASTUID)
                        .filterNotNull().filter { folder.getUID(it) > previousUid }.take(200).toTypedArray()
                }
                val profile = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(UIDFolder.FetchProfileItem.UID) }
                folder.fetch(messages, profile)
                var high = previousUid
                messages.forEach { message ->
                    val uid = folder.getUID(message)
                    if (uid > 0) {
                        val sender = message.from?.joinToString(", ") {
                            if (it is InternetAddress) it.toUnicodeString() else it.toString()
                        }.orEmpty()
                        MessageDatabase.get(context).insert("mail:${account.id}:$validity:$uid", account.provider,
                            message.subject?.ifBlank { "（无主题）" } ?: "（无主题）",
                            "来自 ${account.address} · 在原邮箱应用中查看正文", sender,
                            message.receivedDate?.time ?: message.sentDate?.time ?: System.currentTimeMillis(),
                            "", account.id)
                        high = maxOf(high, uid)
                    }
                }
                // Initial import intentionally establishes a baseline after the current mailbox.
                if (initial) high = initialHigh
                prefs.edit().putLong(account.id + "_validity", validity).putLong(account.id + "_uid", high).commit()
            } finally { runCatching { folder.close(false) } }
        } finally { runCatching { store.close() } }
    }
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MailWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("mail_sync", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
class MailWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        if (MailSync.syncAll(applicationContext).isEmpty()) Result.success() else Result.retry()
    } catch (_: Exception) { Result.retry() }
}
