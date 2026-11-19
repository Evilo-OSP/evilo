package crow.evilo.plugin.subscription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubscriptionParserTest {
    @Test
    fun aPluginCatalogIsRecognisedRatherThanTreatedAsACollection() {
        val content = SubscriptionParser.classify("""{"name":"P","plugins":[]}""", false)
        assertIs<SubscriptionContent.PluginCatalog>(content)
    }

    @Test
    fun aCollectionListsRepositoryUrlsOnly() {
        val content = SubscriptionParser.classify("""
            {"name":"Awesome","sources":[
              "https://a.example.com/catalog.json",
              "https://b.example.com/catalog.json"]}
        """.trimIndent(), false)
        val collection = assertIs<SubscriptionContent.Collection>(content)
        assertEquals("Awesome", collection.document.name)
        assertEquals(2, collection.document.sources.size)
    }

    @Test
    fun aPointerCarriesTheIndexAddressAndFingerprint() {
        val content = SubscriptionParser.classify("""
            {"name":"A","indexUrl":"https://a.example.com/index.json",
             "signingKeyFingerprint":"${"a".repeat(64)}"}
        """.trimIndent(), false)
        val pointer = assertIs<SubscriptionContent.Pointer>(content)
        assertEquals("https://a.example.com/index.json", pointer.document.indexUrl)
    }

    @Test
    fun aDocumentWithTwoShapesIsRejected() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.classify("""{"plugins":[],"sources":["https://a.example.com/x"]}""", false)
        }
        assertTrue(failure.message.orEmpty().contains("exactly one"))
    }

    @Test
    fun aDocumentWithNoKnownShapeIsRejected() {
        assertFailsWith<IllegalArgumentException> { SubscriptionParser.classify("""{"name":"x"}""", false) }
    }

    @Test
    fun cleartextAndDuplicateEntriesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.classify("""{"name":"x","sources":["http://a.example.com/c.json"]}""", false)
        }
        assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.classify("""{"name":"x","sources":[
                "https://a.example.com/c.json","https://a.example.com/c.json"]}""", false)
        }
    }

    @Test
    fun anEmptyCollectionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.classify("""{"name":"x","sources":[]}""", false)
        }
    }

    @Test
    fun aMalformedFingerprintIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionParser.classify("""{"name":"A","indexUrl":"https://a.example.com/i.json",
                "signingKeyFingerprint":"abc"}""", false)
        }
    }
}
