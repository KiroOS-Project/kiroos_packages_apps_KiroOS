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

package com.kiroos.sound;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Kiro Sound: the profile of the output that plays now (speaker, headphones or Bluetooth).
 * Every change is applied at once by the persistent SoundEngine.
 */
public class SoundActivity extends Activity implements SoundEngine.Listener {

    /** the bands' sliders run over +-12 dB, in millibels */
    private static final int BAND_RANGE = 1200;

    private SoundEngine mEngine;
    private TextView mOutput;
    private Switch mEnabled;
    private View mControls;
    private LinearLayout mPresets;
    private final SeekBar[] mBands = new SeekBar[Profiles.BANDS];
    private final TextView[] mBandValues = new TextView[Profiles.BANDS];
    private SeekBar mBass;
    private SeekBar mSurround;
    private View mSurroundNote;
    /** true while the views are filled from the profile (their listeners must not save) */
    private boolean mFilling;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound);
        mEngine = SoundEngine.get(this);
        mEngine.start();
        mOutput = (TextView) findViewById(R.id.output);
        mEnabled = (Switch) findViewById(R.id.enabled);
        mControls = findViewById(R.id.controls);
        mPresets = (LinearLayout) findViewById(R.id.presets);
        mBass = (SeekBar) findViewById(R.id.bass);
        mSurround = (SeekBar) findViewById(R.id.surround);
        mSurroundNote = findViewById(R.id.surround_note);

        mEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean on) {
                if (mFilling) return;
                mEngine.profiles.setEnabled(mEngine.output(), on);
                mEngine.apply();
            }
        });
        buildPresets();
        buildBands();
        mBass.setOnSeekBarChangeListener(new Slider() {
            @Override
            void onValue(int value) {
                mEngine.profiles.setBass(mEngine.output(), value);
            }
        });
        mSurround.setOnSeekBarChangeListener(new Slider() {
            @Override
            void onValue(int value) {
                mEngine.profiles.setSurround(mEngine.output(), value);
            }
        });
        findViewById(R.id.reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mEngine.profiles.reset(mEngine.output());
                mEngine.apply();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mEngine.addListener(this);
        fill();
    }

    @Override
    protected void onPause() {
        mEngine.removeListener(this);
        super.onPause();
    }

    @Override
    public void onSoundChanged() {
        fill();
    }

    /** a slider saves on every step and applies it, the music follows while dragging */
    private abstract class Slider implements SeekBar.OnSeekBarChangeListener {
        abstract void onValue(int value);

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (!fromUser || mFilling) return;
            onValue(progress);
            mEngine.apply();
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static final int[] PRESET_LABELS = {
        R.string.preset_flat, R.string.preset_bass, R.string.preset_warm, R.string.preset_rock,
        R.string.preset_pop, R.string.preset_vocal, R.string.preset_treble,
    };

    private void buildPresets() {
        for (int i = 0; i < Profiles.PRESETS.length; i++) {
            final String id = Profiles.PRESETS[i];
            final TextView chip = new TextView(this);
            chip.setText(PRESET_LABELS[i]);
            chip.setTag(id);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setBackgroundResource(com.kiroos.ui.R.drawable.kiro_chip);
            chip.setPadding(dp(16), 0, dp(16), 0);
            final LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            if (i > 0) lp.setMarginStart(dp(8));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mEngine.profiles.setPreset(mEngine.output(), id);
                    mEngine.apply();
                }
            });
            mPresets.addView(chip, lp);
        }
    }

    private void buildBands() {
        final LinearLayout bands = (LinearLayout) findViewById(R.id.bands);
        final int[] hz = mEngine.bandCenters();
        for (int b = 0; b < Profiles.BANDS; b++) {
            final int band = b;
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(52));
            row.setPadding(dp(16), 0, dp(12), 0);

            final TextView label = new TextView(this);
            label.setText(hz[b] >= 1000
                    ? getString(R.string.khz, trim(hz[b] / 1000f))
                    : getString(R.string.hz, hz[b]));
            label.setTextAppearance(com.kiroos.ui.R.style.Kiro_Body);
            row.addView(label, new LinearLayout.LayoutParams(dp(76),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            final SeekBar bar = new SeekBar(this);
            bar.setMax(BAND_RANGE * 2);
            bar.setOnSeekBarChangeListener(new Slider() {
                @Override
                void onValue(int value) {
                    mEngine.profiles.setLevel(mEngine.output(), band,
                            (short) (value - BAND_RANGE));
                    mBandValues[band].setText(db(value - BAND_RANGE));
                }
            });
            row.addView(bar, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView value = new TextView(this);
            value.setTextAppearance(com.kiroos.ui.R.style.Kiro_Caption);
            value.setGravity(Gravity.END);
            row.addView(value, new LinearLayout.LayoutParams(dp(56),
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            mBands[b] = bar;
            mBandValues[b] = value;
            bands.addView(row);
        }
    }

    private static String trim(float f) {
        return f == (int) f ? String.valueOf((int) f) : String.format("%.1f", f);
    }

    private String db(int millibels) {
        final float v = millibels / 100f;
        final String s = v == (int) v ? String.valueOf((int) v) : String.format("%.1f", v);
        return getString(R.string.db, v > 0 ? "+" + s : s);
    }

    private void fill() {
        mFilling = true;
        final int out = mEngine.output();
        final Profiles p = mEngine.profiles;
        final int name = out == Profiles.BLUETOOTH ? R.string.output_bluetooth
                : out == Profiles.HEADPHONES ? R.string.output_headphones
                : R.string.output_speaker;
        mOutput.setText(getString(R.string.output_now, getString(name)));
        final boolean on = p.enabled(out);
        mEnabled.setChecked(on);
        mControls.setAlpha(on ? 1f : 0.4f);
        setEnabledDeep(mControls, on);
        final String preset = p.preset(out);
        for (int i = 0; i < mPresets.getChildCount(); i++) {
            final TextView chip = (TextView) mPresets.getChildAt(i);
            final boolean sel = preset.equals(chip.getTag());
            chip.setSelected(sel);
            chip.setTextColor(getColor(sel ? com.kiroos.ui.R.color.kiro_on_accent
                    : com.kiroos.ui.R.color.kiro_text));
        }
        for (int b = 0; b < Profiles.BANDS; b++) {
            final int level = Math.max(-BAND_RANGE, Math.min(BAND_RANGE, p.level(out, b)));
            mBands[b].setProgress(level + BAND_RANGE);
            mBandValues[b].setText(db(level));
        }
        mBass.setProgress(p.bass(out));
        final boolean speaker = out == Profiles.SPEAKER;
        mSurround.setProgress(p.surround(out));
        mSurround.setEnabled(on && !speaker);
        mSurroundNote.setVisibility(speaker ? View.VISIBLE : View.GONE);
        mFilling = false;
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                setEnabledDeep(g.getChildAt(i), on);
            }
        }
    }
}
