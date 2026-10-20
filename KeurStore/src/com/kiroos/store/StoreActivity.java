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

package com.kiroos.store;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KeurStore 3. A bar at the bottom: Discover (editorial cards for the featured apps, the
 * essentials, games, the KiroOS apps), Updates (what has a newer build at its source, KiroOS
 * apps included, with sizes and what is new; one tap or all at once) and My apps. Every app
 * opens its own page (version, size, source, what is new, the signature note). Apps install
 * and update right here from their official source; Android checks their signature.
 */
public class StoreActivity extends Activity {

    private static final int TAB_DISCOVER = 0;
    private static final int TAB_UPDATES = 1;
    private static final int TAB_MINE = 2;

    /** where an action button sits: its look follows */
    private static final int ON_LIST = 0;
    private static final int ON_ART = 1;
    private static final int ON_PAGE = 2;

    private final Handler mHandler = new Handler();
    private Map<String, List<Catalog.App>> mCatalog;
    private Icons mIcons;
    private PackageManager mPm;
    private SharedPreferences mPrefs;
    private LinearLayout mContent;
    private ScrollView mScroll;
    private FrameLayout mPage;
    private final View[] mTabs = new View[3];
    private TextView mBadge;
    private int mTab = TAB_DISCOVER;
    private boolean mSearching;
    private String mQuery = "";
    private boolean mItalian;

    /** the latest build at the source, per app (known after a check) */
    private final Map<String, Sources.Release> mReleases = new HashMap<>();
    private final Set<String> mUpdates = new HashSet<>();
    private boolean mChecking;
    private long mLastCheck;
    /** the action buttons on screen, per app, updated in place during an install */
    private final Map<String, List<TextView>> mActions = new HashMap<>();
    private final Map<TextView, Integer> mActionLook = new HashMap<>();
    /** the open app page */
    private Catalog.App mPageApp;
    private TextView mPageAction;

    private int mBg;
    private int mText;
    private int mText2;
    private int mText3;
    private int mAccent;
    private int mOnAccent;
    private int mSurface;
    private int mRaised;
    private int mRipple;
    private int mDivider;
    private boolean mDark;

    /** the installs (kept by Installs for the whole app, so a rotation loses none) */
    private final Installs.Listener mInstalls = new Installs.Listener() {
        @Override
        public void onChanged(String pkg, int result) {
            if (result < 0) {
                final Catalog.App a = app(pkg);
                if (a != null) {
                    refreshActions(pkg, a);
                }
                return;
            }
            if (result == Installer.OK) {
                final boolean wasUpdate = mUpdates.remove(pkg);
                toast(wasUpdate ? R.string.updated : R.string.installed);
            } else {
                toast(result == Installer.INCOMPATIBLE ? R.string.incompatible
                        : result == Installer.NO_NETWORK ? R.string.no_network
                        : R.string.install_failed);
            }
            updateBar();
            render(false);
            if (mPageApp != null && mPageApp.pkg.equals(pkg)) {
                showPage(mPageApp, false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_store);
        mPm = getPackageManager();
        mPrefs = getSharedPreferences("store_ui", MODE_PRIVATE);
        mLastCheck = mPrefs.getLong("last_check", 0);
        mIcons = new Icons(this);
        mItalian = "it".equals(Locale.getDefault().getLanguage());
        mCatalog = Catalog.load(this, mItalian);
        mBg = getColor(R.color.kiro_black);
        mText = getColor(R.color.kiro_text);
        mText2 = getColor(R.color.kiro_text_secondary);
        mText3 = getColor(R.color.kiro_text_tertiary);
        mAccent = getColor(R.color.kiro_accent);
        mOnAccent = getColor(R.color.kiro_on_accent);
        mSurface = getColor(R.color.kiro_surface);
        mRaised = getColor(R.color.kiro_surface_raised);
        mRipple = getColor(R.color.kiro_ripple);
        mDivider = getColor(R.color.kiro_divider);
        mDark = Color.luminance(mBg) < 0.5f;
        mContent = (LinearLayout) findViewById(R.id.content);
        mScroll = (ScrollView) findViewById(R.id.scroll);
        mPage = (FrameLayout) findViewById(R.id.page);
        buildBar();

        Installs.listen(mInstalls);
        UpdateJob.schedule(this);
        updateBar();
        render(true);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Catalog.refresh(StoreActivity.this)) {
                    final Map<String, List<Catalog.App>> fresh = Catalog.load(StoreActivity.this,
                            mItalian);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCatalog = fresh;
                            render(false);
                        }
                    });
                }
            }
        }, "KeurStore-catalogue").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(false);        // installed / not installed may have changed
        if (System.currentTimeMillis() - mLastCheck > 60 * 1000) {
            checkUpdates();
        }
    }

    @Override
    protected void onDestroy() {
        Installs.unlisten(mInstalls);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        StoreWidget.checkSoon(this);   // apps updated here leave the widget's list
    }

    @Override
    public void onBackPressed() {
        if (mPageApp != null) {
            closePage();
        } else if (mSearching) {
            closeSearch();
        } else if (mTab != TAB_DISCOVER) {
            pick(TAB_DISCOVER);
        } else {
            super.onBackPressed();
        }
    }

    private Catalog.App app(String pkg) {
        for (Catalog.App a : Catalog.all(mCatalog)) {
            if (a.pkg.equals(pkg)) {
                return a;
            }
        }
        return null;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    private Drawable pressable(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(mRipple), content,
                content != null ? null : rounded(Color.WHITE, 18));
    }

    private TextView text(CharSequence s, float sp, int color, boolean medium) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (medium) {
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return t;
    }

    private TextView line(CharSequence s, float sp, int color, boolean medium) {
        final TextView t = text(s, sp, color, medium);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** the app's own colour (from the catalogue), else one of a few calm ones */
    private static int color(Catalog.App a) {
        if (a.color != 0) {
            return a.color;
        }
        final int[] colors = {0xFF3987E5, 0xFF7B5CFF, 0xFFFF8A3D, 0xFF34C759, 0xFFFF5B50,
                0xFF2FC7B5, 0xFFD45CF0};
        return colors[Math.abs(a.pkg.hashCode()) % colors.length];
    }

    // --- the bar ------------------------------------------------------------------------------

    private void buildBar() {
        final LinearLayout bar = (LinearLayout) findViewById(R.id.bar);
        bar.setBackground(new BarBackground(mDark ? 0xFF101116 : 0xFFF7F7FA, mDivider));
        final int[] icons = {R.drawable.store_ic_discover, R.drawable.store_ic_updates,
                R.drawable.store_ic_mine};
        final int[] names = {R.string.tab_discover, R.string.tab_updates, R.string.ks_tab_mine};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            final LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            final FrameLayout iconBox = new FrameLayout(this);
            final ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            iconBox.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
            if (i == TAB_UPDATES) {
                mBadge = text("", 10, Color.WHITE, true);
                mBadge.setGravity(Gravity.CENTER);
                mBadge.setTypeface(Typeface.DEFAULT_BOLD);
                mBadge.setBackground(rounded(getColor(R.color.kiro_negative), 8));
                mBadge.setPadding(dp(4), 0, dp(4), 0);
                mBadge.setMinWidth(dp(16));
                // over the icon's top right corner, inside the box (not clipped)
                final FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(16),
                        Gravity.CENTER_HORIZONTAL | Gravity.TOP);
                bl.leftMargin = dp(22);
                iconBox.addView(mBadge, bl);
            }
            t.addView(iconBox, new LinearLayout.LayoutParams(dp(64), dp(30)));
            final TextView label = line(getString(names[i]), 11, mText, true);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(2), dp(2), dp(2), 0);
            t.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick(tab);
                }
            });
            t.setBackground(pressable(null));
            bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    1f));
            mTabs[i] = t;
        }
    }

    private void pick(int tab) {
        if (mSearching) {
            closeSearch();
        }
        if (mTab != tab) {
            mTab = tab;
            updateBar();
            render(true);
            mScroll.scrollTo(0, 0);
        } else {
            mScroll.smoothScrollTo(0, 0);
        }
    }

    private void updateBar() {
        for (int i = 0; i < 3; i++) {
            final int c = i == mTab ? mText : mText3;
            final LinearLayout t = (LinearLayout) mTabs[i];
            final FrameLayout box = (FrameLayout) t.getChildAt(0);
            ((ImageView) box.getChildAt(0)).setColorFilter(c);
            ((TextView) t.getChildAt(1)).setTextColor(c);
        }
        final int n = mUpdates.size();
        mBadge.setText(String.valueOf(n));
        mBadge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    // --- updates ---------------------------------------------------------------------------

    private void checkUpdates() {
        if (mChecking) {
            return;
        }
        mChecking = true;
        final List<Catalog.App> apps = Catalog.all(mCatalog);
        if (mTab == TAB_UPDATES) {
            render(false);
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Set<String> found = new HashSet<>();
                final Map<String, Sources.Release> releases = new HashMap<>();
                for (Catalog.App app : apps) {
                    if (!app.updatable()) {
                        continue;
                    }
                    final PackageInfo have = app.installed(mPm);
                    if (have == null) {
                        continue;
                    }
                    try {
                        final Sources.Release r = Sources.latest(app);
                        releases.put(app.pkg, r);
                        if (Sources.hasUpdate(StoreActivity.this, app, have, r)) {
                            found.add(app.pkg);
                        }
                    } catch (Exception e) {
                        // offline, the source is down, or no store build of a KiroOS app
                    }
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mChecking = false;
                        mLastCheck = System.currentTimeMillis();
                        mPrefs.edit().putLong("last_check", mLastCheck).apply();
                        mReleases.putAll(releases);
                        mUpdates.clear();
                        mUpdates.addAll(found);
                        updateBar();
                        render(false);
                    }
                });
            }
        }, "KeurStore-check").start();
    }

    // --- the pages ---------------------------------------------------------------------------

    private void render(boolean animate) {
        mActions.clear();
        mActionLook.clear();
        if (mPageAction != null) {
            bindAction(mPageAction, mPageApp, ON_PAGE);
        }
        mContent.removeAllViews();
        if (mSearching) {
            renderSearch();
        } else if (mTab == TAB_DISCOVER) {
            renderDiscover();
        } else if (mTab == TAB_UPDATES) {
            renderUpdates();
        } else {
            renderMine();
        }
        if (animate) {
            for (int i = 0; i < mContent.getChildCount() && i < 10; i++) {
                final View v = mContent.getChildAt(i);
                v.setAlpha(0f);
                v.setTranslationY(dp(14));
                v.animate().alpha(1f).translationY(0f).setStartDelay(25L * i).setDuration(280)
                        .setInterpolator(KiroTouch.EASE).start();
            }
        }
    }

    /** the big title of a page, with its caption above and the search button */
    private View header(String caption, String title, boolean search) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(20), dp(26), dp(20), 0);
        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView cap = line(caption.toUpperCase(Locale.getDefault()), 11, mText3, true);
        cap.setLetterSpacing(0.1f);
        texts.addView(cap);
        final TextView t = line(title, 30, mText, false);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(-0.015f);
        t.setPadding(0, dp(2), 0, 0);
        texts.addView(t);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        if (search) {
            final ImageView s = new ImageView(this);
            s.setImageResource(R.drawable.store_ic_search);
            s.setColorFilter(mText);
            s.setScaleType(ImageView.ScaleType.CENTER);
            s.setBackground(pressable(rounded(mRaised, 19)));
            s.setContentDescription(getString(R.string.search_hint));
            s.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSearch();
                }
            });
            KiroTouch.apply(s);
            final LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(38), dp(38));
            sl.bottomMargin = dp(4);
            row.addView(s, sl);
        }
        return row;
    }

    private View sectionTitle(String title, String sub) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(26), dp(20), dp(6));
        final TextView t = line(title, 19, mText, true);
        box.addView(t);
        if (sub != null) {
            final TextView s = text(sub, 12.5f, mText2, false);
            s.setMaxLines(2);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setPadding(0, dp(2), 0, 0);
            box.addView(s);
        }
        return box;
    }

    private void renderDiscover() {
        final String date = DateFormat.format(DateFormat.getBestDateTimePattern(
                Locale.getDefault(), "EEEEdMMMM"), new Date()).toString();
        mContent.addView(header(date, getString(R.string.tab_discover), true));

        final List<Catalog.App> featured = mCatalog.get("featured");
        if (featured != null && !featured.isEmpty()) {
            final HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipToPadding(false);
            hs.setPadding(dp(20), dp(18), dp(8), dp(4));
            final LinearLayout cards = new LinearLayout(this);
            cards.setOrientation(LinearLayout.HORIZONTAL);
            for (Catalog.App a : featured) {
                final LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(286),
                        dp(178));
                cl.setMarginEnd(dp(12));
                cards.addView(card(a), cl);
            }
            hs.addView(cards);
            mContent.addView(hs);
        }

        final List<Catalog.App> essentials = mCatalog.get("utility");
        if (essentials != null && !essentials.isEmpty()) {
            mContent.addView(sectionTitle(getString(R.string.ks_essentials), null));
            mContent.addView(grid(essentials));
        }
        final List<Catalog.App> games = mCatalog.get("games");
        if (games != null && !games.isEmpty()) {
            mContent.addView(sectionTitle(getString(R.string.ks_games_title),
                    getString(R.string.ks_games_sub)));
            for (Catalog.App a : games) {
                mContent.addView(row(a, null, null));
            }
        }
        final List<Catalog.App> kiro = mCatalog.get("kiro");
        if (kiro != null && !kiro.isEmpty()) {
            mContent.addView(sectionTitle(getString(R.string.ks_kiro_title),
                    getString(R.string.ks_kiro_sub)));
            final HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipToPadding(false);
            hs.setPadding(dp(14), dp(10), dp(14), dp(8));
            final LinearLayout icons = new LinearLayout(this);
            icons.setOrientation(LinearLayout.HORIZONTAL);
            for (Catalog.App a : kiro) {
                icons.addView(tile(a, 58, 76));
            }
            hs.addView(icons);
            mContent.addView(hs);
        }
    }

    /** a featured app's card: its colour, two lines about it, the app and its button */
    private View card(final Catalog.App a) {
        final FrameLayout card = new FrameLayout(this);
        card.setBackground(new Art(color(a), dp(24), 0));
        card.setForeground(pressable(null));
        card.setClipToOutline(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(a, true);
            }
        });
        KiroTouch.apply(card);

        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), dp(16), dp(18), 0);
        final TextView eyebrow = line(a.eyebrow != null ? a.eyebrow
                : getString(R.string.section_featured).toUpperCase(Locale.getDefault()), 11,
                0xB3FFFFFF, true);
        eyebrow.setLetterSpacing(0.1f);
        top.addView(eyebrow);
        final TextView headline = text(a.headline != null ? a.headline : a.desc, 22, Color.WHITE,
                false);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setMaxLines(2);
        headline.setEllipsize(TextUtils.TruncateAt.END);
        headline.setLineSpacing(0, 1.05f);
        headline.setPadding(0, dp(6), 0, 0);
        top.addView(headline);
        card.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        final LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(14), 0, dp(14), dp(14));
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcons.load(a, icon);
        bottom.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(line(a.name, 14, Color.WHITE, true));
        names.addView(line(a.desc, 12, 0xB3FFFFFF, false));
        final LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(dp(10));
        nl.setMarginEnd(dp(10));
        bottom.addView(names, nl);
        bottom.addView(actionButton(a, ON_ART), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        card.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return card;
    }

    /** apps as big icons, four to a row */
    private View grid(List<Catalog.App> apps) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), 0);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                box.addView(row);
            }
            row.addView(tile(apps.get(i), 60, 0), new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        for (int i = apps.size() % 4; i > 0 && i < 4; i++) {
            row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        return box;
    }

    /** an icon with its name under it; width 0 = as wide as the grid gives */
    private View tile(final Catalog.App a, int iconDp, int widthDp) {
        final LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcons.load(a, icon);
        t.addView(icon, new LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)));
        final TextView name = line(a.name, 11.5f, mText, false);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(7), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setBackground(pressable(null));
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(a, true);
            }
        });
        KiroTouch.apply(t);
        if (widthDp > 0) {
            t.setLayoutParams(new LinearLayout.LayoutParams(dp(widthDp),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return t;
    }

    private void renderUpdates() {
        final String caption = mChecking ? getString(R.string.ks_checking_short)
                : mLastCheck == 0 ? getString(R.string.ks_checked_never)
                : System.currentTimeMillis() - mLastCheck < 60 * 1000
                ? getString(R.string.ks_checked_now)
                : getString(R.string.ks_checked_fmt, DateUtils.getRelativeTimeSpanString(
                        mLastCheck, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        mContent.addView(header(caption, getString(R.string.tab_updates), false));

        final List<Catalog.App> ups = new ArrayList<>();
        for (Catalog.App a : Catalog.all(mCatalog)) {
            if (mUpdates.contains(a.pkg)) {
                ups.add(a);
            }
        }
        if (!ups.isEmpty()) {
            mContent.addView(summary(ups));
            for (Catalog.App a : ups) {
                final PackageInfo have = a.installed(mPm);
                final Sources.Release r = mReleases.get(a.pkg);
                final StringBuilder sub = new StringBuilder();
                if (have != null && have.versionName != null && r != null && r.versionName != null) {
                    sub.append(getString(R.string.ks_version_change, have.versionName,
                            r.versionName));
                } else if (r != null && r.versionName != null) {
                    sub.append(getString(R.string.version_fmt, r.versionName));
                }
                if (r != null && r.size > 0) {
                    if (sub.length() > 0) {
                        sub.append(" · ");
                    }
                    sub.append(Formatter.formatShortFileSize(this, r.size));
                }
                mContent.addView(row(a, sub.length() > 0 ? sub.toString() : null,
                        r != null ? r.notes : null));
            }
        } else if (mChecking) {
            mContent.addView(notice(0, getString(R.string.checking_updates), null, true));
        } else {
            mContent.addView(notice(R.drawable.store_ic_done, getString(R.string.up_to_date),
                    getString(R.string.ks_all_good_text), false));
        }

        final List<String[]> recent = Sources.recent(this);
        boolean titled = false;
        for (String[] e : recent) {
            final Catalog.App a = app(e[0]);
            if (a == null || mUpdates.contains(a.pkg)) {
                continue;
            }
            if (!titled) {
                mContent.addView(sectionTitle(getString(R.string.ks_recent_title), null));
                titled = true;
            }
            long when = 0;
            try {
                when = Long.parseLong(e[1]);
            } catch (RuntimeException ignored) {
                // an old entry
            }
            String sub = DateUtils.getRelativeTimeSpanString(when, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS).toString();
            if (e.length > 2 && !e[2].isEmpty()) {
                sub += " · " + e[2];
            }
            mContent.addView(small(a, sub));
        }
    }

    /** the box at the top of Updates: how many, how big, and Update all */
    private View summary(final List<Catalog.App> ups) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(14), dp(14), dp(14));
        box.setBackground(rounded(mSurface, 20));
        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(line(getResources().getQuantityString(R.plurals.ks_updates, ups.size(),
                ups.size()), 15, mText, true));
        long total = 0;
        for (Catalog.App a : ups) {
            final Sources.Release r = mReleases.get(a.pkg);
            if (r == null || r.size <= 0) {
                total = -1;
                break;
            }
            total += r.size;
        }
        if (total > 0) {
            final TextView size = line(Formatter.formatShortFileSize(this, total), 12.5f, mText2,
                    false);
            size.setPadding(0, dp(2), 0, 0);
            texts.addView(size);
        }
        box.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        final TextView all = line(getString(R.string.update_all), 13.5f, mOnAccent, true);
        all.setGravity(Gravity.CENTER);
        all.setPadding(dp(16), 0, dp(16), 0);
        all.setBackground(pressable(rounded(mAccent, 18)));
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Catalog.App a : ups) {
                    Installs.start(StoreActivity.this, a);
                }
            }
        });
        KiroTouch.apply(all);
        box.addView(all, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(16), dp(16), dp(8));
        box.setLayoutParams(lp);
        return box;
    }

    /** a box with a symbol (or a spinner), a title and a line under it */
    private View notice(int icon, String title, String sub, boolean spinner) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(20), dp(16), dp(20));
        box.setBackground(rounded(mSurface, 20));
        if (spinner) {
            final ProgressBar p = new ProgressBar(this);
            p.setIndeterminateTintList(ColorStateList.valueOf(mAccent));
            box.addView(p, new LinearLayout.LayoutParams(dp(32), dp(32)));
        } else if (icon != 0) {
            final ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            final int positive = getColor(R.color.kiro_positive);
            iv.setColorFilter(positive);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setPadding(dp(9), dp(9), dp(9), dp(9));
            iv.setBackground(rounded((positive & 0x00FFFFFF) | 0x29000000, 20));
            box.addView(iv, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(line(title, 15, mText, true));
        if (sub != null) {
            final TextView s = text(sub, 12.5f, mText2, false);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        final LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        box.addView(texts, tl);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(16), dp(16), dp(8));
        box.setLayoutParams(lp);
        return box;
    }

    private void renderMine() {
        final List<Catalog.App> mine = new ArrayList<>();
        for (Catalog.App a : Catalog.all(mCatalog)) {
            if (!a.system() && a.installed(mPm) != null) {
                mine.add(a);
            }
        }
        mContent.addView(header(getResources().getQuantityString(R.plurals.ks_apps, mine.size(),
                mine.size()), getString(R.string.ks_tab_mine), false));
        if (mine.isEmpty()) {
            mContent.addView(notice(R.drawable.store_ic_done, getString(R.string.nothing_installed),
                    null, false));
        }
        for (Catalog.App a : mine) {
            mContent.addView(row(a, metaLine(a), null));
        }
    }

    // --- search ----------------------------------------------------------------------------------

    private void openSearch() {
        mSearching = true;
        mQuery = "";
        render(false);
    }

    private void closeSearch() {
        mSearching = false;
        mQuery = "";
        final View focus = getCurrentFocus();
        if (focus != null) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(
                    focus.getWindowToken(), 0);
        }
        render(false);
    }

    private void renderSearch() {
        final LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(26), dp(16), dp(6));
        final EditText field = new EditText(this);
        field.setHint(R.string.search_hint);
        field.setSingleLine(true);
        field.setTextColor(mText);
        field.setHintTextColor(mText2);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setBackgroundResource(R.drawable.store_search_bg);
        field.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.store_ic_search, 0, 0, 0);
        field.setCompoundDrawablePadding(dp(10));
        field.setPadding(dp(14), 0, dp(14), 0);
        bar.addView(field, new LinearLayout.LayoutParams(0, dp(46), 1f));
        final TextView close = line(getString(android.R.string.cancel), 15, mAccent, true);
        close.setPadding(dp(14), dp(10), dp(4), dp(10));
        close.setContentDescription(getString(R.string.ks_search_close));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSearch();
            }
        });
        bar.addView(close);
        mContent.addView(bar);

        final LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        mContent.addView(results);
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.getDefault());
                fillResults(results);
            }
        });
        fillResults(results);
        field.requestFocus();
        field.post(new Runnable() {
            @Override
            public void run() {
                getSystemService(InputMethodManager.class).showSoftInput(field, 0);
            }
        });
    }

    private void fillResults(LinearLayout into) {
        into.removeAllViews();
        if (mQuery.isEmpty()) {
            return;
        }
        int n = 0;
        for (Catalog.App a : Catalog.all(mCatalog)) {
            if (a.name.toLowerCase(Locale.getDefault()).contains(mQuery)
                    || a.desc.toLowerCase(Locale.getDefault()).contains(mQuery)) {
                into.addView(row(a, null, null));
                n++;
            }
        }
        if (n == 0) {
            final TextView t = text(getString(R.string.no_results), 15, mText2, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(24), dp(48), dp(24), dp(24));
            into.addView(t);
        }
    }

    // --- rows ------------------------------------------------------------------------------------

    /** where the app comes from, and the installed version */
    private String metaLine(Catalog.App a) {
        final String source = a.system() ? getString(R.string.source_kiro)
                : a.fdroid() ? getString(R.string.source_fdroid)
                : getString(R.string.source_official, a.site != null ? a.site
                        : Uri.parse(a.source.startsWith("apk:") ? a.source.substring(4)
                                : "https://" + a.source).getHost());
        final PackageInfo have = a.installed(mPm);
        if (have != null && !have.packageName.equals(a.pkg)) {
            return getString(R.string.from_play);
        }
        if (have != null && have.versionName != null) {
            return source + " · " + have.versionName;
        }
        return source;
    }

    /** an app in a list: icon, name, a line, its button; notes (what is new) under it */
    private View row(final Catalog.App a, String sub, String notes) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);

        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        r.setBackground(pressable(null));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(a, true);
            }
        });
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcons.load(a, icon);
        r.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(line(a.name, 15, mText, true));
        final TextView s = line(sub != null ? sub : a.desc, 12.5f, mText2, false);
        s.setPadding(0, dp(2), 0, 0);
        texts.addView(s);
        final LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(13));
        tl.setMarginEnd(dp(10));
        r.addView(texts, tl);
        r.addView(actionButton(a, ON_LIST), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        box.addView(r);

        if (notes != null && !notes.isEmpty()) {
            final TextView n = text(notes, 12.5f, mText2, false);
            n.setLineSpacing(0, 1.15f);
            n.setMaxLines(3);
            n.setEllipsize(TextUtils.TruncateAt.END);
            n.setPadding(dp(69), 0, 0, dp(12));
            box.addView(n);
        }
        final View div = new View(this);
        div.setBackgroundColor(mDivider);
        final LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        dl.setMarginStart(dp(69));
        box.addView(div, dl);
        return box;
    }

    /** a smaller row with no button (recent updates) */
    private View small(final Catalog.App a, String sub) {
        final LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), dp(8), dp(20), dp(8));
        r.setBackground(pressable(null));
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(a, true);
            }
        });
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcons.load(a, icon);
        r.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(line(a.name, 14, mText, true));
        texts.addView(line(sub, 12.5f, mText2, false));
        final LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(13));
        r.addView(texts, tl);
        return r;
    }

    // --- actions ---------------------------------------------------------------------------------

    private TextView actionButton(Catalog.App a, int look) {
        final TextView b = new TextView(this);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, look == ON_PAGE ? 15 : 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.02f);
        b.setMinWidth(dp(look == ON_PAGE ? 0 : 76));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setSingleLine(true);
        bindAction(b, a, look);
        KiroTouch.apply(b);
        return b;
    }

    private void bindAction(final TextView b, final Catalog.App a, int look) {
        List<TextView> views = mActions.get(a.pkg);
        if (views == null) {
            views = new ArrayList<>();
            mActions.put(a.pkg, views);
        }
        if (!views.contains(b)) {
            views.add(b);
        }
        mActionLook.put(b, look);
        final float r = look == ON_PAGE ? 22 : 15;
        final Integer p = Installs.progress(a.pkg);
        final PackageInfo have = a.installed(mPm);
        final int quiet = look == ON_ART ? 0x38FFFFFF : (mAccent & 0x00FFFFFF) | 0x29000000;
        final int quietText = look == ON_ART ? Color.WHITE : mAccent;
        if (p != null) {
            b.setText(p >= 0 ? p + "%" : getString(R.string.installing));
            b.setTextColor(look == ON_ART ? Color.WHITE : mText);
            b.setBackground(new ProgressPill(quiet, (mAccent & 0x00FFFFFF) | 0x80000000,
                    p >= 0 ? p / 100f : 1f));
            b.setEnabled(false);
        } else if (have != null && mUpdates.contains(a.pkg)) {
            b.setText(R.string.update);
            b.setTextColor(mOnAccent);
            b.setBackground(pressable(rounded(mAccent, r)));
            b.setEnabled(true);
        } else if (have != null) {
            final boolean launchable = mPm.getLaunchIntentForPackage(have.packageName) != null;
            b.setText(R.string.open);
            b.setTextColor(look == ON_ART ? Color.WHITE : launchable ? mText : mText3);
            b.setBackground(pressable(rounded(look == ON_ART ? 0x38FFFFFF : mRaised, r)));
            b.setEnabled(launchable);
        } else {
            b.setText(R.string.install);
            b.setTextColor(look == ON_PAGE ? mOnAccent : quietText);
            b.setBackground(pressable(rounded(look == ON_PAGE ? mAccent : quiet, r)));
            b.setEnabled(true);
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(a);
            }
        });
    }

    private void refreshActions(String pkg, Catalog.App a) {
        final List<TextView> views = mActions.get(pkg);
        if (views != null) {
            for (TextView v : new ArrayList<>(views)) {
                final Integer look = mActionLook.get(v);
                bindAction(v, a, look != null ? look : ON_LIST);
            }
        }
    }

    private void act(Catalog.App a) {
        if (Installs.progress(a.pkg) != null) {
            return;
        }
        final PackageInfo have = a.installed(mPm);
        if (have != null && !mUpdates.contains(a.pkg)) {
            final Intent launch = mPm.getLaunchIntentForPackage(have.packageName);
            if (launch != null) {
                startActivity(launch);
            }
            return;
        }
        if (a.updatable()) {
            Installs.start(this, a);
        }
    }

    // --- an app's page ---------------------------------------------------------------------------

    private void showPage(final Catalog.App a, boolean animate) {
        if (mPageAction != null) {
            final List<TextView> views = mActions.get(mPageApp.pkg);
            if (views != null) {
                views.remove(mPageAction);
            }
        }
        mPageApp = a;
        mPage.removeAllViews();
        mPage.setBackgroundColor(mBg);
        final ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        mPage.addView(scroll);

        // the app's colour fading into the page, the back button over it
        final FrameLayout hero = new FrameLayout(this);
        hero.setBackground(new Art(color(a), 0, mBg));
        final ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.store_ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(pressable(rounded(0x59000000, 19)));
        back.setContentDescription(getString(R.string.ks_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePage();
            }
        });
        KiroTouch.apply(back);
        final FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(38), dp(38));
        bl.leftMargin = dp(14);
        bl.topMargin = dp(14);
        hero.addView(back, bl);
        col.addView(hero, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                dp(150)));

        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);
        top.setPadding(dp(20), 0, dp(20), 0);
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setElevation(dp(8));
        mIcons.load(a, icon);
        top.addView(icon, new LinearLayout.LayoutParams(dp(88), dp(88)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(0, 0, 0, dp(4));
        final TextView name = text(a.name, 22, mText, false);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        names.addView(line(metaLine(a), 12.5f, mText2, false));
        final LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(dp(16));
        top.addView(names, nl);
        final LinearLayout.LayoutParams topl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topl.topMargin = -dp(46);
        col.addView(top, topl);

        mPageAction = actionButton(a, ON_PAGE);
        final LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        al.setMargins(dp(20), dp(16), dp(20), 0);
        col.addView(mPageAction, al);

        final LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setBackground(new Rules(mDivider, Math.max(1, dp(0.5f))));
        stats.setPadding(0, dp(12), 0, dp(12));
        final LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMargins(dp(20), dp(18), dp(20), 0);
        col.addView(stats, sl);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), 0, dp(20), dp(28));
        col.addView(body);
        fillPage(a, stats, body);

        if (a.updatable() && !mReleases.containsKey(a.pkg)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Sources.Release rel = Sources.latest(a);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mReleases.put(a.pkg, rel);
                                if (a == mPageApp) {
                                    fillPage(a, stats, body);
                                }
                            }
                        });
                    } catch (Exception e) {
                        // offline: the page shows what is known
                    }
                }
            }, "KeurStore-version").start();
        }

        if (mPage.getVisibility() != View.VISIBLE) {
            mPage.setVisibility(View.VISIBLE);
            if (animate) {
                mPage.setTranslationX(getResources().getDisplayMetrics().widthPixels);
                mPage.animate().translationX(0f).setDuration(320).setInterpolator(KiroTouch.EASE)
                        .start();
            }
        }
    }

    /** the page's numbers and texts (again when the source answers) */
    private void fillPage(final Catalog.App a, LinearLayout stats, LinearLayout body) {
        final PackageInfo have = a.installed(mPm);
        final Sources.Release rel = mReleases.get(a.pkg);
        stats.removeAllViews();
        final String version = rel != null && rel.versionName != null ? rel.versionName
                : have != null && have.versionName != null ? have.versionName : "—";
        final String size = rel != null && rel.size > 0
                ? Formatter.formatShortFileSize(this, rel.size) : "—";
        final String source = a.system() || a.source.startsWith("kiroos:")
                ? getString(R.string.ks_short_kiro) : a.fdroid()
                ? getString(R.string.ks_short_fdroid) : getString(R.string.ks_short_official);
        final String[][] cells = {{getString(R.string.ks_stat_version), version},
                {getString(R.string.ks_stat_size), size}, {getString(R.string.ks_stat_source),
                source}};
        for (int i = 0; i < cells.length; i++) {
            final LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            final TextView k = line(cells[i][0], 10, mText3, true);
            k.setLetterSpacing(0.08f);
            c.addView(k);
            final TextView v = line(cells[i][1], 14, mText, true);
            v.setPadding(dp(4), dp(4), dp(4), 0);
            c.addView(v);
            if (i > 0) {
                c.setBackground(new Rules(mDivider, Math.max(1, dp(0.5f)), true));
            }
            stats.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f));
        }

        body.removeAllViews();
        if (rel != null && rel.notes != null && !rel.notes.isEmpty()) {
            body.addView(pageTitle(getString(R.string.ks_whats_new)));
            body.addView(pageText(rel.notes));
        }
        body.addView(pageTitle(getString(R.string.ks_about)));
        body.addView(pageText(a.desc));

        final LinearLayout safe = new LinearLayout(this);
        safe.setOrientation(LinearLayout.HORIZONTAL);
        safe.setPadding(dp(14), dp(14), dp(14), dp(14));
        safe.setBackground(rounded(mSurface, 18));
        final ImageView shield = new ImageView(this);
        shield.setImageResource(R.drawable.store_ic_shield);
        shield.setColorFilter(getColor(R.color.kiro_positive));
        safe.addView(shield, new LinearLayout.LayoutParams(dp(20), dp(20)));
        final TextView st = text(getString(a.system() || a.source.startsWith("kiroos:")
                ? R.string.ks_safe_kiro : R.string.ks_safe_text), 12.5f, mText2, false);
        st.setLineSpacing(0, 1.15f);
        final LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stl.setMarginStart(dp(12));
        safe.addView(st, stl);
        final LinearLayout.LayoutParams sfl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sfl.topMargin = dp(22);
        body.addView(safe, sfl);

        if (have != null && !a.system() && !a.source.startsWith("kiroos:")) {
            final TextView uninstall = text(getString(R.string.uninstall), 15,
                    getColor(R.color.kiro_negative), true);
            uninstall.setGravity(Gravity.CENTER);
            uninstall.setPadding(0, dp(14), 0, dp(14));
            uninstall.setBackground(pressable(null));
            uninstall.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Android asks for confirmation
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + have.packageName)));
                }
            });
            final LinearLayout.LayoutParams ul = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ul.topMargin = dp(10);
            body.addView(uninstall, ul);
        }
    }

    private TextView pageTitle(String s) {
        final TextView t = line(s, 16, mText, true);
        t.setPadding(0, dp(20), 0, 0);
        return t;
    }

    private TextView pageText(String s) {
        final TextView t = text(s, 13.5f, mText2, false);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private void closePage() {
        if (mPageApp == null) {
            return;
        }
        final List<TextView> views = mActions.get(mPageApp.pkg);
        if (views != null) {
            views.remove(mPageAction);
        }
        mPageApp = null;
        mPageAction = null;
        mPage.animate().translationX(getResources().getDisplayMetrics().widthPixels)
                .setDuration(260).setInterpolator(KiroTouch.EASE).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (mPageApp == null) {
                            mPage.setVisibility(View.GONE);
                            mPage.removeAllViews();
                            mPage.setTranslationX(0f);
                        }
                    }
                }).start();
        render(false);
    }

    // --- drawing ---------------------------------------------------------------------------------

    /**
     * An app's colour as a soft light from the top right: a card (rounded, darker at the
     * bottom for the white text) or the top of its page (fading into the page colour).
     */
    private static final class Art extends Drawable {
        private final int mColor;
        private final float mRadius;
        private final int mFadeTo;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mShade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();

        /** fadeTo = 0: a card; otherwise the page colour the art fades into */
        Art(int color, float radius, int fadeTo) {
            mColor = color;
            mRadius = radius;
            mFadeTo = fadeTo;
        }

        private static int mix(int a, int b, float t) {
            return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }

        @Override
        protected void onBoundsChange(android.graphics.Rect b) {
            mRect.set(b);
            final float w = b.width();
            final float h = b.height();
            final int light = mix(mColor, Color.WHITE, 0.18f);
            final int deep = mix(mColor, Color.BLACK, 0.72f);
            mPaint.setShader(new RadialGradient(b.left + w * 0.85f, b.top, Math.max(w, h) * 1.2f,
                    new int[] {light, mColor, deep}, new float[] {0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            if (mFadeTo == 0) {
                mShade.setShader(new LinearGradient(0, b.top + h * 0.35f, 0, b.bottom,
                        0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
            } else {
                mShade.setShader(new LinearGradient(0, b.top + h * 0.3f, 0, b.bottom,
                        mFadeTo & 0x00FFFFFF, mFadeTo, Shader.TileMode.CLAMP));
            }
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(mRect, mRadius, mRadius, mPaint);
            c.drawRoundRect(mRect, mRadius, mRadius, mShade);
        }

        @Override
        public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(getBounds(), mRadius);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** hairlines: above and below (the stats row), or on the left (a stats cell) */
    private static final class Rules extends Drawable {
        private final Paint mPaint = new Paint();
        private final int mWidth;
        private final boolean mLeft;

        Rules(int color, int width) {
            this(color, width, false);
        }

        Rules(int color, int width, boolean left) {
            mPaint.setColor(color);
            mWidth = width;
            mLeft = left;
        }

        @Override
        public void draw(Canvas c) {
            final android.graphics.Rect b = getBounds();
            if (mLeft) {
                c.drawRect(b.left, b.top, b.left + mWidth, b.bottom, mPaint);
            } else {
                c.drawRect(b.left, b.top, b.right, b.top + mWidth, mPaint);
                c.drawRect(b.left, b.bottom - mWidth, b.right, b.bottom, mPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** the bar: a nearly opaque surface with a hairline on top */
    private static final class BarBackground extends Drawable {
        private final Paint mFill = new Paint();
        private final Paint mLine = new Paint();

        BarBackground(int fill, int line) {
            mFill.setColor(fill);
            mLine.setColor(line);
        }

        @Override
        public void draw(Canvas c) {
            final android.graphics.Rect b = getBounds();
            c.drawRect(b, mFill);
            c.drawRect(b.left, b.top, b.right, b.top + 1, mLine);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** a pill filling up with the download */
    private static final class ProgressPill extends Drawable {
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float mLevel;
        private final RectF mRect = new RectF();

        ProgressPill(int track, int fill, float level) {
            mTrack.setColor(track);
            mFill.setColor(fill);
            mLevel = level;
        }

        @Override
        public void draw(Canvas c) {
            mRect.set(getBounds());
            final float r = mRect.height() / 2f;
            c.drawRoundRect(mRect, r, r, mTrack);
            final float w = mRect.height() + (mRect.width() - mRect.height()) * mLevel;
            mRect.right = mRect.left + w;
            c.drawRoundRect(mRect, r, r, mFill);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
