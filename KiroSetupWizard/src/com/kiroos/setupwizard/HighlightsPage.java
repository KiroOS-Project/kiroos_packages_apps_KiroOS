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

package com.kiroos.setupwizard;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

import java.util.ArrayList;
import java.util.List;

/**
 * What's in KiroOS (the page before the last): the things that make KiroOS, each with its
 * app's own icon when it is an app, a symbol otherwise. Rows of apps missing from the ROM
 * are left out. The rows come in one after the other the first time the page shows.
 */
class HighlightsPage extends SetupPage {

    /** package (null: a system feature), symbol, title, text */
    private static final Object[][] FEATURES = {
            {KaiPage.KAI, R.drawable.ic_kai, R.string.feat_kai_t, R.string.feat_kai},
            {null, R.drawable.ic_edge, R.string.feat_edge_t, R.string.feat_edge},
            {null, R.drawable.ic_tune, R.string.feat_cc_t, R.string.feat_cc},
            {"com.kiroos.camera", R.drawable.ic_camera, R.string.feat_camera_t, R.string.feat_camera},
            {"com.kiroos.store", R.drawable.ic_store, R.string.feat_store_t, R.string.feat_store},
            {null, R.drawable.ic_game, R.string.feat_game_t, R.string.feat_game},
            {null, R.drawable.ic_battery, R.string.feat_guard_t, R.string.feat_guard},
            {null, R.drawable.ic_theme, R.string.feat_theme_t, R.string.feat_theme},
            {"com.kiroos.pulse", R.drawable.ic_music, R.string.feat_music_t, R.string.feat_music},
            {null, R.drawable.ic_update, R.string.feat_update_t, R.string.feat_update},
            {null, R.drawable.ic_shield, R.string.feat_privacy_t, R.string.feat_privacy},
    };

    private final List<View> mRows = new ArrayList<>();
    private boolean mAnimated;

    HighlightsPage(SetupActivity activity) {
        super(activity);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_star;
    }

    @Override
    int titleRes() {
        return R.string.highlights_title;
    }

    @Override
    int summaryRes() {
        return R.string.highlights_summary;
    }

    /**
     * Beta 4: KiroOS next to the LineageOS 14.1 it is built on, for the S6: feature, KiroOS,
     * LineageOS (0 = not there, else what LineageOS has instead).
     */
    private static final int[][] VERSUS = {
            {R.string.vs_ai, 0},
            {R.string.vs_apps, R.string.vs_apps_lineage},
            {R.string.vs_cc, R.string.vs_cc_lineage},
            {R.string.vs_eq, R.string.vs_eq_lineage},
            {R.string.vs_vulkan, 0},
            {R.string.vs_lock, 0},
            {R.string.vs_record, 0},
            {R.string.vs_battery, R.string.vs_battery_lineage},
    };

    private View versusCard(LayoutInflater inflater, ViewGroup content) {
        final android.content.Context c = mActivity;
        final float d = c.getResources().getDisplayMetrics().density;
        final android.widget.LinearLayout card = new android.widget.LinearLayout(c);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setBackgroundResource(com.kiroos.ui.R.drawable.kiro_card);
        final int pad = Math.round(16 * d);
        card.setPadding(pad, Math.round(12 * d), pad, Math.round(12 * d));
        final ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(16 * d);
        card.setLayoutParams(lp);
        card.addView(versusRow(c, d, c.getText(R.string.vs_heading), "KiroOS", "LineageOS", true));
        for (int[] r : VERSUS) {
            card.addView(versusRow(c, d, c.getText(r[0]), "✓",
                    r[1] == 0 ? "—" : c.getText(r[1]), false));
        }
        return card;
    }

    private static View versusRow(android.content.Context c, float d, CharSequence feature,
            CharSequence kiro, CharSequence lineage, boolean header) {
        final android.widget.LinearLayout row = new android.widget.LinearLayout(c);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round((header ? 32 : 38) * d));
        final int accent = c.getColor(com.kiroos.ui.R.color.kiro_accent);
        final int primary = c.getColor(com.kiroos.ui.R.color.kiro_text);
        final int secondary = c.getColor(com.kiroos.ui.R.color.kiro_text_secondary);
        final TextView f = new TextView(c);
        f.setText(feature);
        f.setTextSize(header ? 13 : 15);
        f.setTextColor(header ? secondary : primary);
        row.addView(f, new android.widget.LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView k = new TextView(c);
        k.setText(kiro);
        k.setGravity(android.view.Gravity.CENTER);
        k.setTextSize(header ? 13 : 17);
        k.setTextColor(header ? accent : accent);
        if (header) k.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        row.addView(k, new android.widget.LinearLayout.LayoutParams(Math.round(72 * d),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView l = new TextView(c);
        l.setText(lineage);
        l.setGravity(android.view.Gravity.CENTER);
        l.setTextSize(13);
        l.setTextColor(secondary);
        row.addView(l, new android.widget.LinearLayout.LayoutParams(Math.round(92 * d),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        final View versus = versusCard(inflater, content);
        content.addView(versus);
        mRows.add(versus);
        final PackageManager pm = mActivity.getPackageManager();
        for (Object[] f : FEATURES) {
            final String pkg = (String) f[0];
            Drawable appIcon = null;
            if (pkg != null) {
                try {
                    // the app's own icon, in the light or dark version of the current theme
                    appIcon = pm.getApplicationIcon(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    continue; // not in this ROM
                }
            }
            final View row = inflater.inflate(R.layout.row_feature, content, false);
            final ImageView icon = (ImageView) row.findViewById(R.id.row_icon);
            if (appIcon != null) {
                icon.setBackground(null);
                icon.setPadding(0, 0, 0, 0);
                icon.setImageTintList(null);
                icon.setImageDrawable(appIcon);
            } else {
                icon.setImageResource((Integer) f[1]);
            }
            ((TextView) row.findViewById(R.id.row_title)).setText((Integer) f[2]);
            ((TextView) row.findViewById(R.id.row_summary)).setText((Integer) f[3]);
            content.addView(row);
            mRows.add(row);
        }
    }

    @Override
    void onShown() {
        if (mAnimated) {
            return;
        }
        mAnimated = true;
        final float dy = 24 * mActivity.getResources().getDisplayMetrics().density;
        for (int i = 0; i < mRows.size(); i++) {
            final View row = mRows.get(i);
            row.setAlpha(0f);
            row.setTranslationY(dy);
            row.animate().alpha(1f).translationY(0f).setStartDelay(120 + 45L * i)
                    .setDuration(380).setInterpolator(KiroTouch.EASE).start();
        }
    }
}
