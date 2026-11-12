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
        val account = MailAccount("test", "netease", "test@163.com", "sample-secret")
        val dns = MailFailure.from(account, "连接服务器并登录", javax.mail.MessagingException(
            "Connection failed", java.net.UnknownHostException("imap.163.com")))
        assertTrue(dns.summary.contains("DNS"))
        assertTrue(dns.details.contains("imap.163.com:993"))
        val auth = MailFailure.from(account, "连接服务器并登录",
            javax.mail.AuthenticationFailedException("Rejected test@163.com sample-secret"))
        assertTrue(auth.summary.contains("身份验证"))
        assertFalse(auth.details.contains(account.address))
        assertFalse(auth.details.contains(account.secret))
        assertTrue(MailFailure.from(account, "打开收件箱",
            javax.mail.MessagingException("NO Unsafe Login")).summary.contains("Unsafe Login"))
        assertTrue(MailFailure.from(account, "连接服务器并登录",
            javax.net.ssl.SSLHandshakeException("Certificate expired")).summary.contains("TLS"))
        assertTrue(MailFailure.from(account, "连接服务器并登录",
            java.net.SocketTimeoutException("Read timed out")).summary.contains("超时"))
    }
}
