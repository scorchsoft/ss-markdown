package com.scorchsoft.ssmarkdown.files.memory

import android.app.ActivityManager
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

/**
 * [MemorySource] over the platform: `Runtime`, `ActivityManager` and
 * `StorageManager`. Each read is a fresh query; none is cached.
 *
 * @param filesDir the app's files directory; its volume is the one journals and history use.
 */
class AndroidMemorySource(
    private val activityManager: ActivityManager,
    private val storageManager: StorageManager,
    private val filesDir: File,
) : MemorySource {
    override fun heap(): HeapStats {
        val runtime = Runtime.getRuntime()
        return HeapStats(runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory())
    }

    override fun system(): SystemMemory {
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return SystemMemory(info.availMem, info.threshold, info.lowMemory)
    }

    override val isLowRamDevice: Boolean get() = activityManager.isLowRamDevice

    override val memoryClassMegabytes: Int get() = activityManager.memoryClass

    override fun allocatableStorageBytes(): Long =
        try {
            storageManager.getAllocatableBytes(storageManager.getUuidForPath(filesDir))
        } catch (_: IOException) {
            filesDir.usableSpace
        }
}
