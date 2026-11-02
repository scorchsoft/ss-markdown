package com.scorchsoft.ssmarkdown.fixtures

import androidx.core.content.FileProvider

/**
 * `FileProvider` caches each authority's roots in a static map, but
 * Robolectric gives every test fresh app directories. Without a reset, the
 * second test in a JVM to share a file resolves it against the first test's
 * cache directory, fails, and shares nothing. Call [reset] before each test
 * that shares a file.
 */
object FileProviderCache {
    fun reset() {
        val field = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        val cache = field.get(null) as MutableMap<*, *>
        synchronized(cache) { cache.clear() }
    }
}
