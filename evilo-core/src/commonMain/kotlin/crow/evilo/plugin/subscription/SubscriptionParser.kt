package crow.evilo.plugin.subscription

import crow.evilo.network.ktor.PluginUrlPolicy
import crow.evilo.plugin.api.PluginJson
import crow.evilo.plugin.subscription.crypto.Ed25519
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString

/** A hand-maintained list of plugin repository URLs. */
@Serializable
data class SubscriptionCollection(val name: String, val sources: List<String>)

/** The pointer a collection publishes: where the index lives and who signed it. */
@Serializable
data class SubscriptionPointer(
    val name: String,
    val indexUrl: String,
    val signingKeyFingerprint: String,
    val keyId: String,
)

/** The signed index: every plugin of every listed repository, merged once. */
@Serializable
data class SubscriptionIndex(
    val schemaVersion: Int,
    val name: String,
    val updatedAt: String,
    val plugins: List<IndexedPlugin>,
)

@Serializable
data class IndexedPlugin(
    val catalogUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val iconUrl: String = "",
    val authors: List<String> = emptyList(),
    val description: String = "",
)

/** What an address actually served. */
sealed interface SubscriptionContent {
    /** A hand-maintained URL list. Expands to one source per entry. */
    data class Collection(val document: SubscriptionCollection) : SubscriptionContent

    /** A pointer to a signed index. The bytes are fetched by the caller before verification. */
    data class Pointer(val document: SubscriptionPointer) : SubscriptionContent

    /** A regular plugin catalog: this address is a plugin source, not a collection. */
    data object PluginCatalog : SubscriptionContent
}

object SubscriptionParser {
    const val MAX_SOURCES = 4096
    const val MAX_URL_LENGTH = 4096

    /**
     * Distinguishes the three document shapes by field, so existing plugin catalogs keep
     * working unchanged: `plugins` is a plugin source, `sources` is a collection,
     * `indexUrl` is a pointer.
     */
    fun classify(text: String, allowHttp: Boolean): SubscriptionContent {
        val json = PluginJson.parseToJsonElement(text).jsonObject
        val shapes = listOf("plugins", "sources", "indexUrl").count { json.containsKey(it) }
        require(shapes == 1) {
            "A source document must declare exactly one of plugins, sources, or indexUrl."
        }
        return when {
            json.containsKey("plugins") -> SubscriptionContent.PluginCatalog
            json.containsKey("sources") -> SubscriptionContent.Collection(collection(json, allowHttp))
            else -> SubscriptionContent.Pointer(pointer(json, allowHttp))
        }
    }

    fun collection(json: JsonObject, allowHttp: Boolean): SubscriptionCollection {
        val name = json["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("A collection must declare its name.")
        require(name.length <= 120) { "A collection name exceeds 120 characters." }
        val entries = PluginJson.decodeFromJsonElement(ListSerializer(String.serializer()), json.getValue("sources"))
        require(entries.isNotEmpty()) { "A collection must list at least one source." }
        require(entries.size <= MAX_SOURCES) { "A collection may list at most $MAX_SOURCES sources." }
        entries.forEachIndexed { index, url ->
            require(url.length <= MAX_URL_LENGTH) { "Collection entry ${index + 1} exceeds $MAX_URL_LENGTH characters." }
            PluginUrlPolicy.check(url, allowHttp)
        }
        require(entries.distinctBy { it.trimEnd('/') }.size == entries.size) {
            "A collection lists the same source twice."
        }
        return SubscriptionCollection(name, entries.map { it.trim() })
    }

    fun pointer(json: JsonObject, allowHttp: Boolean): SubscriptionPointer {
        val name = json["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("A pointer must declare its name.")
        val indexUrl = json["indexUrl"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("A pointer must declare its indexUrl.")
        val fingerprint = json["signingKeyFingerprint"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("A pointer must declare its signingKeyFingerprint.")
        val keyId = json["keyId"]?.jsonPrimitive?.content ?: "subscription"
        PluginUrlPolicy.check(indexUrl, allowHttp)
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "A signing key fingerprint must be 64 lowercase hexadecimal characters."
        }
        return SubscriptionPointer(name, indexUrl, fingerprint, keyId)
    }

    /**
     * Verifies the index signature, then parses it.
     *
     * The signature is Ed25519 over the exact index bytes. The fingerprint in the pointer
     * must equal the SHA-256 of the raw public key, which is what the user saw when they
     * subscribed; a substituted key therefore fails here rather than silently trusting.
     */
    fun verifyIndex(pointer: SubscriptionPointer, indexBytes: ByteArray, signatureBytes: ByteArray,
        publicKeyBytes: ByteArray, allowHttp: Boolean): SubscriptionIndex {
        require(publicKeyBytes.size == 32) { "A subscription public key must be 32 bytes." }
        val actual = publicKeyBytes.toByteString().sha256().hex()
        require(actual == pointer.signingKeyFingerprint) {
            "The index signing key does not match the fingerprint in the pointer."
        }
        val signature = signatureBytes.toByteString()
        require(signature.size == 64) { "A subscription signature must be 64 bytes." }
        require(Ed25519.verify(indexBytes.toByteString(), signature, publicKeyBytes.toByteString())) {
            "The index signature does not verify."
        }
        val json = PluginJson.parseToJsonElement(indexBytes.decodeToString()).jsonObject
        val schema = json["schemaVersion"]?.jsonPrimitive?.int
            ?: throw IllegalArgumentException("The index must declare schemaVersion.")
        require(schema == 1) { "Unsupported subscription index version $schema." }
        val plugins = PluginJson.decodeFromJsonElement(ListSerializer(IndexedPlugin.serializer()), json.getValue("plugins"))
        plugins.forEachIndexed { index, plugin ->
            require(plugin.id.isNotBlank()) { "Index plugin ${index + 1} has no id." }
            require(plugin.name.length <= 120) { "Index plugin ${index + 1} has an oversized name." }
            PluginUrlPolicy.check(plugin.catalogUrl, allowHttp)
            if (plugin.iconUrl.isNotEmpty()) PluginUrlPolicy.check(plugin.iconUrl, allowHttp)
        }
        require(plugins.distinctBy { it.id }.size == plugins.size) { "The index lists a plugin id twice." }
        return SubscriptionIndex(schema, json["name"]?.jsonPrimitive?.content ?: pointer.name,
            json["updatedAt"]?.jsonPrimitive?.content ?: "", plugins)
    }

    fun publicKey(hex: String): ByteArray {
        require(hex.matches(Regex("[0-9a-f]{64}"))) { "A subscription public key must be 64 lowercase hexadecimal characters." }
        return hex.decodeHex().toByteArray()
    }
}
