// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** Downloads and decodes GIF previews. Keeps the raw bytes in memory so scrolling back doesn't re-download. */
object GifPreviewLoader {
    private const val TAG = "GifPreviewLoader"

    private val cache = object : LruCache<String, ByteArray>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    suspend fun load(resources: android.content.res.Resources, url: String): Drawable? {
        val bytes = cache.get(url) ?: withContext(Dispatchers.IO) {
            try {
                KlipyClient.download(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "could not load preview $url", e)
                null
            }
        }?.also { cache.put(url, it) } ?: return null
        return withContext(Dispatchers.Default) { decode(resources, bytes) }
    }

    private fun decode(resources: android.content.res.Resources, bytes: ByteArray): Drawable? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // animated for GIF and animated WebP
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        } else {
            // older Android versions only show the first frame
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(resources, it) }
        }
    } catch (e: Exception) {
        Log.i(TAG, "could not decode preview", e)
        null
    }
}

@Composable
fun GifImage(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val resources = LocalContext.current.resources
    var drawable by remember(url) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(url) { drawable = GifPreviewLoader.load(resources, url) }
    DisposableEffect(drawable) {
        onDispose { (drawable as? Animatable)?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        modifier = modifier,
        update = { imageView ->
            imageView.contentDescription = contentDescription
            imageView.setImageDrawable(drawable)
            (drawable as? Animatable)?.start()
        }
    )
}
