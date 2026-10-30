package com.refboard.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import coil.map.Mapper
import coil.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures the app has saved to Pictures/RefBoard, by file name. The site
 * uses the same file name for every size of an image (only the size folder
 * in the address changes), so a saved picture stands in for its thumbnail
 * and full-size copy alike: anything downloaded can be viewed offline.
 */
object LocalImages {
    private val byName = ConcurrentHashMap<String, Uri>()

    /** Indexes what's already in Pictures/RefBoard (including earlier downloads). */
    fun start(context: Context, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME),
                    "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf("Pictures/RefBoard/%"),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        byName.putIfAbsent(name, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)))
                    }
                }
            }
        }
    }

    fun put(fileName: String, uri: Uri) {
        byName[fileName] = uri
    }

    /** The saved copy of a site image, if there is one. */
    fun find(url: String): Uri? {
        if (!url.startsWith("https://cdni.")) return null
        return byName[fileName(url)]
    }

    fun fileName(url: String) = url.substringBefore('?').substringAfterLast('/')

    /** Lets Coil load saved pictures from the phone instead of the site. */
    class CoilMapper : Mapper<String, Uri> {
        override fun map(data: String, options: Options): Uri? = find(data)
    }
}
