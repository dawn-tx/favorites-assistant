package com.favkit.tablet.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import com.favkit.tablet.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 出站信封加密。
 *
 * 生产模式：随机 256 位会话密钥用 AES-256-GCM 加密业务 JSON，会话密钥再用服务端 RSA 公钥
 * （OAEP-SHA256，MGF1-SHA256）包裹后随 `key` 字段发送，只有服务端私钥能解开。
 * 兜底模式：未配置公钥时退化为 Android Keystore 硬件密钥加密，数据仍不以明文出网，
 * 但后台无法解密 —— 只适合本地 mock 联调，正式环境必须配置公钥。
 */
class EnvelopeCrypto(private val app: android.app.Application, private val settings: AppSettings) {

    suspend fun encrypt(plaintext: String): Envelope = withContext(Dispatchers.IO) {
        val pem = resolveServerPublicKeyPem()
        if (pem != null) envelopeWithServerKey(plaintext, pem) else envelopeWithDeviceKey(plaintext)
    }

    private fun envelopeWithServerKey(plaintext: String, pem: String): Envelope {
        val random = SecureRandom()
        val sessionKey = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)

        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = aes.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            parsePublicKey(pem),
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        return Envelope(
            alg = ALG_SERVER,
            key = rsa.doFinal(sessionKey).base64(),
            iv = iv.base64(),
            data = ciphertext.base64(),
        )
    }

    private fun envelopeWithDeviceKey(plaintext: String): Envelope {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceMasterKey(), GCMParameterSpec(TAG_BITS, iv))
        return Envelope(
            alg = ALG_DEVICE,
            key = "",
            iv = iv.base64(),
            data = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).base64(),
        )
    }

    private suspend fun resolveServerPublicKeyPem(): String? {
        settings.current().serverPublicKeyPem.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            app.assets.open(ASSET_PEM).bufferedReader().use { it.readText() }
        }.getOrNull()?.takeIf { it.contains("BEGIN PUBLIC KEY") }
    }

    private fun parsePublicKey(pem: String): RSAPublicKey {
        // BuildConfig 里注入的 PEM 是字面量 "\n"（两字符），必须先还原成真实换行再剥头部
        val der = pem.replace("\\r", "").replace("\\n", "\n")
            .lineSequence()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
            .let { Base64.decode(it, Base64.NO_WRAP) }
        val key = java.security.KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(der))
        return key as RSAPublicKey
    }

    private fun deviceMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    companion object {
        private const val TAG_BITS = 128
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "favkit_sync_master"
        private const val ASSET_PEM = "server_public_key.pem"
        const val ALG_SERVER = "RSA-OAEP-256+AES-256-GCM"
        const val ALG_DEVICE = "AES-256-GCM/DEVICE-KEYSTORE"
    }
}
