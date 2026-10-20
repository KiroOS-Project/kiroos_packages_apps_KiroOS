/*
 * Copyright (C) 2026 KiroOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kiroos.home;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Kiro Home Beta 5: the widget picker, in the home itself. A sheet with every widget as a real,
 * live preview (the widget bound for a moment and drawn small), and above it the widget picked
 * shown large at the size chosen (Small 2 x 2, Medium 4 x 2, Large 4 x 4, as far as the widget
 * allows). "Add to Home" places it on the page shown. The widgets bound for the previews and
 * not added are unbound when the picker closes.
 */
final class WidgetPicker extends FrameLayout {

    interface Host {
        /** add the widget id (already bound, or the clock) at rows x cols */
        void onWidgetPicked(int id, int rows, int cols);

        /** a widget whose app wants to set it up first: bind a new id and configure it */
        void onWidgetNeedsConfigure(AppWidgetProviderInfo info);

        void onPickerClosed();
    }

    /** one choice of the picker: the home's clock, or an app's widget */
    private static final class Entry {
        AppWidgetProviderInfo info; // null: the home's clock
        String name;
        String app;
        /** the id bound for the card's preview, -1 when shown as a picture */
        int previewId = -1;
    }

    private final HomeActivity mActivity;
    private final WidgetStack mWidgets;
    private final Host mHost;
    private final AppWidgetManager mManager;
    private final float mDp;
    private final int mCellW;
    private final int mCellH;
    private final List<Entry> mEntries = new ArrayList<>();
    private final List<Integer> mBound = new ArrayList<>();

    private final View mScrim;
    private final LinearLayout mSheet;
    private final FrameLayout mHeroArea;
    private final TextView mHeroCaption;
    private final LinearLayout mSizes;
    private final GridLayout mGrid;
    private final List<FrameLayout> mCards = new ArrayList<>();

    private Entry mSelected;
    /** the id bound for the large preview of the widget picked (-1: none, or the clock) */
    private int mHeroId = -1;
    private List<int[]> mHeroSizes = new ArrayList<>();
    private int mSize;
    private ScaledFrame mHero;
    private boolean mClosing;

    WidgetPicker(HomeActivity activity, WidgetStack widgets, int cellW, int cellH,
            boolean clockOnHome, Host host) {
        super(activity);
        mActivity = activity;
        mWidgets = widgets;
        mHost = host;
        mManager = AppWidgetManager.getInstance(activity);
        mDp = getResources().getDisplayMetrics().density;
        mCellW = cellW;
        mCellH = cellH;

        mScrim = new View(activity);
        mScrim.setBackgroundColor(0xB3000000);
        mScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        addView(mScrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // the widget picked, large, above the sheet
        mHeroArea = new FrameLayout(activity);
        addView(mHeroArea, new LayoutParams(LayoutParams.MATCH_PARENT, dp(250), Gravity.TOP));
        mHeroCaption = new TextView(activity);
        mHeroCaption.setTextColor(0x9EFFFFFF);
        mHeroCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mHeroCaption.setGravity(Gravity.CENTER_HORIZONTAL);
        mHeroArea.addView(mHeroCaption, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // the sheet
        mSheet = new LinearLayout(activity);
        mSheet.setOrientation(LinearLayout.VERTICAL);
        mSheet.setClickable(true);
        // Beta 5: frosted glass, like the app drawer
        final float r = 28 * mDp;
        mSheet.setBackground(new Glass.GlassDrawable(mSheet, r, r, 0, 0, 0xFF101115));
        mSheet.setClipToOutline(true);
        mSheet.setPadding(dp(16), dp(10), dp(16), 0);
        final LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        slp.topMargin = dp(262);
        addView(mSheet, slp);

        final View handle = new View(activity);
        final GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x47FFFFFF);
        hb.setCornerRadius(2 * mDp);
        handle.setBackground(hb);
        final LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        mSheet.addView(handle, hlp);

        final LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.BOTTOM);
        final TextView title = new TextView(activity);
        title.setText(R.string.picker_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        final TextView hint = new TextView(activity);
        hint.setText(R.string.picker_hint);
        hint.setTextColor(0x80FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titleRow.addView(hint);
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        mSheet.addView(titleRow, tlp);

        // Small / Medium / Large
        mSizes = new LinearLayout(activity);
        mSizes.setPadding(dp(4), dp(4), dp(4), dp(4));
        final GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(0x0FFFFFFF);
        segBg.setCornerRadius(21 * mDp);
        mSizes.setBackground(segBg);
        final LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, dp(42));
        sizeLp.topMargin = dp(10);
        mSheet.addView(mSizes, sizeLp);

        // the cards
        final ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(14), 0, dp(80));
        mGrid = new GridLayout(activity);
        mGrid.setColumnCount(3);
        scroll.addView(mGrid, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));
        mSheet.addView(scroll, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        // Add to Home, over the bottom of the cards
        final Button add = new Button(activity);
        add.setText(R.string.picker_add);
        add.setAllCaps(false);
        add.setTextColor(0xFFFFFFFF);
        add.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        add.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final GradientDrawable ab = new GradientDrawable();
        ab.setColor(getResources().getColor(R.color.kiro_accent, null));
        ab.setCornerRadius(23 * mDp);
        add.setBackground(ab);
        add.setStateListAnimator(null);
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                addSelected();
            }
        });
        final LayoutParams addLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(46),
                Gravity.BOTTOM);
        addLp.setMargins(dp(16), 0, dp(16), dp(14));
        addView(add, addLp);

        loadEntries(clockOnHome);
        buildCards();
        if (!mEntries.isEmpty()) select(mEntries.get(0));
    }

    private int dp(float v) {
        return Math.round(v * mDp);
    }

    void setInsets(int top, int bottom) {
        final LayoutParams h = (LayoutParams) mHeroArea.getLayoutParams();
        h.topMargin = top;
        mHeroArea.setLayoutParams(h);
        final LayoutParams s = (LayoutParams) mSheet.getLayoutParams();
        s.topMargin = top + dp(250);
        mSheet.setLayoutParams(s);
        setPadding(0, 0, 0, bottom);
    }

    // --- the choices ---------------------------------------------------------------------

    private void loadEntries(boolean clockOnHome) {
        final PackageManager pm = getContext().getPackageManager();
        if (!clockOnHome) {
            final Entry clock = new Entry();
            clock.name = getContext().getString(R.string.clock_widget);
            clock.app = getContext().getString(R.string.app_name);
            mEntries.add(clock);
        }
        final List<Entry> apps = new ArrayList<>();
        for (AppWidgetProviderInfo info : mManager.getInstalledProvidersForProfile(
                Process.myUserHandle())) {
            final Entry e = new Entry();
            e.info = info;
            e.name = info.loadLabel(pm);
            try {
                e.app = pm.getApplicationLabel(pm.getApplicationInfo(
                        info.provider.getPackageName(), 0)).toString();
            } catch (PackageManager.NameNotFoundException ex) {
                e.app = info.provider.getPackageName();
            }
            if (e.name == null) e.name = e.app;
            apps.add(e);
        }
        final Collator c = Collator.getInstance();
        c.setStrength(Collator.PRIMARY);
        Collections.sort(apps, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                final int byApp = c.compare(a.app, b.app);
                return byApp != 0 ? byApp : c.compare(a.name, b.name);
            }
        });
        mEntries.addAll(apps);
    }

    /** a widget bound for a moment, for a preview; -1 when it cannot be (or must be set up) */
    private int bindPreview(AppWidgetProviderInfo info) {
        if (info.configure != null) return -1;
        int id = -1;
        try {
            id = mWidgets.allocateId();
            if (mManager.bindAppWidgetIdIfAllowed(id, info.provider)) {
                mBound.add(id);
                return id;
            }
        } catch (RuntimeException e) {
            // the widget service refused it: a picture instead
        }
        if (id != -1) mWidgets.host().deleteAppWidgetId(id);
        return -1;
    }

    /** {rows, cols} a widget opens with */
    private int[] defaultCells(Entry e) {
        if (e.info == null) return new int[] {2, HomeModel.COLUMNS};
        final int inset = dp(12);
        final int cols = e.info.minWidth + inset <= 2 * mCellW ? 2 : HomeModel.COLUMNS;
        final int rows = Math.max(1, Math.min(4, (e.info.minHeight + inset + mCellH - 1) / mCellH));
        return new int[] {rows, cols};
    }

    /** the view of a widget at a size of the home, or its picture when it cannot be shown live */
    private View widgetView(Entry e, int id) {
        if (e.info == null) {
            return LayoutInflater.from(getContext()).inflate(R.layout.home_clock, null, false);
        }
        if (id != -1) {
            final AppWidgetHostView v = mWidgets.host().createView(
                    getContext().getApplicationContext(), id, e.info);
            v.setAppWidget(id, e.info);
            // it does not take touches here: a tap picks the card
            return v;
        }
        final ImageView img = new ImageView(getContext());
        Drawable d = null;
        try {
            d = e.info.loadPreviewImage(getContext(), 0);
            if (d == null) d = e.info.loadIcon(getContext(), 0);
        } catch (RuntimeException ex) {
            // no picture
        }
        img.setImageDrawable(d);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return img;
    }

    private void buildCards() {
        final int width = getResources().getDisplayMetrics().widthPixels - dp(32);
        final int cardW = (width - 2 * dp(10)) / 3;
        final int cardH = dp(76);
        for (final Entry e : mEntries) {
            final LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            final FrameLayout frame = new FrameLayout(getContext());
            final GradientDrawable fb = new GradientDrawable();
            fb.setColor(0xF01C1E24);
            fb.setCornerRadius(16 * mDp);
            frame.setBackground(fb);
            frame.setClipToOutline(true);
            frame.setForeground(null);
            e.previewId = e.info != null ? bindPreview(e.info) : -1;
            final int[] cells = defaultCells(e);
            final ScaledFrame preview = new ScaledFrame(getContext(), cells[1] * mCellW,
                    cells[0] * mCellH, cardW - dp(8), cardH - dp(8));
            preview.addView(widgetView(e, e.previewId));
            frame.addView(preview, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            // a layer above the live widget takes the taps
            final View tap = new View(getContext());
            tap.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(e);
                }
            });
            frame.addView(tap, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
            mCards.add(frame);
            card.addView(frame, new LinearLayout.LayoutParams(cardW, cardH));

            final TextView name = new TextView(getContext());
            name.setText(e.name);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(dp(2), dp(7), dp(2), 0);
            card.addView(name, new LinearLayout.LayoutParams(cardW, LayoutParams.WRAP_CONTENT));
            final TextView app = new TextView(getContext());
            app.setText(e.app);
            app.setTextColor(0x80FFFFFF);
            app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            app.setSingleLine(true);
            app.setEllipsize(android.text.TextUtils.TruncateAt.END);
            app.setPadding(dp(2), 0, dp(2), 0);
            card.addView(app, new LinearLayout.LayoutParams(cardW, LayoutParams.WRAP_CONTENT));

            final GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            final int col = (mCards.size() - 1) % 3;
            glp.setMargins(col == 0 ? 0 : dp(5), 0, col == 2 ? 0 : dp(5), dp(12));
            mGrid.addView(card, glp);
        }
    }

    // --- the widget picked -----------------------------------------------------------------

    private void select(Entry e) {
        if (e == mSelected) return;
        mSelected = e;
        for (int i = 0; i < mCards.size(); i++) {
            final FrameLayout f = mCards.get(i);
            final GradientDrawable fb = (GradientDrawable) f.getBackground();
            if (mEntries.get(i) == e) {
                fb.setStroke(dp(2), getResources().getColor(R.color.kiro_accent, null));
            } else {
                fb.setStroke(Math.max(1, dp(0.5f)), 0x1FFFFFFF);
            }
        }
        // the large preview: its own binding (a view can be in one place only)
        if (mHeroId != -1) {
            mBound.remove(Integer.valueOf(mHeroId));
            mWidgets.host().deleteAppWidgetId(mHeroId);
            mHeroId = -1;
        }
        if (e.info == null) {
            mHeroSizes = mWidgets.sizes(HomeModel.CLOCK_ID, mCellW, mCellH);
        } else {
            mHeroId = bindPreview(e.info);
            mHeroSizes = mHeroId != -1 ? mWidgets.sizes(mHeroId, mCellW, mCellH)
                    : new ArrayList<int[]>();
            if (mHeroSizes.isEmpty()) {
                final int[] c = defaultCells(e);
                final int area = c[0] * c[1];
                mHeroSizes.add(new int[] {c[0], c[1], area <= 4 ? 0 : area <= 8 ? 1 : 2});
            }
        }
        // medium when there is one, else the first
        mSize = 0;
        for (int i = 0; i < mHeroSizes.size(); i++) {
            if (mHeroSizes.get(i)[2] == 1) mSize = i;
        }
        buildSizes();
        showHero(false);
    }

    private void buildSizes() {
        mSizes.removeAllViews();
        final int[] names = {R.string.widget_size_small, R.string.widget_size_medium,
                R.string.widget_size_large};
        for (int n = 0; n < 3; n++) {
            int index = -1;
            for (int i = 0; i < mHeroSizes.size(); i++) {
                if (mHeroSizes.get(i)[2] == n) index = i;
            }
            final TextView seg = new TextView(getContext());
            final boolean bar = mHeroSizes.size() == 1 && mHeroSizes.get(0)[0] == 1;
            seg.setText(bar && index != -1 ? getContext().getString(R.string.picker_bar)
                    : getContext().getString(names[n]));
            seg.setGravity(Gravity.CENTER);
            seg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            seg.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            final boolean on = index == mSize && index != -1;
            final GradientDrawable sb = new GradientDrawable();
            sb.setCornerRadius(17 * mDp);
            sb.setColor(on ? 0xFFFFFFFF : 0x00000000);
            seg.setBackground(sb);
            seg.setTextColor(on ? 0xFF111111 : index != -1 ? 0xFFFFFFFF : 0x42FFFFFF);
            final int pick = index;
            if (pick != -1) {
                seg.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (pick == mSize) return;
                        mSize = pick;
                        buildSizes();
                        showHero(true);
                    }
                });
            }
            mSizes.addView(seg, new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        }
    }

    /** the widget picked at the size chosen, scaled to fit above the sheet */
    private void showHero(boolean resized) {
        final int[] s = mHeroSizes.get(mSize);
        final int vw = s[1] * mCellW;
        final int vh = s[0] * mCellH;
        final int areaW = getResources().getDisplayMetrics().widthPixels - dp(48);
        final int areaH = dp(250) - dp(40);
        if (mHero != null) {
            mHero.removeAllViews();
            mHeroArea.removeView(mHero);
        }
        mHero = new ScaledFrame(getContext(), vw, vh, areaW, areaH);
        final View v = widgetView(mSelected, mHeroId);
        mHero.addView(v);
        if (mHeroId != -1 && v instanceof AppWidgetHostView) {
            // the widget's app draws for this size (Kiro widgets show more when larger)
            final int inset = dp(12);
            final int w = Math.round((vw - inset) / mDp);
            final int h = Math.round((vh - inset) / mDp);
            try {
                ((AppWidgetHostView) v).updateAppWidgetSize(null, w, h, w, h);
            } catch (RuntimeException e) {
                // ignore
            }
        }
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.bottomMargin = dp(18);
        mHeroArea.addView(mHero, lp);
        mHeroCaption.setText(mSelected.name + "  ·  " + s[1] + " × " + s[0]);
        // grows or shrinks from the size it had, on the spring; a new widget rises in
        mHero.setAlpha(0f);
        mHero.setScaleX(0.92f);
        mHero.setScaleY(0.92f);
        mHero.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(KiroSpring.FLUID.duration()).setInterpolator(KiroSpring.FLUID)
                .start();
    }

    // --- adding, closing -----------------------------------------------------------------

    private void addSelected() {
        if (mSelected == null || mClosing) return;
        final int[] s = mHeroSizes.get(mSize);
        if (mSelected.info == null) {
            finish();
            mHost.onWidgetPicked(HomeModel.CLOCK_ID, s[0], s[1]);
            return;
        }
        if (mSelected.info.configure != null || mHeroId == -1) {
            final AppWidgetProviderInfo info = mSelected.info;
            finish();
            mHost.onWidgetNeedsConfigure(info);
            return;
        }
        // the large preview's binding becomes the widget on the home
        final int id = mHeroId;
        mBound.remove(Integer.valueOf(id));
        mHeroId = -1;
        if (mHero != null) mHero.removeAllViews();
        finish();
        mHost.onWidgetPicked(id, s[0], s[1]);
    }

    void open() {
        mScrim.setAlpha(0f);
        mScrim.animate().alpha(1f).setDuration(220).start();
        mSheet.post(new Runnable() {
            @Override
            public void run() {
                mSheet.setTranslationY(mSheet.getHeight());
                mSheet.animate().translationY(0f).setDuration(KiroSpring.FLUID.duration())
                        .setInterpolator(KiroSpring.FLUID).withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                mSheet.invalidate(); // the glass lines up where it settled
                            }
                        }).start();
            }
        });
        mHeroArea.setAlpha(0f);
        mHeroArea.animate().alpha(1f).setStartDelay(120).setDuration(260).start();
    }

    void close() {
        if (mClosing) return;
        mClosing = true;
        mScrim.animate().alpha(0f).setDuration(200).start();
        mHeroArea.animate().alpha(0f).setDuration(150).start();
        mSheet.animate().translationY(mSheet.getHeight()).setDuration(KiroSpring.SMOOTH.duration())
                .setInterpolator(KiroSpring.SMOOTH).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }).start();
    }

    /** gone at once: the previews' bindings undone */
    private void finish() {
        mClosing = true;
        for (int id : mBound) {
            try {
                mWidgets.host().deleteAppWidgetId(id);
            } catch (RuntimeException e) {
                // ignore
            }
        }
        mBound.clear();
        if (mHeroId != -1) {
            mWidgets.host().deleteAppWidgetId(mHeroId);
            mHeroId = -1;
        }
        if (getParent() != null) ((ViewGroup) getParent()).removeView(this);
        mHost.onPickerClosed();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return true; // nothing reaches the home under the picker
    }

    /**
     * Lays its child out at a home size (virtualW x virtualH) and shows it scaled down to fit
     * maxW x maxH: the widget draws exactly as it will on the home, only smaller.
     */
    static final class ScaledFrame extends FrameLayout {
        private final int mVW;
        private final int mVH;
        private final float mScale;

        ScaledFrame(Context c, int virtualW, int virtualH, int maxW, int maxH) {
            super(c);
            mVW = Math.max(1, virtualW);
            mVH = Math.max(1, virtualH);
            mScale = Math.min(1f, Math.min(maxW / (float) mVW, maxH / (float) mVH));
            setClipChildren(false);
        }

        @Override
        public void addView(View child) {
            super.addView(child, new LayoutParams(mVW, mVH));
            child.setPivotX(0f);
            child.setPivotY(0f);
            child.setScaleX(mScale);
            child.setScaleY(mScale);
            if (!(child instanceof ImageView)) {
                final float d = getResources().getDisplayMetrics().density;
                final GradientDrawable g = new GradientDrawable();
                g.setColor(0xF01C1E24);
                g.setCornerRadius(22 * d);
                if (child.getBackground() == null) child.setBackground(g);
                child.setClipToOutline(true);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(mVW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(mVH, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(Math.round(mVW * mScale), Math.round(mVH * mScale));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).layout(0, 0, mVW, mVH);
            }
        }
    }
}
