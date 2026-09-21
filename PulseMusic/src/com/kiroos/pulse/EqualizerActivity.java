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

package com.kiroos.pulse;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** The 10-band equalizer: presets, a slider per band, pre-amp, on / off. Changes are live. */
public class EqualizerActivity extends Activity {

    private final BandSlider[] mSliders = new BandSlider[Equalizer10.BANDS];
    private final TextView[] mValues = new TextView[Equalizer10.BANDS];
    private BandSlider mPreamp;
    private TextView mPreampValue;
    private Equalizer10 mEq;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equalizer);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bind(Equalizer10.shared(getSharedPreferences("pulse", MODE_PRIVATE)));
    }

    private void bind(final Equalizer10 eq) {
        mEq = eq;
        final LinearLayout bands = (LinearLayout) findViewById(R.id.bands);
        bands.removeAllViews();
        for (int i = 0; i < Equalizer10.BANDS; i++) {
            final int band = i;
            final LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            final TextView value = new TextView(this);
            value.setTextColor(getColor(R.color.kiro_text_secondary));
            value.setTextSize(10);
            value.setGravity(Gravity.CENTER);
            col.addView(value);
            final BandSlider slider = new BandSlider(this, null);
            slider.setRange(Equalizer10.MAX_DB);
            slider.setValue(eq.gain(i));
            slider.setOnChange(new BandSlider.OnChange() {
                @Override
                public void onChange(BandSlider s, float db) {
                    mEq.setGain(band, db);
                    mValues[band].setText(format(db));
                }
            });
            col.addView(slider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            final TextView freq = new TextView(this);
            final int f = Equalizer10.FREQUENCIES[i];
            freq.setText(f >= 1000 ? (f / 1000) + "k" : String.valueOf(f));
            freq.setTextColor(getColor(R.color.kiro_text_secondary));
            freq.setTextSize(11);
            freq.setGravity(Gravity.CENTER);
            col.addView(freq);
            bands.addView(col, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            mSliders[i] = slider;
            mValues[i] = value;
            value.setText(format(eq.gain(i)));
        }

        mPreamp = (BandSlider) findViewById(R.id.preamp);
        mPreampValue = (TextView) findViewById(R.id.preamp_value);
        mPreamp.setRange(12f);
        mPreamp.setValue(eq.preamp());
        mPreampValue.setText(format(eq.preamp()));
        mPreamp.setOnChange(new BandSlider.OnChange() {
            @Override
            public void onChange(BandSlider s, float db) {
                mEq.setPreamp(Math.min(6f, db));
                mPreampValue.setText(format(mEq.preamp()));
            }
        });

        final Switch on = (Switch) findViewById(R.id.eq_switch);
        on.setChecked(eq.isEnabled());
        setEnabled(eq.isEnabled());
        on.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                mEq.setEnabled(checked);
                setEnabled(checked);
            }
        });

        final LinearLayout presets = (LinearLayout) findViewById(R.id.presets);
        presets.removeAllViews();
        for (final Object[] p : Equalizer10.PRESETS) {
            final TextView chip = new TextView(this);
            chip.setText((Integer) p[0]);
            chip.setTextColor(getColor(R.color.kiro_text));
            chip.setTextSize(14);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.kiro_chip);
            final int pad = Math.round(16 * getResources().getDisplayMetrics().density);
            chip.setPadding(pad, 0, pad, 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final float[] gains = (float[]) p[1];
                    mEq.setGains(gains);
                    mEq.setEnabled(true);
                    on.setChecked(true);
                    for (int i = 0; i < Equalizer10.BANDS; i++) {
                        mSliders[i].setValue(gains[i]);
                        mValues[i].setText(format(gains[i]));
                    }
                }
            });
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    Math.round(36 * getResources().getDisplayMetrics().density));
            lp.setMarginEnd(Math.round(8 * getResources().getDisplayMetrics().density));
            presets.addView(chip, lp);
        }
    }

    private void setEnabled(boolean on) {
        for (BandSlider s : mSliders) {
            if (s != null) {
                s.setEnabled(on);
                s.setAlpha(on ? 1f : 0.4f);
            }
        }
        if (mPreamp != null) {
            mPreamp.setEnabled(on);
            mPreamp.setAlpha(on ? 1f : 0.4f);
        }
    }

    private static String format(float db) {
        return (db > 0 ? "+" : "") + (db == Math.round(db) ? String.valueOf(Math.round(db))
                : String.valueOf(db));
    }

    @Override
    protected void onStop() {
        super.onStop();
        mEq.save(getSharedPreferences("pulse", MODE_PRIVATE));
    }
}
