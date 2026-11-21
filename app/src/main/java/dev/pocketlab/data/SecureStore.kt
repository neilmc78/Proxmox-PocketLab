package dev.pocketlab.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
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

    /** Set when saved settings couldn't be read; shown on the Overview screen. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** False while the file on disk could not be read for a possibly temporary reason: never overwrite it then. */
    @Volatile private var writable = true

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    /**
     * Applies and persists a change. If the file couldn't be read (transient Keystore error), changes
     * are refused rather than overwriting settings that may still be recoverable after a restart.
     */
    @Synchronized
    fun update(transform: (AppConfig) -> AppConfig) {
        if (!writable) return
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
        var lastTransient: Exception? = null
        repeat(3) { attempt ->
            try {
                return decrypt(file.readBytes())
            } catch (e: Exception) {
                if (isPermanent(e)) {
                    // Tampered/corrupt file, or the Keystore key is gone (e.g. data copied to a new device).
                    // Keep the unreadable file under a unique name, then start clean and say so.
                    val backup = File(file.parentFile, "config.enc.unreadable-${System.currentTimeMillis()}")
                    runCatching { Files.move(file.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE) }
                    _problem.value = "Saved settings couldn't be decrypted (${e.javaClass.simpleName}) and were set aside. Add your clusters and hosts again."
                    return AppConfig()
                }
                lastTransient = e
                if (attempt < 2) Thread.sleep(300L * (attempt + 1))
            }
        }
        writable = false
        _problem.value = "Couldn't read saved settings right now (${lastTransient?.javaClass?.simpleName}). They're untouched; restart the app. Changes are disabled until then."
        return AppConfig()
    }

    private fun decrypt(bytes: ByteArray): AppConfig {
        if (bytes.size < 2) throw CorruptConfig("file too short")
        val ivLen = bytes[0].toInt()
        if (ivLen !in 12..16 || bytes.size < 1 + ivLen + 16) throw CorruptConfig("bad header")
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(bytes, 1 + ivLen, bytes.size - 1 - ivLen)
        return try {
            json.decodeFromString(AppConfig.serializer(), plain.decodeToString())
        } catch (e: SerializationException) { throw CorruptConfig(e.message ?: "bad JSON") }
          catch (e: IllegalArgumentException) { throw CorruptConfig(e.message ?: "bad JSON") }
    }

    /** Only these mean the data itself is unrecoverable; anything else (Keystore busy, I/O) may be temporary. */
    private fun isPermanent(e: Exception) = e is CorruptConfig || e is AEADBadTagException ||
        e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException

    private class CorruptConfig(msg: String) : Exception(msg)

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
        // Atomic replace: either the old or the new file, never a torn one. Throws if it fails, so the
        // in-memory state is never published as saved when it isn't.
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

}
