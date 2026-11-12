package cn.local.message.message_aggregation

import android.content.Context
import androidx.work.*
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
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
            var stage = "连接服务器并登录"
            try {
                store.connect(host, 993, account.address, account.secret)
                if (account.provider == "netease") {
                    stage = "发送客户端标识"
                    // IMAPFolder.id requires an already-open folder, but NetEase checks ID
                    // before opening INBOX. Send it on the authenticated store connection.
                    (store as IMAPStore).id(mapOf("name" to "MessageAggregation", "vendor" to "Symplatt"))
                }
            } catch (e: Exception) {
                runCatching { store.close() }
                throw MailFailure.from(account, stage, e)
            }
        }
    }
    private fun openInbox(store: Store, account: MailAccount): IMAPFolder {
        try {
            val folder = store.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            return folder
        } catch (e: Exception) { throw MailFailure.from(account, "打开收件箱", e) }
    }
    fun validate(account: MailAccount) {
        val store = connect(account)
        try { openInbox(store, account).close(false) } finally { runCatching { store.close() } }
    }
    fun syncAll(context: Context): List<String> = synchronized(lock) {
        val prefs = context.getSharedPreferences("mail_sync", Context.MODE_PRIVATE)
        val errors = mutableListOf<String>()
        AccountStore(context).accounts().forEach { account ->
            try {
                syncAccount(context, account)
                prefs.edit().putLong(account.id + "_success", System.currentTimeMillis())
                    .remove(account.id + "_error").remove(account.id + "_error_details").commit()
            } catch (e: Exception) {
                val failure = MailFailure.from(account, "同步邮件", e)
                prefs.edit().putString(account.id + "_error", failure.summary)
                    .putString(account.id + "_error_details", failure.details).commit()
                errors += account.address + "：" + failure.summary
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
