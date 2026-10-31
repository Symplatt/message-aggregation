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
        } finally { db.removeAccount("__test") }
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
