/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.emoji;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import helium314.keyboard.event.HapticEvent;
import helium314.keyboard.keyboard.Key;
import helium314.keyboard.keyboard.Keyboard;
import helium314.keyboard.keyboard.KeyboardActionListener;
import helium314.keyboard.keyboard.KeyboardElement;
import helium314.keyboard.keyboard.KeyboardLayoutSet;
import helium314.keyboard.keyboard.KeyboardSwitcher;
import helium314.keyboard.keyboard.KeyboardView;
import helium314.keyboard.keyboard.MainKeyboardView;
import helium314.keyboard.keyboard.PointerTracker;
import helium314.keyboard.keyboard.internal.KeyDrawParams;
import helium314.keyboard.keyboard.internal.KeyVisualAttributes;
import helium314.keyboard.keyboard.internal.keyboard_parser.EmojiParserKt;
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode;
import helium314.keyboard.latin.AudioAndHapticFeedbackManager;
import helium314.keyboard.latin.dictionary.Dictionary;
import helium314.keyboard.latin.dictionary.DictionaryFactory;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.RichInputMethodManager;
import helium314.keyboard.latin.RichInputMethodSubtype;
import helium314.keyboard.latin.SingleDictionaryFacilitator;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.DictionaryInfoUtils;
import helium314.keyboard.latin.utils.ResourceUtils;

import static helium314.keyboard.latin.common.Constants.NOT_A_COORDINATE;

/**
 * View class to implement Emoji palettes.
 * The Emoji keyboard consists of group of views layout/emoji_palettes_view.
 * <ol>
 * <li> Emoji category tabs.
 * <li> Delete button.
 * <li> Emoji keyboard pages that can be scrolled by swiping horizontally or by selecting a tab.
 * <li> Back to main keyboard button and enter button.
 * </ol>
 * Because of the above reasons, this class doesn't extend {@link KeyboardView}.
 */
public final class EmojiPalettesView extends LinearLayout
        implements View.OnClickListener, EmojiViewCallback {
    private static SingleDictionaryFacilitator sDictionaryFacilitator;

    private boolean initialized = false;
    private final Colors mColors;
    private final EmojiLayoutParams mEmojiLayoutParams;
    private LinearLayout mTabStrip;
    private EmojiCategoryPageIndicatorView mEmojiCategoryPageIndicatorView;
    private KeyboardActionListener mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;
    private final EmojiCategory mEmojiCategory;
    private RecyclerView mPager; // all categories in one vertical list
    private EmojiListAdapter mListAdapter;
    private LinearLayoutManager mLayoutManager;
    private boolean mTabJumpPending; // keep the tapped tab selected even if its section can't scroll to the top

    public EmojiPalettesView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.emojiPalettesViewStyle);
    }

    public EmojiPalettesView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        mColors = Settings.getValues().mColors;
        final KeyboardLayoutSet.Builder builder = new KeyboardLayoutSet.Builder(context, null, null);
        final Resources res = context.getResources();
        mEmojiLayoutParams = new EmojiLayoutParams(res);
        builder.setSubtype(RichInputMethodSubtype.Companion.getEmojiSubtype());
        builder.setKeyboardGeometry(ResourceUtils.getKeyboardWidth(context, Settings.getValues()),
                mEmojiLayoutParams.getEmojiKeyboardHeight());
        final KeyboardLayoutSet layoutSet = builder.build();
        final TypedArray emojiPalettesViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.EmojiPalettesView, defStyle, R.style.EmojiPalettesView);
        mEmojiCategory = new EmojiCategory(context, layoutSet, emojiPalettesViewAttr);
        emojiPalettesViewAttr.recycle();
        setFitsSystemWindows(true);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final Resources res = getContext().getResources();
        // The main keyboard expands to the entire this {@link KeyboardView}.
        final int width = ResourceUtils.getKeyboardWidth(getContext(), Settings.getValues())
                + getPaddingLeft() + getPaddingRight();
        final int height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues())
                + getPaddingTop() + getPaddingBottom();
        mEmojiCategoryPageIndicatorView.mWidth = width;
        setMeasuredDimension(width, height);
    }

    private void addTab(LinearLayout host, EmojiCategory.Category category) {
        final ImageView iconView = new ImageView(getContext());
        mColors.setBackground(iconView, ColorType.STRIP_BACKGROUND);
        mColors.setColor(iconView, ColorType.EMOJI_CATEGORY);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageResource(mEmojiCategory.getCategoryTabIcon(category));
        iconView.setContentDescription(mEmojiCategory.getAccessibilityDescription(category));
        iconView.setTag(category);
        host.addView(iconView);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        iconView.setOnClickListener(this);
        if (category == EmojiCategory.Category.RECENTS) {
            iconView.setOnLongClickListener(v -> {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_LONG_PRESS);
                clearRecentKeys();

                return true;
            });
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    public void initialize() { // needs to be delayed for access to EmojiTabStrip, which is not a child of this view
        if (initialized) return;
        mEmojiCategory.initialize();
        mTabStrip = (LinearLayout) KeyboardSwitcher.getInstance().getEmojiTabStrip();
        if (Settings.getValues().isSecondaryStripVisible()) {
            for (EmojiCategory.CategoryProperties properties : mEmojiCategory.getShownCategories()) {
                addTab(mTabStrip, properties.getCategory());
            }
        }

        mPager = findViewById(R.id.emoji_pager);
        mLayoutManager = new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false);
        mPager.setLayoutManager(mLayoutManager);
        mListAdapter = new EmojiListAdapter(mEmojiCategory, this, mColors);
        mPager.setAdapter(mListAdapter);
        mPager.setItemAnimator(null); // avoid flicker when recents change
        mPager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                onListScrolled();
            }
        });
        mEmojiLayoutParams.setEmojiListProperties(mPager);
        mEmojiCategoryPageIndicatorView = findViewById(R.id.emoji_category_page_id_view);
        mEmojiLayoutParams.setCategoryPageIdViewProperties(mEmojiCategoryPageIndicatorView);
        setCurrentCategory(mEmojiCategory.getCurrentCategory(), true);
        final int pageId = mEmojiCategory.getCurrentCategoryPageId();
        mLayoutManager.scrollToPositionWithOffset(pageId == 0
                ? mListAdapter.headerPosition(mEmojiCategory.getCurrentCategory())
                : mListAdapter.pagePosition(mEmojiCategory.getCurrentCategory(), pageId), 0);
        mEmojiCategoryPageIndicatorView.setColors(mColors.get(ColorType.EMOJI_CATEGORY_SELECTED), mColors.get(ColorType.STRIP_BACKGROUND));
        initialized = true;
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link android.view.View.OnClickListener}
     * interface to handle non-canceled touch-up events from View-based elements such as the space
     * bar.
     */
    @Override
    public void onClick(View v) {
        final Object tag = v.getTag();
        if (tag instanceof EmojiCategory.Category category) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS);
            // jump to the category's section in the list, the scroll listener updates the selected tab
            mPager.stopScroll();
            mTabJumpPending = true;
            mLayoutManager.scrollToPositionWithOffset(mListAdapter.headerPosition(category), 0);
            setCurrentCategory(category, false);
        }
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     */
    @Override
    public void onPressKey(final Key key) {
        final int code = key.getCode();
        mKeyboardActionListener.onPressKey(code, 0, 1, HapticEvent.KEY_PRESS);
    }

    /**
     * Called from {@link EmojiPageKeyboardView} through {@link EmojiViewCallback}
     * interface to handle touch events from non-View-based elements such as Emoji buttons.
     * This may be called without any prior call to {@link EmojiViewCallback#onPressKey(Key)}.
     */
    @Override
    public void onReleaseKey(final Key key) {
        addRecentKey(key);
        final int code = key.getCode();
        if (code == KeyCode.MULTIPLE_CODE_POINTS) {
            // todo: when we enter some emoticons, e.g. :-D, inline emoji search is triggered and emoji view is closed
            //  (one more instance of "emoji search should recognize emoticons", but in this case could be fixed in other ways)
            mKeyboardActionListener.onTextInput(key.getOutputText());
        } else if (code != KeyCode.UNSPECIFIED) {
            mKeyboardActionListener.onCodeInput(code, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
        }
        mKeyboardActionListener.onReleaseKey(code, false);
        if (Settings.getValues().mAlphaAfterEmojiInEmojiView)
            mKeyboardActionListener.onCodeInput(KeyCode.ALPHA, NOT_A_COORDINATE, NOT_A_COORDINATE, false);
    }

    @Override
    public void onRemoveRecentsKey(Key key)
    {
        getRecentsKeyboard().removeRecentsKey(key);
    }

    private void clearRecentKeys() {
        if (RecentEmojis.get().isEmpty()) return;
        RecentEmojis.clear();
        getRecentsKeyboard().removeAllKeys();
        notifyRecentsChanged();
        KeyboardSwitcher.getInstance().showToast(getResources().getString(R.string.recent_emojis_cleared), true);
    }

    @Override
    public String getDescription(String emoji) {
        if (sDictionaryFacilitator == null) {
            return null;
        }

        var wordProperty = sDictionaryFacilitator.getWordProperty(EmojiParserKt.getEmojiNeutralVersion(emoji));
        if (wordProperty == null || ! wordProperty.mHasShortcuts) {
            return null;
        }

        return wordProperty.mShortcutTargets.get(0).mWord;
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void startEmojiPalettes(final KeyVisualAttributes keyVisualAttr,
               final EditorInfo editorInfo, final KeyboardActionListener keyboardActionListener) {
        initialize();

        setupBottomRowKeyboard(editorInfo, keyboardActionListener);
        final KeyDrawParams params = new KeyDrawParams();
        params.updateParams(mEmojiLayoutParams.getBottomRowKeyboardHeight(), keyVisualAttr);
        new EmojiLayoutParams(getResources()).setEmojiListProperties(mPager); // necessary when floating
        mEmojiCategory.reloadRecents(); // in case recents changed from outside emoji keyboards
        setupSidePadding();
        initDictionaryFacilitator();
    }

    private void addRecentKey(Key key) {
        if (Settings.getValues().mIncognitoModeEnabled) {
            // We do not want to log recent keys while being in incognito
            return;
        }
        if (mEmojiCategory.isInRecentTab()) {
            getRecentsKeyboard().addPendingKey(key);
            return;
        }
        getRecentsKeyboard().addKeyFirst(key);
        notifyRecentsChanged();
    }

    private void notifyRecentsChanged() {
        if (mListAdapter != null)
            mListAdapter.notifyItemChanged(mListAdapter.pagePosition(EmojiCategory.Category.RECENTS, 0));
    }

    /** Selects the tab of the category at the top of the list, and updates the position indicator. */
    private void onListScrolled() {
        final int first = mLayoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return;
        if (mTabJumpPending && mPager.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
            mTabJumpPending = false;
            return;
        }
        mTabJumpPending = false;
        final EmojiCategory.Category category = mListAdapter.categoryAt(first);
        if (category == null) return;
        setCurrentCategory(category, false);
        final int pageId = mListAdapter.pageIdAt(first);
        if (mEmojiCategory.getCurrentCategoryPageId() != pageId)
            mEmojiCategory.setCurrentCategoryPageId(pageId);

        final int categoryCount = mEmojiCategory.getShownCategories().size();
        final int categoryIndex = mEmojiCategory.getTabIdFromCategoryId(category);
        final float progress = (first - mListAdapter.headerPosition(category)) / (float) mListAdapter.itemCount(category);
        mEmojiCategoryPageIndicatorView.setCategoryPageId(categoryCount, categoryIndex, progress);
    }

    private void setupBottomRowKeyboard(EditorInfo editorInfo, KeyboardActionListener keyboardActionListener) {
        MainKeyboardView keyboardView = findViewById(R.id.bottom_row_keyboard);
        keyboardView.setKeyboardActionListener(keyboardActionListener);
        PointerTracker.switchTo(keyboardView);
        KeyboardLayoutSet kls = KeyboardLayoutSet.Builder.Companion.buildEmojiClipBottomRow(getContext(), editorInfo);
        Keyboard keyboard = kls.getKeyboard(KeyboardElement.EMOJI_BOTTOM_ROW);
        keyboardView.setKeyboard(keyboard);
    }

    private void setupSidePadding() {
        final SettingsValues sv = Settings.getValues();
        final int keyboardWidth = ResourceUtils.getKeyboardWidth(getContext(), sv);
        final TypedArray keyboardAttr = getContext().obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard);
        final float leftPadding = keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        final float rightPadding =  keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f) * sv.mSidePaddingScale;
        keyboardAttr.recycle();
        mPager.setPadding(
                (int) leftPadding,
                mPager.getPaddingTop(),
                (int) rightPadding,
                mPager.getPaddingBottom()
        );
        mEmojiCategoryPageIndicatorView.setPadding(
                (int) leftPadding,
                mEmojiCategoryPageIndicatorView.getPaddingTop(),
                (int) rightPadding,
                mEmojiCategoryPageIndicatorView.getPaddingBottom()
        );
        // setting width does not do anything, so we have some workaround in EmojiCategoryPageIndicatorView
    }

    public void stopEmojiPalettes() {
        if (!initialized) return;
        getRecentsKeyboard().flushPendingRecentKeys();
    }

    private DynamicGridKeyboard getRecentsKeyboard() {
        return mEmojiCategory.getKeyboard(EmojiCategory.Category.RECENTS, 0);
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
    }

    private void setCurrentCategory(EmojiCategory.Category category, boolean initial) {
        EmojiCategory.Category oldCategory = mEmojiCategory.getCurrentCategory();
        if (initial || oldCategory != category) {
            mEmojiCategory.setCurrentCategory(category);
            if (!initial && oldCategory == EmojiCategory.Category.RECENTS) {
                // recents are not re-ordered while visible, apply the pending changes when leaving them
                getRecentsKeyboard().flushPendingRecentKeys();
                mPager.post(this::notifyRecentsChanged);
            }

            if (Settings.getValues().isSecondaryStripVisible()) {
                View old = mTabStrip.findViewWithTag(oldCategory);
                View current = mTabStrip.findViewWithTag(category);

                if (old instanceof ImageView)
                    Settings.getValues().mColors.setColor((ImageView) old, ColorType.EMOJI_CATEGORY);
                if (current instanceof ImageView)
                    Settings.getValues().mColors.setColor((ImageView) current, ColorType.EMOJI_CATEGORY_SELECTED);
            }
        }
    }

    public void clearKeyboardCache() {
        if (!initialized) {
            return;
        }

        mEmojiCategory.clearKeyboardCache();
        mListAdapter.rebuild();
        mListAdapter.notifyDataSetChanged();
        closeDictionaryFacilitator();
    }

    private void initDictionaryFacilitator() {
        if (Settings.getValues().mShowEmojiDescriptions) {
            var locale = RichInputMethodManager.getInstance().getCurrentSubtype().getLocale();
            if (sDictionaryFacilitator == null || ! sDictionaryFacilitator.isForLocale(locale)) {
                closeDictionaryFacilitator();
                var dictFile = DictionaryInfoUtils.getCachedDictForLocaleAndType(locale, Dictionary.TYPE_EMOJI, getContext());
                var dictionary = dictFile != null? DictionaryFactory.getDictionary(dictFile, locale) : null;
                sDictionaryFacilitator = dictionary != null? new SingleDictionaryFacilitator(dictionary) : null;
            }
        } else {
            closeDictionaryFacilitator();
        }
    }

    public static void closeDictionaryFacilitator() {
        if (sDictionaryFacilitator != null) {
            sDictionaryFacilitator.closeDictionaries();
            sDictionaryFacilitator = null;
        }
    }
}
