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
 * [HttpTransport] over `HttpURLConnection`: no extra dependency, HTTPS
 * only (enforced here and by the default network security config).
 *
 * @param connectTimeoutMillis how long to wait for a connection.
 * @param readTimeoutMillis how long a read may stall before failing.
 */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : HttpTransport {
    override fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw IOException("Refusing a non-HTTPS URL: $url")
        val connection = parsed.openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.instanceFollowRedirects = true
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        val status = connection.responseCode
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
    }
}
