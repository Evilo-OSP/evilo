package crow.evilo.network.ktor

import crow.evilo.plugin.api.SourceHttpClient
import crow.evilo.plugin.api.SourceMetadata
import crow.evilo.plugin.api.SourceRequest
import crow.evilo.plugin.api.SourceResponse
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CancellationException

object PluginUrlPolicy {
    fun check(value: String, allowHttp: Boolean = false): Url {
        requireNetwork(value.length <= 4096 && (value.startsWith("https://") || value.startsWith("http://")), NetworkPolicyViolation.INVALID_URL) {
            "An absolute HTTP(S) URL is required."
        }
        requireNetwork(value.none { it <= ' ' || it == '\u007f' || it == '\\' || it == '#' }, NetworkPolicyViolation.INVALID_URL) { "Invalid characters in URL." }
        val url = try { Url(value) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw NetworkPolicyException(NetworkPolicyViolation.INVALID_URL, "Cannot parse the HTTP(S) URL.", failure) }
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?')
        requireNetwork(url.host.isNotBlank() && url.host.length <= 253, NetworkPolicyViolation.INVALID_URL) { "Invalid or oversized hostname." }
        requireNetwork(url.user == null && '@' !in authority, NetworkPolicyViolation.URL_CREDENTIALS) { "URL credentials are not allowed." }
        requireNetwork(url.protocol == URLProtocol.HTTPS || (allowHttp && url.protocol == URLProtocol.HTTP), NetworkPolicyViolation.CLEARTEXT_NOT_APPROVED) {
            "HTTP requires explicit cleartext consent; use HTTPS where possible."
        }
        url.rawSegments.forEach { segment ->
            var decoded = segment
            repeat(3) {
                decoded = try { decoded.decodeURLPart() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { throw NetworkPolicyException(NetworkPolicyViolation.INVALID_URL, "Cannot decode the URL path.", failure) }
                requireNetwork(decoded.none { it <= ' ' || it == '\u007f' || it == '\\' } &&
                    decoded.split('/').none { it == "." || it == ".." }, NetworkPolicyViolation.URL_PATH) { "URL path traversal is not allowed." }
            }
        }
        return url
    }

    fun origin(url: Url): String = "${url.protocol.name}://${url.host.lowercase()}:${url.port}"
}

/**
 * Sanitizes the requests a plugin sends through the host. The host does not track which
 * servers a plugin may reach: installing a plugin is the authorization. What remains here
 * keeps a request syntactically valid and strips headers that describe the host itself.
 */
class PluginNetworkPolicy(
    val pluginId: String,
    private val allowHttp: Boolean,
) {
    fun approve(request: SourceRequest): SourceRequest {
        val url = PluginUrlPolicy.check(request.url, allowHttp)
        val body = request.body
        requireNetwork(body == null || body.encodeToByteArray().size <= MAX_REQUEST_BODY_BYTES,
            NetworkPolicyViolation.REQUEST_BODY) { "Oversized HTTP request body." }
        requireNetwork(request.headers.size <= MAX_REQUEST_HEADERS, NetworkPolicyViolation.HEADERS_NOT_APPROVED) {
            "Too many HTTP headers."
        }
        val headers = mutableMapOf<String, String>()
        request.headers.forEach { (name, value) ->
            val normalized = name.lowercase()
            val diagnosticHeader = normalized.takeIf { it.length <= 64 && HEADER_NAME.matches(it) }
            requireNetwork(normalized !in headers && normalized !in FORBIDDEN_HEADERS,
                NetworkPolicyViolation.HEADERS_NOT_APPROVED, rejectedHeader = diagnosticHeader) {
                "HTTP header '$name' is not allowed."
            }
            requireNetwork(value.length <= 4096 && value.none { it < ' ' || it == '\u007f' },
                NetworkPolicyViolation.HEADERS_NOT_APPROVED, rejectedHeader = diagnosticHeader) { "Invalid HTTP header value." }
            headers[normalized] = value
        }
        return request.copy(url = url.toString(), headers = headers)
    }
}

class PluginHttpClient(
    private val transport: BoundedHttpTransport,
    private val policy: PluginNetworkPolicy,
) : SourceHttpClient {
    override suspend fun execute(source: SourceMetadata, request: SourceRequest): SourceResponse {
        require(source.id == policy.pluginId) { "HTTP requests belong to another plugin." }
        val approved = policy.approve(request)
        val response = transport.fetch(approved, MAX_API_RESPONSE_BYTES)
        return SourceResponse(approved.url, response.statusCode, response.headers,
            response.bytes.decodeToString(throwOnInvalidSequence = true))
    }

    suspend fun imageBytes(url: String, headers: Map<String, String>): ByteArray {
        // Default the user agent so a plugin that omits it still gets media the CDN serves.
        val withAgent = if (headers.keys.none { it.equals("user-agent", ignoreCase = true) }) {
            mapOf("User-Agent" to "evilo") + headers
        } else {
            headers
        }
        val approved = policy.approve(SourceRequest(url = url, headers = withAgent))
        val response = transport.fetch(approved, MAX_MEDIA_RESPONSE_BYTES)
        if (response.statusCode !in 200..299) throw HttpStatusException(response.statusCode)
        return response.bytes
    }

    fun approvedStreamUrl(url: String, headers: Map<String, String>): String =
        policy.approve(SourceRequest(url = url, headers = headers)).url

    fun approvedImageRequest(url: String, headers: Map<String, String>): SourceRequest =
        policy.approve(SourceRequest(url = url, headers = headers))
}

private const val MAX_API_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_MEDIA_RESPONSE_BYTES = 16 * 1024 * 1024
private const val MAX_REQUEST_BODY_BYTES = 64 * 1024
private const val MAX_REQUEST_HEADERS = 32
private val HEADER_NAME = Regex("[a-z0-9!#$%&'*+.^_`|~-]+")
private val FORBIDDEN_HEADERS = setOf(
    "cookie", "cookie2", "host", "connection", "content-length", "transfer-encoding", "upgrade", "proxy-authorization",
    "proxy-connection", "forwarded", "x-forwarded-for", "x-forwarded-host", "origin", "access-control-request-headers",
    "access-control-request-method", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
)
