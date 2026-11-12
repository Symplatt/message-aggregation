package cn.local.message.message_aggregation

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Base64
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.net.ssl.SSLException

class MailFailure(val summary: String, val details: String) : Exception(summary) {
    companion object {
        fun from(account: MailAccount, stage: String, error: Exception): MailFailure {
            if (error is MailFailure) return error
            // JavaMail can use nextException instead of cause; inspect both, guarding cycles.
            val chain = mutableListOf<Throwable>()
            val pending = ArrayDeque<Throwable>().apply { add(error) }
            while (pending.isNotEmpty() && chain.size < 8) {
                val next = pending.removeFirst()
                if (chain.any { it === next }) continue
                chain.add(next)
                next.cause?.let { pending.add(it) }
                (next as? MessagingException)?.nextException?.let { pending.add(it) }
            }
            val raw = chain.mapNotNull { it.message }.distinct().joinToString("\n")
            val summary = when {
                raw.contains("unsafe login", ignoreCase = true) -> "服务器拒绝客户端安全验证（Unsafe Login）"
                chain.any { it is UnknownHostException } -> "无法解析邮箱服务器地址（DNS）"
                chain.any { it is SSLException } -> "邮箱服务器 TLS 安全连接失败"
                chain.any { it is SocketTimeoutException } -> "连接或读取邮箱服务器超时"
                chain.any { it is ConnectException } -> "无法连接邮箱服务器端口"
                chain.any { it is AuthenticationFailedException } -> "服务器拒绝身份验证，请查看详情"
                stage == "发送客户端标识" -> "服务器未接受客户端标识"
                stage == "打开收件箱" -> "服务器未能打开收件箱"
                else -> "$stage 失败"
            }
            // No protocol debug logging: diagnostic text is bounded and excludes credentials.
            val sensitive = listOf(account.secret, account.address,
                "\u0000${account.address}\u0000${account.secret}",
                "${account.address}\u0000${account.address}\u0000${account.secret}")
                .filter { it.isNotEmpty() }
            var safe = raw
            (sensitive + sensitive.map { Base64.getEncoder().encodeToString(it.toByteArray()) })
                .sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[已隐藏]") }
            safe = safe.replace(Regex("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}"), "[邮箱]")
                .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), " ").take(2000)
            val host = MessageRules.mailHost(account.provider, account.address)
            return MailFailure(summary, "失败阶段：$stage\n服务器：$host:993\n连接方式：IMAP / TLS\n" +
                "异常类型：${chain.joinToString(" → ") { it.javaClass.simpleName }}\n\n" +
                "服务器返回或系统错误：\n${safe.ifBlank { "未提供进一步信息" }}")
        }
    }
}
