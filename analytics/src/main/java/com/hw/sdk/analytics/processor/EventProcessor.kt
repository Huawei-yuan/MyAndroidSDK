package com.hw.sdk.analytics.processor

import android.util.Base64
import com.hw.sdk.analytics.config.AnalyticsConfig
import com.hw.sdk.analytics.models.Event
import com.hw.sdk.analytics.utils.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DataCryptor(private val secretKey: String) {
    companion object {
        private const val AES_ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_LENGTH = 256
    }

    private val cipher by lazy { Cipher.getInstance(AES_ALGORITHM) }
    private val keySpec by lazy {
        val key = secretKey.toByteArray().copyOf(32) // 确保32字节长度
        SecretKeySpec(key, "AES")
    }

    suspend fun encryptAsync(data: String): String = withContext(Dispatchers.Default) {
        try {
            val iv = ByteArray(12) // GCM推荐12字节IV
            SecureRandom().nextBytes(iv)
            val ivSpec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))

            // 将IV和加密数据合并
            val result = iv + encrypted
            Base64.encodeToString(result, Base64.NO_WRAP)
        } catch (e: Exception) {
            logError("Encryption failed", e)
            data // 降级处理，返回原始数据
        }
    }

    suspend fun decryptAsync(encryptedData: String): String = withContext(Dispatchers.Default) {
        try {
            val data = Base64.decode(encryptedData, Base64.NO_WRAP)
            val iv = data.sliceArray(0..11) // 前12字节是IV
            val encrypted = data.sliceArray(12 until data.size)

            val ivSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decrypted = cipher.doFinal(encrypted)

            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            logError("Decryption failed", e)
            encryptedData // 降级处理
        }
    }
}

class EventProcessor(private val config: AnalyticsConfig) {
    private val cryptor = if (config.enableEncryption && config.encryptionKey != null) {
        DataCryptor(config.encryptionKey)
    } else null

    suspend fun processEvent(event: Event): Event {
        return if (cryptor != null) {
            // 加密敏感属性
            val encryptedProperties = event.properties.mapValues { (key, value) ->
                if (isSensitiveProperty(key)) {
                    JsonPrimitive(cryptor.encryptAsync(value.toString()))
                } else {
                    value
                }
            }

            event.copy(properties = encryptedProperties)
        } else {
            event
        }
    }

    private fun isSensitiveProperty(key: String): Boolean {
        val sensitiveKeys = setOf("user_id", "email", "phone", "address")
        return sensitiveKeys.contains(key.lowercase())
    }
}