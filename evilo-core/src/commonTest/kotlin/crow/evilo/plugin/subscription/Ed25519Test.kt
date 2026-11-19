package crow.evilo.plugin.subscription

import crow.evilo.plugin.subscription.crypto.Ed25519
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8

/** RFC 8032 section 7.1 vector 1, so the ported verifier is checked against the standard. */
class Ed25519Test {
    private val publicKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".decodeHex()
    private val signature = ("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
        "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b").decodeHex()

    @Test
    fun anEmptyMessageVerifiesAgainstItsPublishedSignature() {
        assertTrue(Ed25519.verify("".encodeUtf8(), signature, publicKey))
    }

    @Test
    fun aChangedMessageDoesNotVerify() {
        assertFalse(Ed25519.verify("x".encodeUtf8(), signature, publicKey))
    }

    @Test
    fun aChangedSignatureDoesNotVerify() {
        val tampered = signature.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Ed25519.verify("".encodeUtf8(), okio.ByteString.of(*tampered), publicKey))
    }

    @Test
    fun aDifferentPublicKeyDoesNotVerify() {
        val other = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c".decodeHex()
        assertFalse(Ed25519.verify("".encodeUtf8(), signature, other))
    }
}
