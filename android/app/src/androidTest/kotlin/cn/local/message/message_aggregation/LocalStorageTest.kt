package cn.local.message.message_aggregation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun duplicateDeliveryPreservesReadAndArchiveAcrossDatabaseReopen() {
        val db = MessageDatabase.get(context)
        val id = "__test_replay"
        try {
            db.insert(id, "wechat", "测试", "同一通知", "sender", 100, "key", "__test")
            db.update(id, true, true)
            db.close()
            db.insert(id, "wechat", "测试", "同一通知", "sender", 100, "key", "__test")
            val records = db.list().filter { it["id"] == id }
            assertEquals(1, records.size)
            assertEquals(true, records.single()["isRead"])
            assertEquals(true, records.single()["archived"])
            db.setPinned(id, true)
            db.close()
            db.insert("__test_pin_next", "wechat", "测试", "后续通知", "sender", 101, "key", "__test")
            assertTrue(db.list().filter { it["accountId"] == "__test" }.all { it["isPinned"] == true })
            db.setPinned(id, false)
            db.deleteMessages(listOf(id, "__test_pin_next"))
            db.insert(id, "wechat", "测试", "同一通知", "sender", 100, "key", "__test")
            assertFalse(db.list().any { it["id"] == id || it["id"] == "__test_pin_next" })

            val sender = "__test_sms_sender"
            db.insertSms("__test_sms_event", sender, "广播先到", 200)
            val openedId = db.list().single { it["id"] == "__test_sms_event" }["id"] as String
            db.update(openedId, true, null)
            db.insertSms("__test_sms_provider", sender, "广播先到", 201, "__test_sms_event")
            db.update(openedId, false, null)
            db.close()
            db.insertSms("__test_sms_provider", sender, "广播先到", 201, "__test_sms_event")
            val broadcastFirst = db.list().filter { it["sender"] == sender && it["body"] == "广播先到" }
            assertEquals(1, broadcastFirst.size)
            assertEquals(openedId, broadcastFirst.single()["id"])
            assertEquals(false, broadcastFirst.single()["isRead"])
            // An old provider identity must still resolve to the same durable message.
            db.update("__test_sms_provider", true, null)
            assertEquals(true, db.list().single { it["id"] == openedId }["isRead"])

            db.insertSms("__test_sms_provider_first", sender, "收件箱先到", 300, "__test_sms_event_late")
            db.insertSms("__test_sms_provider_first", sender, "收件箱先到", 301, "__test_sms_event_late")
            db.update("__test_sms_event_late", true, null)
            val providerFirst = db.list().filter { it["body"] == "收件箱先到" }
            assertEquals(1, providerFirst.size)
            assertEquals(true, providerFirst.single()["isRead"])

            db.insertSms("__test_sms_old_provider", sender, "已有两份", 400)
            db.insertSms("__test_sms_old_event", sender, "已有两份", 400)
            db.update("__test_sms_old_provider", true, null)
            // Make the ordering deterministic without timing assumptions.
            db.writableDatabase.execSQL("UPDATE messages SET localStateAt = 1 WHERE id = '__test_sms_old_provider'")
            db.update("__test_sms_old_event", false, null)
            db.insertSms("__test_sms_old_provider", sender, "已有两份", 400, "__test_sms_old_event")
            val merged = db.list().filter { it["body"] == "已有两份" }
            assertEquals(1, merged.size)
            assertEquals(false, merged.single()["isRead"])
            db.update("__test_sms_old_event", true, null)
            assertEquals(true, db.list().single { it["id"] == "__test_sms_old_provider" }["isRead"])
            db.deleteMessages(listOf("__test_sms_event_late"))
            db.insertSms("__test_sms_provider_first", sender, "收件箱先到", 301, "__test_sms_event_late")
            assertFalse(db.list().any { it["id"] == "__test_sms_provider_first" })
        } finally {
            db.removeAccount("__test")
            db.writableDatabase.delete("messages", "sender = ?", arrayOf("__test_sms_sender"))
            db.writableDatabase.delete("messages", "id GLOB ?", arrayOf("__test_sms_*"))
        }
    }
    @Test fun credentialsAreEncryptedAtRestAndCanBeRemoved() {
        val store = AccountStore(context)
        val account = MailAccount("__test_credential", "qqmail", "fixture@qq.com", "fixture-secret-123")
        try {
            store.save(account)
            val raw = context.getSharedPreferences("mail_accounts", 0).getString("accounts", "")!!
            assertFalse(raw.contains(account.secret))
            assertEquals(account.secret, AccountStore(context).accounts().first { it.id == account.id }.secret)
            store.remove(account.id)
            assertFalse(AccountStore(context).accounts().any { it.id == account.id })
        } finally { store.remove(account.id) }
    }
    @Test fun accountRemovalDoesNotDeleteOtherSources() {
        val db = MessageDatabase.get(context)
        try {
            db.insert("__test_mail1", "qqmail", "A", "", "", 1, "", "__test_account1")
            db.insert("__test_mail2", "netease", "B", "", "", 2, "", "__test_account2")
            db.removeAccount("__test_account1")
            assertFalse(db.list().any { it["id"] == "__test_mail1" })
            assertTrue(db.list().any { it["id"] == "__test_mail2" })
        } finally { db.removeAccount("__test_account1"); db.removeAccount("__test_account2") }
    }
}
