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
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KeurStore 2.0. Discover (a hero card and the sections), Updates (the apps with a newer build
 * at their source, one tap or all at once) and Installed; a search across the catalogue.
 * Every app shows its real picture, installs and updates right here with its progress, and
 * opens a detail sheet (source, version, uninstall). No Google Play: apps come from the
 * developer's own download or from F-Droid, and Android checks their signature.
 */
public class StoreActivity extends Activity {

    private static final String[] SECTIONS = {"featured", "games", "utility", "kiro"};
    private static final int[] SECTION_NAMES = {R.string.section_featured, R.string.section_games,
            R.string.section_utility, R.string.section_kiro};
    private static final int TAB_DISCOVER = 0;
    private static final int TAB_UPDATES = 1;
    private static final int TAB_INSTALLED = 2;

    private final Handler mHandler = new Handler();
    private Map<String, List<Catalog.App>> mCatalog;
    private Icons mIcons;
    private PackageManager mPm;
    private LinearLayout mContent;
    private ScrollView mScroll;
    private final TextView[] mTabs = new TextView[3];
    private int mTab = TAB_DISCOVER;
    private String mQuery = "";
    private boolean mItalian;

    /** download percent per app (0-100), -1 while Android installs it */
    private final Map<String, Integer> mProgress = new HashMap<>();
    /** the latest build at the source, per app (known after a check) */
    private final Map<String, Sources.Release> mReleases = new HashMap<>();
    private final Set<String> mUpdates = new HashSet<>();
    private boolean mChecking;
    private long mLastCheck;
    /** the action buttons on screen, per app, updated in place during an install */
    private final Map<String, List<TextView>> mActions = new HashMap<>();
    /** the detail sheet's button, kept up to date across list refreshes */
    private TextView mSheetAction;
    private Catalog.App mSheetApp;

    private int mText;
    private int mText2;
    private int mText3;
    private int mAccent;
    private int mOnAccent;
    private int mSurface;
    private int mRaised;
    private int mRipple;

    private final BroadcastReceiver mDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            final String pkg = i.getStringExtra("pkg");
            final int result = i.getIntExtra("result", Installer.FAILED);
            mProgress.remove(pkg);
            if (result == Installer.OK) {
                final boolean wasUpdate = mUpdates.remove(pkg);
                toast(wasUpdate ? R.string.updated : R.string.installed);
            } else {
                toast(result == Installer.INCOMPATIBLE ? R.string.incompatible
                        : result == Installer.NO_NETWORK ? R.string.no_network
                        : R.string.install_failed);
            }
            updateTabs();
            render(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_store);
        mPm = getPackageManager();
        mIcons = new Icons(this);
        mItalian = "it".equals(Locale.getDefault().getLanguage());
        mCatalog = Catalog.load(this, mItalian);
        mText = getColor(R.color.kiro_text);
        mText2 = getColor(R.color.kiro_text_secondary);
        mText3 = getColor(R.color.kiro_text_tertiary);
        mAccent = getColor(R.color.kiro_accent);
        mOnAccent = getColor(R.color.kiro_on_accent);
        mSurface = getColor(R.color.kiro_surface);
        mRaised = getColor(R.color.kiro_surface_raised);
        mRipple = getColor(R.color.kiro_ripple);
        mContent = (LinearLayout) findViewById(R.id.content);
        mScroll = (ScrollView) findViewById(R.id.scroll);

        final LinearLayout tabs = (LinearLayout) findViewById(R.id.tabs);
        final int[] names = {R.string.tab_discover, R.string.tab_updates, R.string.tab_installed};
        for (int i = 0; i < 3; i++) {
            final int tab = i;
            final TextView t = new TextView(this);
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mTab != tab) {
                        mTab = tab;
                        updateTabs();
                        render(true);
                        mScroll.scrollTo(0, 0);
                    }
                }
            });
            tabs.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    1f));
            mTabs[i] = t;
        }

        final EditText search = (EditText) findViewById(R.id.search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.getDefault());
                render(false);
            }
        });

        registerReceiver(mDone, new IntentFilter(Installer.ACTION_DONE));
        UpdateJob.schedule(this);
        updateTabs();
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
        unregisterReceiver(mDone);
        super.onDestroy();
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

    // --- updates ---------------------------------------------------------------------------

    private void checkUpdates() {
        if (mChecking) {
            return;
        }
        mChecking = true;
        mLastCheck = System.currentTimeMillis();
        updateTabs();
        final List<Catalog.App> apps = Catalog.all(mCatalog);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Set<String> found = new HashSet<>();
                final Map<String, Sources.Release> releases = new HashMap<>();
                for (Catalog.App app : apps) {
                    if (!app.direct()) {
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
                        // offline or the source is down: no update shown for it
                    }
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mChecking = false;
                        mReleases.putAll(releases);
                        mUpdates.clear();
                        mUpdates.addAll(found);
                        updateTabs();
                        render(false);
                    }
                });
            }
        }, "KeurStore-check").start();
    }

    private void updateTabs() {
        for (int i = 0; i < 3; i++) {
            final boolean on = i == mTab;
            final TextView t = mTabs[i];
            t.setBackground(on ? rounded(mAccent, 18) : null);
            t.setTextColor(on ? mOnAccent : mText2);
        }
        final int n = mUpdates.size();
        mTabs[TAB_UPDATES].setText(n > 0 ? getString(R.string.tab_updates) + " · " + n
                : getString(R.string.tab_updates));
    }

    // --- the list ----------------------------------------------------------------------------

    private void render(boolean animate) {
        mActions.clear();
        if (mSheetAction != null) {
            bindAction(mSheetAction, mSheetApp);
        }
        mContent.removeAllViews();
        if (!mQuery.isEmpty()) {
            final List<Catalog.App> hits = new ArrayList<>();
            for (Catalog.App a : Catalog.all(mCatalog)) {
                if (a.name.toLowerCase(Locale.getDefault()).contains(mQuery)
                        || a.desc.toLowerCase(Locale.getDefault()).contains(mQuery)) {
                    hits.add(a);
                }
            }
            if (hits.isEmpty()) {
                mContent.addView(empty(R.drawable.store_ic_search, R.string.no_results, false));
            }
            for (Catalog.App a : hits) {
                mContent.addView(row(a));
            }
        } else if (mTab == TAB_DISCOVER) {
            mContent.addView(hero());
            for (int i = 0; i < SECTIONS.length; i++) {
                final List<Catalog.App> apps = mCatalog.get(SECTIONS[i]);
                if (apps == null || apps.isEmpty()) {
                    continue;
                }
                mContent.addView(sectionTitle(getString(SECTION_NAMES[i])));
                for (Catalog.App a : apps) {
                    mContent.addView(row(a));
                }
            }
        } else if (mTab == TAB_UPDATES) {
            final List<Catalog.App> ups = new ArrayList<>();
            for (Catalog.App a : Catalog.all(mCatalog)) {
                if (mUpdates.contains(a.pkg)) {
                    ups.add(a);
                }
            }
            if (ups.isEmpty()) {
                mContent.addView(mChecking
                        ? empty(0, R.string.checking_updates, true)
                        : empty(R.drawable.store_ic_done, R.string.up_to_date, false));
            } else {
                mContent.addView(updateAllButton(ups));
                for (Catalog.App a : ups) {
                    mContent.addView(row(a));
                }
            }
        } else {
            final List<Catalog.App> mine = new ArrayList<>();
            for (Catalog.App a : Catalog.all(mCatalog)) {
                if (!a.system() && a.installed(mPm) != null) {
                    mine.add(a);
                }
            }
            if (mine.isEmpty()) {
                mContent.addView(empty(R.drawable.store_ic_done, R.string.nothing_installed,
                        false));
            }
            for (Catalog.App a : mine) {
                mContent.addView(row(a));
            }
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

    private View sectionTitle(String text) {
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(mText);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(4), dp(20), dp(4), dp(10));
        return t;
    }

    private View empty(int icon, int text, boolean spinner) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(56), dp(24), dp(24));
        if (spinner) {
            final ProgressBar p = new ProgressBar(this);
            p.setIndeterminateTintList(ColorStateList.valueOf(mAccent));
            box.addView(p, new LinearLayout.LayoutParams(dp(40), dp(40)));
        } else if (icon != 0) {
            final ImageView iv = new ImageView(this);
            iv.setImageResource(icon);
            iv.setColorFilter(mAccent);
            box.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
        }
        final TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(mText2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, 0);
        box.addView(t);
        return box;
    }

    /** the top of Discover: what KeurStore is, with the featured apps' pictures */
    private View hero() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {mAccent, 0xFF8E5CF5});
        bg.setCornerRadius(dp(26));
        card.setBackground(bg);
        final TextView title = new TextView(this);
        title.setText(R.string.hero_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        card.addView(title);
        final TextView text = new TextView(this);
        text.setText(R.string.hero_text);
        text.setTextColor(0xDDFFFFFF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setPadding(0, dp(6), 0, dp(14));
        card.addView(text);
        final LinearLayout icons = new LinearLayout(this);
        icons.setOrientation(LinearLayout.HORIZONTAL);
        final List<Catalog.App> featured = mCatalog.get("featured");
        if (featured != null) {
            for (int i = 0; i < featured.size() && i < 5; i++) {
                final Catalog.App a = featured.get(i);
                final ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                mIcons.load(a, iv);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        details(a);
                    }
                });
                KiroTouch.apply(iv);
                final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46),
                        dp(46));
                lp.setMarginEnd(dp(10));
                icons.addView(iv, lp);
            }
        }
        card.addView(icons);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        card.setLayoutParams(lp);
        return card;
    }

    private View updateAllButton(final List<Catalog.App> ups) {
        final TextView b = new TextView(this);
        b.setText(R.string.update_all);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(mOnAccent);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(mRipple), rounded(mAccent, 24),
                null));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                for (Catalog.App a : ups) {
                    if (!mProgress.containsKey(a.pkg)) {
                        install(a);
                    }
                }
            }
        });
        KiroTouch.apply(b);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

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
        if (have != null && have.versionName != null && !a.system()) {
            return source + " · " + have.versionName;
        }
        return source;
    }

    private View row(final Catalog.App a) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(mRipple), rounded(mSurface,
                22), null));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                details(a);
            }
        });

        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcons.load(a, icon);
        card.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextColor(mText);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        final TextView desc = new TextView(this);
        desc.setText(a.desc);
        desc.setTextColor(mText2);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setMaxLines(2);
        desc.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(desc);
        final TextView meta = new TextView(this);
        meta.setText(metaLine(a));
        meta.setTextColor(mText3);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, dp(3), 0, 0);
        texts.addView(meta);
        final LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(14));
        tl.setMarginEnd(dp(10));
        card.addView(texts, tl);

        final TextView action = new TextView(this);
        action.setGravity(Gravity.CENTER);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setMinWidth(dp(86));
        action.setPadding(dp(14), 0, dp(14), 0);
        action.setSingleLine(true);
        bindAction(action, a);
        KiroTouch.apply(action);
        card.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)));

        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    // --- actions -------------------------------------------------------------------------------

    private void bindAction(final TextView b, final Catalog.App a) {
        List<TextView> views = mActions.get(a.pkg);
        if (views == null) {
            views = new ArrayList<>();
            mActions.put(a.pkg, views);
        }
        if (!views.contains(b)) {
            views.add(b);
        }
        final Integer p = mProgress.get(a.pkg);
        final PackageInfo have = a.installed(mPm);
        if (p != null) {
            b.setText(p >= 0 ? p + "%" : getString(R.string.installing));
            b.setTextColor(mText);
            b.setBackground(new ProgressPill(mRaised, mAccent & 0x66FFFFFF,
                    p >= 0 ? p / 100f : 1f));
            b.setEnabled(false);
        } else if (have != null && mUpdates.contains(a.pkg)) {
            accent(b, R.string.update);
        } else if (have != null) {
            b.setText(R.string.open);
            b.setTextColor(mAccent);
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(mRipple),
                    rounded(mRaised, 18), null));
            b.setEnabled(true);
        } else if (a.system()) {
            b.setText(R.string.open);
            b.setTextColor(mText3);
            b.setBackground(rounded(mRaised, 18));
            b.setEnabled(false);
        } else {
            accent(b, R.string.install);
        }
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                act(a);
            }
        });
    }

    private void accent(TextView b, int text) {
        b.setText(text);
        b.setTextColor(mOnAccent);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(mRipple), rounded(mAccent, 18),
                null));
        b.setEnabled(true);
    }

    private void refreshActions(String pkg, Catalog.App a) {
        final List<TextView> views = mActions.get(pkg);
        if (views != null) {
            for (TextView v : new ArrayList<>(views)) {
                bindAction(v, a);
            }
        }
    }

    private void act(Catalog.App a) {
        if (mProgress.containsKey(a.pkg)) {
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
        if (a.direct()) {
            install(a);
        }
    }

    private void install(final Catalog.App a) {
        mProgress.put(a.pkg, 0);
        refreshActions(a.pkg, a);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Installer.install(StoreActivity.this, a, new Installer.Progress() {
                    @Override
                    public void onProgress(final int percent) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mProgress.containsKey(a.pkg)) {
                                    mProgress.put(a.pkg, percent);
                                    refreshActions(a.pkg, a);
                                }
                            }
                        });
                    }
                });
            }
        }, "KeurStore-install").start();
    }

    // --- detail sheet ----------------------------------------------------------------------------

    private void details(final Catalog.App a) {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(24));
        final GradientDrawable sheet = new GradientDrawable();
        final float r = dp(28);
        sheet.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setColor(mRaised);
        root.setBackground(sheet);

        final View handle = new View(this);
        handle.setBackground(rounded(mText3, 2));
        final LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(36), dp(4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = dp(16);
        root.addView(handle, hl);

        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mIcons.load(a, icon);
        top.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        final LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextColor(mText);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        names.addView(name);
        final TextView source = new TextView(this);
        source.setText(metaLine(a));
        source.setTextColor(mText2);
        source.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        names.addView(source);
        final LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.setMarginStart(dp(16));
        top.addView(names, nl);
        root.addView(top);

        final TextView desc = new TextView(this);
        desc.setText(a.desc);
        desc.setTextColor(mText);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        desc.setPadding(0, dp(16), 0, 0);
        root.addView(desc);

        final TextView version = new TextView(this);
        version.setTextColor(mText3);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        version.setPadding(0, dp(8), 0, 0);
        showVersion(version, a);
        root.addView(version);
        if (a.direct() && !mReleases.containsKey(a.pkg)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        final Sources.Release rel = Sources.latest(a);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mReleases.put(a.pkg, rel);
                                showVersion(version, a);
                            }
                        });
                    } catch (Exception e) {
                        // offline: no version line
                    }
                }
            }, "KeurStore-version").start();
        }

        final TextView action = new TextView(this);
        action.setGravity(Gravity.CENTER);
        action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bindAction(action, a);
        mSheetAction = action;
        mSheetApp = a;
        KiroTouch.apply(action);
        final LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        al.topMargin = dp(20);
        root.addView(action, al);

        final PackageInfo have = a.installed(mPm);
        if (have != null && !a.system()) {
            final TextView uninstall = new TextView(this);
            uninstall.setText(R.string.uninstall);
            uninstall.setTextColor(getColor(R.color.kiro_negative));
            uninstall.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            uninstall.setGravity(Gravity.CENTER);
            uninstall.setPadding(0, dp(14), 0, dp(4));
            uninstall.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    // Android asks for confirmation
                    startActivity(new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + have.packageName)));
                }
            });
            root.addView(uninstall);
        }

        d.setContentView(root);
        final Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        w.setDimAmount(0.45f);
        w.setWindowAnimations(0);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                final List<TextView> views = mActions.get(a.pkg);
                if (views != null) {
                    views.remove(action);
                }
                mSheetAction = null;
                mSheetApp = null;
                render(false);
            }
        });
        d.show();
        root.setTranslationY(dp(420));
        root.animate().translationY(0f).setDuration(360).setInterpolator(KiroTouch.EASE).start();
    }

    private void showVersion(TextView v, Catalog.App a) {
        final PackageInfo have = a.installed(mPm);
        final Sources.Release rel = mReleases.get(a.pkg);
        final StringBuilder s = new StringBuilder();
        if (have != null && have.versionName != null) {
            s.append(getString(R.string.installed_version_fmt, have.versionName));
        }
        if (rel != null && rel.versionName != null && (have == null
                || !rel.versionName.equals(have.versionName))) {
            if (s.length() > 0) {
                s.append("  ·  ");
            }
            s.append(getString(R.string.version_fmt, rel.versionName));
        }
        v.setText(s);
        v.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
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
