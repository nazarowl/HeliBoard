// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.emoji

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors

/**
 * All emoji categories in one vertical list: a header with the category name, followed by the category's
 * emoji pages (each page is a small grid keyboard of a few rows).
 */
internal class EmojiListAdapter(
    private val emojiCategory: EmojiCategory,
    private val emojiViewCallback: EmojiViewCallback,
    private val colors: Colors,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Item(val category: EmojiCategory.Category) {
        class Header(category: EmojiCategory.Category) : Item(category)
        class Page(category: EmojiCategory.Category, val pageId: Int) : Item(category)
    }

    private class HeaderHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    private class PageHolder(val keyboardView: EmojiPageKeyboardView) : RecyclerView.ViewHolder(keyboardView)

    private var items: List<Item> = emptyList()
    private var headerPositions: Map<EmojiCategory.Category, Int> = emptyMap()

    init {
        rebuild()
    }

    /** Re-creates the item list, needed when the number of pages may have changed. */
    fun rebuild() {
        val newItems = ArrayList<Item>()
        val newHeaderPositions = HashMap<EmojiCategory.Category, Int>()
        for (properties in emojiCategory.shownCategories) {
            val category = properties.category
            newHeaderPositions[category] = newItems.size
            newItems.add(Item.Header(category))
            for (pageId in 0 until emojiCategory.getCategoryPageCount(category)) {
                newItems.add(Item.Page(category, pageId))
            }
        }
        items = newItems
        headerPositions = newHeaderPositions
    }

    fun headerPosition(category: EmojiCategory.Category): Int = headerPositions[category] ?: 0

    fun pagePosition(category: EmojiCategory.Category, pageId: Int): Int = headerPosition(category) + 1 + pageId

    fun categoryAt(position: Int): EmojiCategory.Category? = items.getOrNull(position)?.category

    /** Page id at the given position, 0 for headers. */
    fun pageIdAt(position: Int): Int = (items.getOrNull(position) as? Item.Page)?.pageId ?: 0

    /** Number of items (header + pages) of the category. */
    fun itemCount(category: EmojiCategory.Category): Int = emojiCategory.getCategoryPageCount(category) + 1

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (items[position] is Item.Header) TYPE_HEADER else TYPE_PAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_HEADER) {
            val textView = TextView(parent.context)
            val density = parent.resources.displayMetrics.density
            textView.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            textView.setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (2 * density).toInt())
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            textView.setTextColor(colors.get(ColorType.EMOJI_CATEGORY))
            textView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            return HeaderHolder(textView)
        }
        val keyboardView = LayoutInflater.from(parent.context)
            .inflate(R.layout.emoji_keyboard_page, parent, false) as EmojiPageKeyboardView
        keyboardView.setEmojiViewCallback(emojiViewCallback)
        return PageHolder(keyboardView)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderHolder).textView.text = emojiCategory.getAccessibilityDescription(item.category)
            is Item.Page -> (holder as PageHolder).keyboardView.setKeyboard(emojiCategory.getKeyboard(item.category, item.pageId))
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is PageHolder) {
            holder.keyboardView.releaseCurrentKey(false)
            holder.keyboardView.deallocateMemory()
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PAGE = 1
    }
}
