package com.refboard.app.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Path

/** Small text-file helpers over okio, so storage code is the same on every device. */
object Files {
    fun readText(path: Path): String? =
        runCatching { fileSystem.read(path) { readUtf8() } }.getOrNull()

    /** Writes via a temporary file and a rename, so a crash never leaves half a file. */
    fun writeTextAtomic(path: Path, text: String) {
        path.parent?.let { fileSystem.createDirectories(it) }
        val tmp = path.parent!! / (path.name + ".tmp")
        fileSystem.write(tmp) { writeUtf8(text) }
        fileSystem.atomicMove(tmp, path)
    }

    suspend fun readTextIo(path: Path): String? = withContext(Dispatchers.IO) { readText(path) }

    suspend fun writeTextAtomicIo(path: Path, text: String) = withContext(Dispatchers.IO) {
        runCatching { writeTextAtomic(path, text) }
    }

    fun lastModified(path: Path): Long? = runCatching { fileSystem.metadata(path).lastModifiedAtMillis }.getOrNull()
}

/** Today's date as yyyy-MM-dd (UTC), for file names. */
fun todayStamp(): String {
    // Days since 1970 to a civil date (Howard Hinnant's algorithm).
    val days = nowMillis() / 86_400_000L
    val z = days + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
