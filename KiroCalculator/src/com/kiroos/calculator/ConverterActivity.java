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

package com.kiroos.calculator;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

/**
 * Kiro Calculator 1.2: the unit converter. A category (length, area, volume, mass,
 * temperature, speed, time, data, energy), a value in one unit, its value in another, and
 * the value in every unit of the category below. Tap a unit to change it, a row of the list
 * to convert into that unit, the arrows to swap; long press a value to copy it. The last
 * category and units are remembered.
 */
public class ConverterActivity extends Activity {

    private SharedPreferences mPrefs;
    private LinearLayout mChips;
    private EditText mInput;
    private TextView mFromUnit;
    private TextView mToUnit;
    private TextView mOutput;
    private LinearLayout mAll;
    private int mCategory;
    private int mFrom;
    private int mTo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_converter);
        mPrefs = getSharedPreferences("calc", MODE_PRIVATE);
        mChips = (LinearLayout) findViewById(R.id.categories);
        mInput = (EditText) findViewById(R.id.input);
        mFromUnit = (TextView) findViewById(R.id.from_unit);
        mToUnit = (TextView) findViewById(R.id.to_unit);
        mOutput = (TextView) findViewById(R.id.output);
        mAll = (LinearLayout) findViewById(R.id.all_units);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mFromUnit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickUnit(true);
            }
        });
        mToUnit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickUnit(false);
            }
        });
        final View swap = findViewById(R.id.btn_swap);
        swap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                final String out = mOutput.getText().toString();
                final int f = mFrom;
                mFrom = mTo;
                mTo = f;
                v.animate().rotationBy(180).setDuration(260).setInterpolator(KiroTouch.EASE).start();
                if (!"—".equals(out) && !out.isEmpty()) {
                    mInput.setText(out.replace('−', '-'));
                    mInput.setSelection(mInput.length());
                }
                save();
                update();
            }
        });
        KiroTouch.apply(mFromUnit, mToUnit, swap);
        mOutput.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                copy(mOutput.getText() + " " + mToUnit.getText());
                return true;
            }
        });
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                update();
            }
        });

        mCategory = Math.max(0, Math.min(Units.CATEGORIES.length - 1, mPrefs.getInt("conv_cat", 0)));
        buildChips();
        selectCategory(mCategory, false);
        if (savedInstanceState == null) {
            mInput.setText(mPrefs.getString("conv_value", "1"));
            mInput.setSelection(mInput.length());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mPrefs.edit().putString("conv_value", mInput.getText().toString()).apply();
    }

    private void buildChips() {
        final float d = getResources().getDisplayMetrics().density;
        for (int i = 0; i < Units.CATEGORIES.length; i++) {
            final int index = i;
            final TextView chip = new TextView(this);
            chip.setText(Units.CATEGORIES[i].name);
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.kiro_chip);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setPadding(Math.round(16 * d), 0, Math.round(16 * d), 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectCategory(index, true);
                }
            });
            KiroTouch.apply(chip);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(40 * d));
            lp.setMarginEnd(Math.round(8 * d));
            mChips.addView(chip, lp);
        }
    }

    private void selectCategory(int index, boolean byUser) {
        final Units.Category c = Units.CATEGORIES[index];
        final boolean same = index == mCategory && !byUser;
        mCategory = index;
        // the units last used in this category, or its usual pair
        mFrom = clamp(mPrefs.getInt("conv_from_" + index, c.from), c);
        mTo = clamp(mPrefs.getInt("conv_to_" + index, c.to), c);
        for (int i = 0; i < mChips.getChildCount(); i++) {
            final TextView chip = (TextView) mChips.getChildAt(i);
            chip.setSelected(i == index);
            chip.setTextColor(getColor(i == index ? R.color.kiro_on_accent : R.color.kiro_text));
        }
        if (byUser) {
            final View chip = mChips.getChildAt(index);
            ((android.widget.HorizontalScrollView) mChips.getParent())
                    .smoothScrollTo(Math.max(0, chip.getLeft() - chip.getWidth()), 0);
        }
        save();
        update();
        if (!same) {
            KiroTouch.enter(mAll);
        }
    }

    private static int clamp(int unit, Units.Category c) {
        return unit < 0 || unit >= c.units.length ? 0 : unit;
    }

    private void save() {
        mPrefs.edit().putInt("conv_cat", mCategory)
                .putInt("conv_from_" + mCategory, mFrom)
                .putInt("conv_to_" + mCategory, mTo).apply();
    }

    private double value() {
        final String s = mInput.getText().toString().trim().replace(',', '.').replace('−', '-');
        if (s.isEmpty() || s.equals("-") || s.equals(".")) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void update() {
        final Units.Category c = Units.CATEGORIES[mCategory];
        mFromUnit.setText(c.units[mFrom].symbol);
        mToUnit.setText(c.units[mTo].symbol);
        final double v = value();
        mOutput.setText(Double.isNaN(v) ? "" : Units.format(Units.convert(c, mFrom, mTo, v)));
        mOutput.setTextSize(TypedValue.COMPLEX_UNIT_SP, mOutput.length() > 14 ? 26 : 36);

        mAll.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < c.units.length; i++) {
            if (i == mFrom) {
                continue;
            }
            final int unit = i;
            final View row = inflater.inflate(R.layout.row_unit, mAll, false);
            final String text = Double.isNaN(v) ? "" : Units.format(Units.convert(c, mFrom, i, v));
            ((TextView) row.findViewById(R.id.unit_value)).setText(text);
            ((TextView) row.findViewById(R.id.unit_symbol)).setText(c.units[i].symbol);
            row.setSelected(i == mTo);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mTo = unit;
                    save();
                    update();
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    copy(text + " " + c.units[unit].symbol);
                    return true;
                }
            });
            mAll.addView(row);
        }
    }

    private void pickUnit(final boolean from) {
        final Units.Category c = Units.CATEGORIES[mCategory];
        final String[] names = new String[c.units.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = c.units[i].symbol;
        }
        new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setSingleChoiceItems(names, from ? mFrom : mTo, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (from) {
                            mFrom = which;
                        } else {
                            mTo = which;
                        }
                        dialog.dismiss();
                        save();
                        update();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText(null, text.replace('−', '-').trim()));
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
    }
}
