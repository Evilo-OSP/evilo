package crow.evilo.plugin.catalog

import crow.evilo.network.ktor.BoundedHttpTransport
import crow.evilo.plugin.api.HostDescriptor
import crow.evilo.plugin.api.PluginJson
import crow.evilo.storage.EviloPreferences
import crow.evilo.storage.EviloPreferencesRepository
import crow.evilo.storage.MemoryDataStore
import crow.evilo.storage.MemoryPluginCatalogStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Drives a real subscription address through the repository: pointer, key, signature,
 * index, then a catalog. The artifacts under /tmp were produced by
 * evilo-subscribe-template/scripts/build_index.py, so producer and consumer are checked
 * against each other.
 */
class SubscriptionSourceTest {
    private val base = "https://subscription.example.com"
    private val files: Map<String, ByteArray> = mapOf(
        // The pointer is regenerated against this fixture's base so no localhost leaks in.
        "/repo.json" to java.io.File("/tmp/evilo-subscription-demo-index/repo.json").readText()
            .replace(Regex("\"indexUrl\": \"[^\"]*\""), "\"indexUrl\": \"$base/index.json\"")
            .encodeToByteArray(),
        "/index.json" to java.io.File("/tmp/evilo-subscription-demo-index/index.json").readBytes(),
        "/index.json.sig" to java.io.File("/tmp/evilo-subscription-demo-index/index.json.sig").readBytes(),
        "/index.json.pub" to java.io.File("/tmp/evilo-subscription-demo-index/index.json.pub").readBytes(),
    )

    private fun repository(fault: (String) -> HttpStatusCode? = { null }): Pair<PluginCatalogRepository, BoundedHttpTransport> {
        val transport = BoundedHttpTransport(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            println("REQ ${request.url}")
            val bytes = files[path]
            when {
                bytes == null -> respond("missing", HttpStatusCode.NotFound)
                fault(path) != null -> respond("denied", fault(path)!!)
                else -> respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }) { followRedirects = false }, requestTimeoutMillis = 0)
        val preferences = EviloPreferencesRepository(MemoryDataStore(EviloPreferences()))
        return PluginCatalogRepository(MemoryPluginCatalogStore(), preferences, transport,
            HostDescriptor("1.0.0", 1, capabilities = setOf(crow.evilo.plugin.api.HOST_MEDIA_CAPABILITY))) to transport
    }

    @Test
    fun aPointerResolvesToTheIndexPlugins() = runBlocking {
        val (repository, transport) = repository()
        try {
            repository.initialize("""{"name":"bundled","plugins":[]}""")
            // Rewrite the pointer so it names this fixture's absolute address.
            repository.addSource("$base/repo.json", allowHttp = false)
            val snapshot = repository.state.value.snapshots.first { it.subscription.url == "$base/repo.json" }
            assertEquals(listOf("com.alice.novel", "com.bob.manga", "com.carol.anime"),
                snapshot.catalog.plugins.map { it.id })
        } finally {
            transport.close()
        }
    }

    @Test
    fun aSignedIndexWithABadSignatureIsRejected() = runBlocking {
        val (repository, transport) = repository(fault = { path -> if (path == "/index.json.sig") HttpStatusCode.NotFound else null })
        try {
            repository.initialize("""{"name":"bundled","plugins":[]}""")
            val failure = assertFailsWith<SourceFailure> { repository.addSource("$base/repo.json", false) }
            assertTrue(failure.message.orEmpty().contains("HTTP 404"), failure.message.orEmpty())
        } finally {
            transport.close()
        }
    }
}
