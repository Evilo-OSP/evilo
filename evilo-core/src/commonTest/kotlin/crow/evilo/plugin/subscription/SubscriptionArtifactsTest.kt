package crow.evilo.plugin.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.ByteString.Companion.decodeHex

/**
 * Reads the artifacts `evilo-subscribe-template/scripts/build_index.py` produced for the
 * three-repository demo, so the producer and the consumer are checked against each other
 * rather than each against its own assumption.
 */
class SubscriptionArtifactsTest {
    private val directory = java.io.File("/tmp/evilo-subscription-demo-index")
    private val pointerText = """
        {"name":"Awesome evilo plugins",
         "indexUrl":"https://example.com/index.json",
         "signingKeyFingerprint":"21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9",
         "keyId":"subscription"}
    """.trimIndent()
    private val publicKeyHex = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"

    @Test
    fun theGeneratedPointerIsRecognised() {
        val content = SubscriptionParser.classify(pointerText, false)
        val pointer = assertIs<SubscriptionContent.Pointer>(content)
        assertEquals("21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9",
            pointer.document.signingKeyFingerprint)
    }

    @Test
    fun theGeneratedIndexVerifiesAndListsEveryPlugin() {
        val pointer = assertIs<SubscriptionContent.Pointer>(SubscriptionParser.classify(pointerText, false)).document
        val indexBytes = java.io.File(directory, "index.json").readBytes()
        val signature = java.io.File(directory, "index.json.sig").readText().trim().decodeHex().toByteArray()
        val index = SubscriptionParser.verifyIndex(pointer, indexBytes, signature,
            SubscriptionParser.publicKey(publicKeyHex), allowHttp = true)
        assertEquals(3, index.plugins.size)
        assertEquals(listOf("com.alice.novel", "com.bob.manga", "com.carol.anime"), index.plugins.map { it.id })
        assertEquals("Alice", index.plugins.first().authors.single())
        assertEquals("1.2.0", index.plugins.first().version)
    }

    @Test
    fun aTamperedIndexIsRejected() {
        val pointer = assertIs<SubscriptionContent.Pointer>(SubscriptionParser.classify(pointerText, false)).document
        val original = java.io.File(directory, "index.json").readText()
        val signature = java.io.File(directory, "index.json.sig").readText().trim().decodeHex().toByteArray()
        val tampered = original.replace("Alice Novel", "Mallory Novel").encodeToByteArray()
        val failure = assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.verifyIndex(pointer, tampered, signature, SubscriptionParser.publicKey(publicKeyHex), false)
        }
        assertTrue(failure.message.orEmpty().contains("signature"))
    }

    @Test
    fun aSubstitutedKeyIsRejected() {
        val pointer = assertIs<SubscriptionContent.Pointer>(SubscriptionParser.classify(pointerText, false)).document
        val indexBytes = java.io.File(directory, "index.json").readBytes()
        val signature = java.io.File(directory, "index.json.sig").readText().trim().decodeHex().toByteArray()
        // A different key, even one whose signature would verify, fails the fingerprint check.
        val other = SubscriptionParser.publicKey("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
        val failure = assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.verifyIndex(pointer, indexBytes, signature, other, false)
        }
        assertTrue(failure.message.orEmpty().contains("fingerprint"))
    }
}
