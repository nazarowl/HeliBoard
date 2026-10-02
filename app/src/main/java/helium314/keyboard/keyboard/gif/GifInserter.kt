// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Log
import java.io.File

object GifInserter {
    private const val TAG = "GifInserter"
    const val GIF_MIME_TYPE = "image/gif"
    private const val MAX_CACHED_FILES = 10

    fun gifDir(context: Context) = File(context.cacheDir, "gifs")

    /** Deletes all but the newest few downloaded GIFs. */
    fun cleanup(context: Context) {
        val files = gifDir(context).listFiles() ?: return
        files.sortedByDescending { it.lastModified() }.drop(MAX_CACHED_FILES).forEach { it.delete() }
    }

    /**
     * Sends the GIF to the app being typed in.
     * Uses image insertion if the text field supports GIFs, the clipboard paste fallback if the field does not
     * declare any supported types, and inserts the link as text if the field only supports other content.
     */
    @JvmStatic
    fun insert(latinIME: LatinIME, filePath: String?, url: String?) {
        val file = filePath?.let { File(it) }
        if (file == null || !file.isFile) {
            Log.w(TAG, "GIF file missing, inserting link instead")
            if (!url.isNullOrEmpty()) latinIME.onTextInput(url)
            return
        }
        val editorMimeTypes = latinIME.currentInputEditorInfo?.let { EditorInfoCompat.getContentMimeTypes(it) } ?: emptyArray()
        if (editorMimeTypes.isNotEmpty() && editorMimeTypes.none { ClipDescription.compareMimeTypes(GIF_MIME_TYPE, it) }) {
            Log.i(TAG, "text field does not accept GIFs (${editorMimeTypes.joinToString()}), inserting link")
            if (!url.isNullOrEmpty()) latinIME.onTextInput(url)
            return
        }
        val uri = try {
            FileProvider.getUriForFile(latinIME, latinIME.getString(R.string.clipboard_provider_authority), file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "could not create content uri for GIF", e)
            if (!url.isNullOrEmpty()) latinIME.onTextInput(url)
            return
        }
        val content = InputContentInfoCompat(uri, ClipDescription("GIF", arrayOf(GIF_MIME_TYPE)), url?.let { Uri.parse(it) })
        latinIME.mKeyboardActionListener.onContent(content)
    }
}
