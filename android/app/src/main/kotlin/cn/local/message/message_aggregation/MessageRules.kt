package cn.local.message.message_aggregation

import java.security.MessageDigest

object MessageRules {
    val notificationPackages = mapOf("com.tencent.mm" to "wechat", "com.tencent.mobileqq" to "qq")
    fun fingerprint(vararg values: String): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun mailHost(provider: String, address: String): String {
        val parts = address.trim().lowercase().split('@')
        require(parts.size == 2 && parts[0].isNotBlank() && !address.any { it.isWhitespace() }) { "请输入完整邮箱地址" }
        return when (provider) {
            "qqmail" -> when (parts[1]) {
                "qq.com", "foxmail.com" -> "imap.qq.com"
                else -> throw IllegalArgumentException("QQ 邮箱支持 qq.com / foxmail.com")
            }
            "netease" -> when (parts[1]) {
                "163.com" -> "imap.163.com"
                "126.com" -> "imap.126.com"
                "yeah.net" -> "imap.yeah.net"
                else -> throw IllegalArgumentException("网易邮箱支持 163.com / 126.com / yeah.net")
            }
            else -> throw IllegalArgumentException("该邮箱提供商尚未接入")
        }
    }
}
