package dev.pocketlab.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * All configuration (including API tokens, passwords and private keys) lives in one JSON
 * document encrypted with AES-256-GCM under a non-exportable Android Keystore key.
 * Nothing is written in plaintext, and backups are disabled in the manifest.
 */
class SecureStore(private val file: File, private val keyProvider: () -> SecretKey) {
    constructor(context: Context) : this(File(context.filesDir, "config.enc"), ::keystoreKey)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        save(next)
        _config.value = next
    }

    private fun key(): SecretKey = keyProvider()

    companion object {
        private const val ALIAS = "pocketlab_config_v1"

        fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return gen.generateKey()
        }
    }

    private fun load(): AppConfig {
        if (!file.exists()) return AppConfig()
        return try {
            val bytes = file.readBytes()
            val ivLen = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen)
            json.decodeFromString(AppConfig.serializer(), plain.decodeToString())
        } catch (e: Exception) {
            // Corrupt or key lost (e.g. app data restored to a new device): start clean, keep the old file aside.
            file.renameTo(File(file.parentFile, "config.enc.unreadable"))
            AppConfig()
        }
    }

    private fun save(cfg: AppConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val enc = cipher.doFinal(json.encodeToString(AppConfig.serializer(), cfg).encodeToByteArray())
        val tmp = File(file.parentFile, "config.enc.tmp")
        tmp.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(enc)
            out.fd.sync()
        }
        tmp.renameTo(file)
    }

}
