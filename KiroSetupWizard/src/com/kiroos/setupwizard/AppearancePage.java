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

import android.app.ActivityManagerNative;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

/**
 * Appearance: the light theme (a new phone's default), the dark one, or automatic (dark from
 * sunset to sunrise), as Settings > Display > Theme; and the text size (Settings > Display >
 * Font size). The setup follows the theme, so a choice shows at once: the activity is
 * recreated on this page.
 */
class AppearancePage extends SetupPage {

    private static final String TAG = "KiroSetup";
    private static final float[] SCALES = {0.9f, 1.0f, 1.15f};
    private static final int[] SCALE_LABELS = {R.string.text_small, R.string.text_default,
            R.string.text_large};

    private final UiModeManager mUi;
    private ThemePreviewView mLight;
    private ThemePreviewView mDark;
    private ImageView mAutoCheck;
    private final TextView[] mScaleChips = new TextView[SCALES.length];

    AppearancePage(SetupActivity activity) {
        super(activity);
        mUi = (UiModeManager) activity.getSystemService(Context.UI_MODE_SERVICE);
    }

    @Override
    int iconRes() {
        return R.drawable.ic_palette;
    }

    @Override
    int titleRes() {
        return R.string.appearance_title;
    }

    @Override
    int summaryRes() {
        return R.string.appearance_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        final float dp = mActivity.getResources().getDisplayMetrics().density;
        final LinearLayout pair = new LinearLayout(mActivity);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        mLight = new ThemePreviewView(mActivity, true);
        mDark = new ThemePreviewView(mActivity, false);
        pair.addView(column(mLight, R.string.theme_light, UiModeManager.MODE_NIGHT_NO, dp),
                weight(0, Math.round(6 * dp)));
        pair.addView(column(mDark, R.string.theme_dark, UiModeManager.MODE_NIGHT_YES, dp),
                weight(Math.round(6 * dp), 0));
        content.addView(pair);

        final View auto = inflater.inflate(R.layout.row_choice, content, false);
        ((TextView) auto.findViewById(R.id.row_title)).setText(R.string.theme_auto);
        mAutoCheck = (ImageView) auto.findViewById(R.id.row_check);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(UiModeManager.MODE_NIGHT_AUTO);
            }
        });
        final LinearLayout.LayoutParams autoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(56 * dp));
        autoLp.topMargin = Math.round(16 * dp);
        content.addView(auto, autoLp);

        addHeading(content, R.string.text_size);
        final LinearLayout chips = new LinearLayout(mActivity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SCALES.length; i++) {
            final int index = i;
            final TextView chip = new TextView(mActivity);
            chip.setText(SCALE_LABELS[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.kiro_chip);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13 + 2.5f * i);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setFontScale(SCALES[index]);
                }
            });
            KiroTouch.apply(chip);
            mScaleChips[i] = chip;
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    Math.round(48 * dp), 1f);
            lp.setMarginStart(i == 0 ? 0 : Math.round(4 * dp));
            lp.setMarginEnd(i == SCALES.length - 1 ? 0 : Math.round(4 * dp));
            chips.addView(chip, lp);
        }
        content.addView(chips);
        refresh();
    }

    private static LinearLayout.LayoutParams weight(int start, int end) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        return lp;
    }

    /** a preview with its name below; the whole column chooses the theme */
    private View column(ThemePreviewView preview, int label, final int mode, float dp) {
        final LinearLayout col = new LinearLayout(mActivity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(200 * dp)));
        final TextView name = new TextView(mActivity);
        name.setText(label);
        name.setTextAppearance(R.style.Kiro_Body);
        name.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * dp);
        col.addView(name, lp);
        col.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose(mode);
            }
        });
        KiroTouch.apply(col);
        return col;
    }

    private void choose(int mode) {
        // a change of the night bit recreates the activity in the new theme, on this page
        mUi.setNightMode(mode);
        refresh();
    }

    private void refresh() {
        if (mLight == null) {
            return;
        }
        final int mode = mUi.getNightMode();
        mLight.setChosen(mode == UiModeManager.MODE_NIGHT_NO);
        mDark.setChosen(mode == UiModeManager.MODE_NIGHT_YES);
        mAutoCheck.setVisibility(mode == UiModeManager.MODE_NIGHT_AUTO ? View.VISIBLE : View.INVISIBLE);
        final float scale = mActivity.getResources().getConfiguration().fontScale;
        int closest = 1;
        for (int i = 0; i < SCALES.length; i++) {
            if (Math.abs(SCALES[i] - scale) < Math.abs(SCALES[closest] - scale)) {
                closest = i;
            }
        }
        for (int i = 0; i < SCALES.length; i++) {
            mScaleChips[i].setSelected(i == closest);
            mScaleChips[i].setTextColor(mActivity.getColor(i == closest
                    ? R.color.kiro_on_accent : R.color.kiro_text));
        }
    }

    @Override
    void onShown() {
        refresh();
    }

    /** as Settings > Display > Font size: the persistent configuration's font scale */
    private void setFontScale(float scale) {
        try {
            final Configuration c = ActivityManagerNative.getDefault().getConfiguration();
            if (Math.abs(c.fontScale - scale) < 0.01f) {
                return;
            }
            c.fontScale = scale;
            ActivityManagerNative.getDefault().updatePersistentConfiguration(c);
        } catch (RemoteException e) {
            Log.w(TAG, "font scale", e);
        }
    }
}
