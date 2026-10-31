package com.refboard.app.data

import coil3.Uri
import coil3.map.Mapper
import coil3.request.Options
import coil3.toUri
import com.refboard.app.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pictures the app has saved, by file name. The site uses the same file
 * name for every size of an image (only the size folder in the address
 * changes), so a saved picture stands in for its thumbnail and full-size
 * copy alike: anything downloaded can be viewed offline.
 */
object LocalImages {
    /** File name -> address the image loader can open (content:// or file://). */
    private val byName = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Indexes what's already saved (including earlier downloads). */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { Platform.services.imageSink.listAll() }.getOrDefault(emptyList())
            byName.update { current -> saved.toMap() + current }
        }
    }

    fun put(fileName: String, address: String) {
        byName.update { it + (fileName to address) }
    }

    /** The saved copy of a site image, if there is one. */
    fun find(url: String): String? {
        if (!url.startsWith("https://cdni.")) return null
        return byName.value[fileName(url)]
    }

    fun fileName(url: String) = url.substringBefore('?').substringAfterLast('/')

    /** Lets the image loader open saved pictures from the device instead of the site. */
    class CoilMapper : Mapper<String, Uri> {
        override fun map(data: String, options: Options): Uri? = find(data)?.toUri()
    }
}
