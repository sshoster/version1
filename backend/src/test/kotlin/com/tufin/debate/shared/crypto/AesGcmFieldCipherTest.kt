package com.tufin.debate.shared.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AesGcmFieldCipherTest {

    private val cipher = AesGcmFieldCipher("0123456789abcdef0123456789abcdef".toByteArray())

    @Test
    fun `roundtrip preserves content including hebrew`() {
        val plain = "הודעה פרטית עם emoji 🙂 ותווים <מיוחדים>"
        assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun `ciphertext is prefixed, non-deterministic, and unreadable`() {
        val plain = "secret"
        val a = cipher.encrypt(plain)
        val b = cipher.encrypt(plain)
        assertTrue(a.startsWith("enc1:"))
        assertNotEquals(a, b, "random IV must make ciphertexts differ")
        assertTrue(!a.contains(plain))
    }

    @Test
    fun `legacy plaintext values pass through decrypt`() {
        assertEquals("plain old value", cipher.decrypt("plain old value"))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val stored = cipher.encrypt("secret")
        val tampered = stored.dropLast(4) + "AAAA"
        assertThrows<Exception> { cipher.decrypt(tampered) }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertThrows<IllegalArgumentException> { AesGcmFieldCipher("short".toByteArray()) }
    }
}
