package com.scorchsoft.ssmarkdown.fonts

import com.scorchsoft.ssmarkdown.fonts.download.HttpResponse
import com.scorchsoft.ssmarkdown.fonts.download.HttpTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** A canned network: each URL answers with a response, or throws. Records every request. */
class FakeHttp : HttpTransport {
    private val routes = mutableMapOf<String, () -> HttpResponse>()
    val requests = mutableListOf<Pair<String, Map<String, String>>>()

    fun answer(
        url: String,
        status: Int = 200,
        body: ByteArray,
        declaredLength: Long = body.size.toLong(),
    ) {
        routes[url] = { HttpResponse(status, declaredLength, ByteArrayInputStream(body)) }
    }

    fun stream(
        url: String,
        body: () -> InputStream,
    ) {
        routes[url] = { HttpResponse(200, -1, body()) }
    }

    fun fail(
        url: String,
        error: IOException,
    ) {
        routes[url] = { throw error }
    }

    fun failEverything(error: IOException) {
        default = { throw error }
    }

    private var default: (String) -> HttpResponse = {
        HttpResponse(404, 0, ByteArrayInputStream(ByteArray(0)))
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
    ): HttpResponse {
        requests += url to headers
        return routes[url]?.invoke() ?: default(url)
    }
}
