// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.gif

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.emoji.EmojiSearchActivity
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.CloseIcon
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SearchIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "gif-search"

/**
 * GIF picker shown above the keyboard, like [EmojiSearchActivity] (and using the same mechanism, so the keyboard
 * treats the search field like the emoji search field). The selected GIF is downloaded and its file path is sent
 * back to the keyboard, which inserts it into the app the user was typing in.
 */
class GifSearchActivity : ComponentActivity() {
    private val colors = Settings.getValues().mColors
    private val scope = MainScope()
    private val handler by lazy { Handler(mainLooper) }
    private var imeOpened = false
    private var firstSearchDone = false
    private var imeVisible = false
    private var imeClosed = false
    private var screenHeight = 0

    private val gifs = mutableStateListOf<KlipyGif>()
    private var loading by mutableStateOf(false)
    private var errorRes by mutableStateOf<Int?>(null)
    private var sending by mutableStateOf(false)
    private var shownQuery by mutableStateOf("") // query of the results currently shown
    private var nextPage = 1
    private var hasNext = false
    private var loadToken = 0
    private var debounceJob: Job? = null

    private var selectedGif: KlipyGif? = null
    private var selectedFile: File? = null

    private val closer = Runnable {
        if (!imeVisible) {
            Log.d(TAG, "IME closed")
            imeClosed = true
            finish()
        }
    }

    @OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateScreenHeight()
        KeyboardSwitcher.getInstance().resetKeyboardStateToAlphabet()
        enableEdgeToEdge()
        setContent {
            LocalContext.current.setTheme(KeyboardTheme.getKeyboardTheme(this).mStyleId)
            val textColor = Color(colors.get(ColorType.EMOJI_KEY_TEXT))
            Surface(modifier = Modifier.fillMaxSize(), color = Color(0x80000000)) {
                var heightDp by remember { mutableStateOf(0.dp) }
                Column(modifier = Modifier.fillMaxSize().clickable(onClick = { finish() })
                    .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets(bottom = heightDp))),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    val localDensity = LocalDensity.current
                    var heightPx by remember { mutableIntStateOf(0) }
                    Column(modifier = Modifier.wrapContentHeight().background(Color(colors.get(ColorType.MAIN_BACKGROUND)))
                        .clickable(false) {}.onGloballyPositioned {
                            // same logic as in EmojiSearchActivity: close when the keyboard is closed or switched away
                            val bottom = it.localToScreen(Offset(0f, it.size.height.toFloat())).y.toInt()
                            imeVisible = bottom < screenHeight - 100
                            if (imeOpened && !imeVisible) {
                                handler.postDelayed(closer, 200)
                            }
                            if (imeOpened && !isAlphaKeyboard()) {
                                finish()
                                return@onGloballyPositioned
                            }
                            if (imeVisible && firstSearchDone && isAlphaKeyboard()) {
                                imeOpened = true
                                handler.removeCallbacks(closer)
                            }
                            heightPx = it.size.height
                            heightDp = with(localDensity) { it.size.height.toDp() }
                        }) {
                        val fontFamily = remember { KeyboardTypeface.customFontFamily() }
                        Row(modifier = Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) {
                                Icon(painter = painterResource(R.drawable.ic_arrow_back),
                                    stringResource(R.string.spoken_description_action_previous), tint = textColor)
                            }
                            Text(text = stringResource(R.string.gif_search_title), fontSize = 18.sp, fontFamily = fontFamily,
                                color = textColor, modifier = Modifier.weight(1f))
                            Text(text = stringResource(R.string.gif_powered_by), fontSize = 11.sp, fontFamily = fontFamily,
                                color = textColor.copy(alpha = 0.6f), modifier = Modifier.padding(end = 8.dp))
                        }
                        Box(modifier = Modifier.fillMaxWidth().height(GRID_HEIGHT.dp), contentAlignment = Alignment.Center) {
                            val message = when {
                                !KlipyClient.hasApiKey -> R.string.gif_no_api_key
                                gifs.isEmpty() && errorRes != null -> errorRes
                                gifs.isEmpty() && !loading && firstSearchDone -> R.string.gif_no_results
                                else -> null
                            }
                            if (message != null) {
                                Text(stringResource(message), color = textColor, textAlign = TextAlign.Center,
                                    fontFamily = fontFamily, modifier = Modifier.padding(16.dp))
                            } else if (gifs.isEmpty()) {
                                CircularProgressIndicator(color = textColor, modifier = Modifier.size(32.dp))
                            } else {
                                key(shownQuery) { // new grid state (scrolled to start) for each new search
                                    val gridState = rememberLazyStaggeredGridState()
                                    LaunchedEffect(gridState) {
                                        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                                            .collect { if (it >= gifs.size - 10) loadMore() }
                                    }
                                    LazyHorizontalStaggeredGrid(
                                        rows = StaggeredGridCells.Fixed(2),
                                        state = gridState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(4.dp),
                                        horizontalItemSpacing = 4.dp,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        items(gifs.size, key = { gifs[it].slug }) { index ->
                                            val gif = gifs[index]
                                            val ratio = (gif.preview.width.toFloat() / gif.preview.height).coerceIn(0.5f, 2.5f)
                                            GifImage(
                                                url = gif.preview.url,
                                                contentDescription = gif.title,
                                                modifier = Modifier.fillMaxHeight()
                                                    .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(colors.get(ColorType.KEY_BACKGROUND)))
                                                    .clickable { onGifClicked(gif) }
                                            )
                                        }
                                    }
                                }
                            }
                            if (sending) {
                                Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                        val focusRequester = remember { FocusRequester() }
                        var text by remember { mutableStateOf(TextFieldValue(lastQuery, selection = TextRange(lastQuery.length))) }
                        val textFieldColors = TextFieldDefaults.colors().copy(
                            unfocusedContainerColor = Color(colors.get(ColorType.EMOJI_SEARCH_BACKGROUND)),
                            unfocusedTextColor = Color(colors.get(ColorType.EMOJI_SEARCH_TEXT)),
                            cursorColor = Color(colors.get(ColorType.EMOJI_SEARCH_TEXT)),
                            unfocusedLeadingIconColor = Color(colors.get(ColorType.EMOJI_SEARCH_TEXT)),
                            unfocusedTrailingIconColor = Color(colors.get(ColorType.EMOJI_SEARCH_TEXT)),
                            unfocusedPlaceholderColor = lerp(Color(colors.get(ColorType.EMOJI_SEARCH_BACKGROUND)),
                                Color(colors.get(ColorType.EMOJI_SEARCH_TEXT)), 0.5f))
                        CompositionLocalProvider(
                            LocalTextSelectionColors provides textFieldColors.textSelectionColors,
                            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = fontFamily),
                        ) {
                            BasicTextField(
                                value = text,
                                modifier = Modifier.fillMaxWidth().heightIn(20.dp, 30.dp).focusRequester(focusRequester),
                                textStyle = TextStyle(fontFamily = fontFamily, textDirection = TextDirection.Content,
                                    color = textFieldColors.unfocusedTextColor),
                                onValueChange = {
                                    val changed = it.text != text.text
                                    text = it
                                    if (changed) onQueryChanged(it.text, immediately = false)
                                },
                                enabled = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
                                    platformImeOptions = PlatformImeOptions(EmojiSearchActivity.encodePrivateImeOptions(
                                        EmojiSearchActivity.PrivateImeOptions(heightPx)))),
                                keyboardActions = KeyboardActions(onSearch = { onQueryChanged(text.text, immediately = true) }),
                                singleLine = true,
                                cursorBrush = SolidColor(textFieldColors.cursorColor)
                            ) {
                                TextFieldDefaults.DecorationBox(
                                    value = text.text,
                                    colors = textFieldColors,
                                    contentPadding = PaddingValues(2.dp),
                                    visualTransformation = VisualTransformation.None,
                                    innerTextField = it,
                                    placeholder = { Text(stringResource(R.string.gif_search_placeholder), fontFamily = fontFamily) },
                                    leadingIcon = { SearchIcon() },
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            text = TextFieldValue()
                                            onQueryChanged("", immediately = true)
                                        }) { CloseIcon(android.R.string.cancel) }
                                    },
                                    singleLine = true,
                                    enabled = true,
                                    interactionSource = MutableInteractionSource(),
                                )
                            }
                        }
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    }
                }
            }
        }
    }

    override fun onEnterAnimationComplete() {
        load(lastQuery, reset = true)
        firstSearchDone = true
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenHeight()
        imeVisible = false
        imeOpened = false
        firstSearchDone = true
    }

    override fun onStop() {
        Log.d(TAG, "GIF search ending. Selected: ${selectedGif?.slug}, imeClosed: $imeClosed")
        val intent = Intent(EmojiSearchActivity.EMOJI_SEARCH_DONE_ACTION).setPackage(packageName)
            .putExtra(EmojiSearchActivity.IME_CLOSED_KEY, imeClosed)
        val gif = selectedGif
        val file = selectedFile
        if (gif != null && file != null) {
            intent.putExtra(GIF_FILE_KEY, file.absolutePath)
            intent.putExtra(GIF_URL_KEY, gif.full.url)
        }
        sendBroadcast(intent)
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun updateScreenHeight() {
        screenHeight = windowManager.defaultDisplay.height
    }

    private fun isAlphaKeyboard() = KeyboardSwitcher.getInstance().keyboardSwitchState !in
        setOf(KeyboardSwitcher.KeyboardSwitchState.EMOJI, KeyboardSwitcher.KeyboardSwitchState.CLIPBOARD)

    private fun onQueryChanged(query: String, immediately: Boolean) {
        if (imeVisible && !imeOpened) imeOpened = true
        lastQuery = query
        debounceJob?.cancel()
        debounceJob = scope.launch {
            if (!immediately) delay(SEARCH_DELAY_MS)
            if (query.trim() != shownQuery.trim() || gifs.isEmpty()) load(query, reset = true)
        }
    }

    private fun loadMore() {
        if (loading || !hasNext) return
        load(shownQuery, reset = false)
    }

    private fun load(query: String, reset: Boolean) {
        if (!KlipyClient.hasApiKey) return
        val token = ++loadToken
        val page = if (reset) 1 else nextPage
        loading = true
        errorRes = null
        if (reset) {
            gifs.clear()
            shownQuery = query
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { KlipyClient.fetch(query, page) }
                if (token != loadToken) return@launch
                val known = gifs.mapTo(HashSet()) { it.slug }
                gifs.addAll(result.gifs.filter { known.add(it.slug) })
                nextPage = result.page + 1
                hasNext = result.hasNext && result.gifs.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "loading GIFs failed", e)
                if (token == loadToken) errorRes = R.string.gif_load_error
            } finally {
                if (token == loadToken) loading = false
            }
        }
    }

    private fun onGifClicked(gif: KlipyGif) {
        if (sending) return
        sending = true
        val query = shownQuery
        scope.launch {
            try {
                val file = File(GifInserter.gifDir(this@GifSearchActivity), gif.slug.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".gif")
                withContext(Dispatchers.IO) {
                    GifInserter.cleanup(this@GifSearchActivity)
                    if (file.isFile && file.length() > 0) file.setLastModified(System.currentTimeMillis())
                    else KlipyClient.downloadTo(gif.full.url, file)
                }
                Thread { KlipyClient.registerShare(gif, query) }.start()
                selectedGif = gif
                selectedFile = file
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "downloading GIF failed", e)
                Toast.makeText(this@GifSearchActivity, R.string.gif_send_error, Toast.LENGTH_SHORT).show()
                sending = false
            }
        }
    }

    companion object {
        const val GIF_FILE_KEY: String = "GIF_FILE"
        const val GIF_URL_KEY: String = "GIF_URL"
        private const val GRID_HEIGHT = 200
        private const val SEARCH_DELAY_MS = 400L
        private var lastQuery: String = ""
    }
}
