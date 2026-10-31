package cn.local.message.message_aggregation

import org.junit.Assert.*
import org.junit.Test

class MessageRulesTest {
    @Test fun notificationAllowlistExcludesOtherAppsAndMail() {
        assertEquals("wechat", MessageRules.notificationPackages["com.tencent.mm"])
        assertEquals("qq", MessageRules.notificationPackages["com.tencent.mobileqq"])
        assertNull(MessageRules.notificationPackages["com.google.android.gm"])
        assertNull(MessageRules.notificationPackages["com.example.other"])
    }
    @Test fun identitiesSeparateSendersTimesAndDelimiterBoundaries() {
        val id = MessageRules.fingerprint("wechat", "123", "alice", "hello")
        assertEquals(id, MessageRules.fingerprint("wechat", "123", "alice", "hello"))
        assertNotEquals(id, MessageRules.fingerprint("wechat", "124", "alice", "hello"))
        assertNotEquals(id, MessageRules.fingerprint("wechat", "123", "bob", "hello"))
        assertNotEquals(MessageRules.fingerprint("ab", "c"), MessageRules.fingerprint("a", "bc"))
    }
    @Test fun mailHostMustMatchProvider() {
        assertEquals("imap.qq.com", MessageRules.mailHost("qqmail", "a@foxmail.com"))
        assertEquals("imap.126.com", MessageRules.mailHost("netease", "a@126.com"))
        assertThrows(IllegalArgumentException::class.java) { MessageRules.mailHost("qqmail", "a@attacker.example") }
        assertThrows(IllegalArgumentException::class.java) { MessageRules.mailHost("gmail", "a@gmail.com") }
        assertThrows(IllegalArgumentException::class.java) { MessageRules.mailHost("netease", "a@@163.com") }
    }
}
