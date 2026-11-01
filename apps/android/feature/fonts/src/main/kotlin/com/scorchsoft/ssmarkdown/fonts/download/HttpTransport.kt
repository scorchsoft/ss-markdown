package com.scorchsoft.ssmarkdown.fonts.download

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one network call the app makes: an HTTP GET. Injected so tests run
 * the whole download path against canned responses, never the network.
 */
fun interface HttpTransport {
    /**
     * Opens [url]. Throws [IOException] when there is no connection;
     * any HTTP status, including errors, is a normal [HttpResponse].
     */
    @Throws(IOException::class)
    fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse
}

/**
 * A response being read. Close it.
 *
 * @property status the HTTP status code.
 * @property contentLength the declared length, or -1 when unknown.
 * @property body the response body.
 */
class HttpResponse(
    val status: Int,
    val contentLength: Long,
    val body: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        try {
            body.close()
        } finally {
            onClose()
        }
    }
}

/**
 * Where a redirect may lead: HTTPS on one of [allowedHosts], at most
 * [maxRedirects] times. `HttpURLConnection` would follow any HTTPS
 * redirect on its own, so the font download could be sent anywhere
 * (SEC-10); the transport follows redirects itself through this.
 */
internal class RedirectGuard(
    private val allowedHosts: Set<String>,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    /** The URL to fetch next after [from] answered with [location]; this is redirect number [hop]. */
    @Throws(IOException::class)
    fun next(
        from: URL,
        location: String?,
        hop: Int,
    ): URL {
        val target = location?.takeIf { hop <= maxRedirects }?.let { resolve(from, it) }
        return target?.takeIf(::allowed) ?: throw IOException("Refusing redirect $hop from $from to $location")
    }

    private fun allowed(url: URL): Boolean = url.protocol == "https" && url.host in allowedHosts && url.userInfo == null

    private fun resolve(
        from: URL,
        location: String,
    ): URL? =
        try {
            URL(from, location)
        } catch (_: java.net.MalformedURLException) {
            null
        }

    companion object {
        /** Google answers in one hop at most; a few more is generous. */
        const val MAX_REDIRECTS: Int = 3

        /** The hosts the font download talks to. */
        val GOOGLE_FONTS: Set<String> = setOf("fonts.googleapis.com", Css2Api.FILE_HOST)
    }
}

/**
 * [HttpTransport] over `HttpURLConnection`: no extra dependency, HTTPS
 * only (enforced here and by the default network security config), and
 * redirects followed only to [allowedHosts] (SEC-10).
 *
 * @param connectTimeoutMillis how long to wait for a connection.
 * @param readTimeoutMillis how long a read may stall before failing.
 * @param allowedHosts where a redirect may lead.
 */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    allowedHosts: Set<String> = RedirectGuard.GOOGLE_FONTS,
) : HttpTransport {
    private val redirects = RedirectGuard(allowedHosts)

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse {
        var target = URL(url)
        if (target.protocol != "https") throw IOException("Refusing a non-HTTPS URL: $url")
        var hop = 0
        while (true) {
            val connection = open(target, headers)
            val status = connection.responseCode
            if (status !in REDIRECTS) return response(connection, status)
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            target = redirects.next(target, location, ++hop)
        }
    }

    private fun open(
        url: URL,
        headers: Map<String, String>,
    ): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = false
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        return connection
    }

    private fun response(
        connection: HttpURLConnection,
        status: Int,
    ): HttpResponse {
        val body =
            if (status in
                SUCCESS
            ) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream(ByteArray(0))
            }
        return HttpResponse(status, connection.contentLengthLong, body) { connection.disconnect() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000
        val SUCCESS = 200..299
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
    }
}
