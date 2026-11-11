package cn.local.message.message_aggregation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class MailAccount(val id: String, val provider: String, val address: String, val secret: String)

class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("mail_accounts", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("mail_credentials", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("mail_credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun metadata(): List<MailAccount> {
        val data = JSONArray(prefs.getString("accounts", "[]"))
        return (0 until data.length()).map {
            val a = data.getJSONObject(it)
            MailAccount(a.getString("id"), a.getString("provider"), a.getString("address"), "")
        }
    }
    fun accounts(): List<MailAccount> {
        val data = JSONArray(prefs.getString("accounts", "[]"))
        return (0 until data.length()).map {
            val a = data.getJSONObject(it)
            MailAccount(a.getString("id"), a.getString("provider"), a.getString("address"), decrypt(a.getString("secret")))
        }
    }
    private fun write(accounts: List<MailAccount>) {
        val json = JSONArray()
        accounts.forEach { a -> json.put(JSONObject().apply {
            put("id", a.id); put("provider", a.provider); put("address", a.address); put("secret", encrypt(a.secret))
        }) }
        check(prefs.edit().putString("accounts", json.toString()).commit()) { "无法保存邮箱配置" }
    }
    fun save(account: MailAccount) = write(accounts().filterNot { it.id == account.id } + account)
    fun remove(id: String) = write(accounts().filterNot { it.id == id })
}
