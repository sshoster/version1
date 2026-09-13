package com.tufin.debate.shared.crypto

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Application-level encryption for highly sensitive private fields (private message bodies,
 * boundaries) — docs/security.md §5. Upgradeable to MongoDB CSFLE behind this same interface.
 */
interface FieldCipher {
    fun encrypt(plain: String): String
    fun decrypt(stored: String): String
}

/** AES-256-GCM with a random 12-byte IV per value; output format `enc1:<base64(iv || ciphertext)>`. */
class AesGcmFieldCipher(key: ByteArray) : FieldCipher {

    companion object {
        const val PREFIX = "enc1:"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private val random = SecureRandom()
    }

    private val keySpec = SecretKeySpec(key, "AES")

    init {
        require(key.size == 32) { "APP_ENCRYPTION_KEY must decode to exactly 32 bytes (AES-256)" }
    }

    override fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    override fun decrypt(stored: String): String {
        // Values written before encryption was enabled stay readable.
        if (!stored.startsWith(PREFIX)) return stored
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_LENGTH)))
        return String(cipher.doFinal(bytes.copyOfRange(IV_LENGTH, bytes.size)), Charsets.UTF_8)
    }
}

/** Pass-through used only in local/test when no key is configured. */
class NoopFieldCipher : FieldCipher {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(stored: String): String = stored
}

@Configuration
class FieldCipherConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun fieldCipher(environment: Environment): FieldCipher {
        val configured = environment.getProperty("app.encryption.key", "").trim()
        if (configured.isNotEmpty()) {
            return AesGcmFieldCipher(Base64.getDecoder().decode(configured))
        }
        check(environment.acceptsProfiles(Profiles.of("local", "test"))) {
            "APP_ENCRYPTION_KEY is required outside the local/test profiles " +
                "(base64 of 32 random bytes, see .env.example)"
        }
        log.warn("No APP_ENCRYPTION_KEY configured — private fields are stored UNENCRYPTED (local dev only)")
        return NoopFieldCipher()
    }
}
