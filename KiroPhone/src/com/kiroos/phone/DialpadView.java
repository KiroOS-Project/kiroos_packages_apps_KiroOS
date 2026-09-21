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

package com.kiroos.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kiroos.ui.SquircleDrawable;

/**
 * The 4 x 3 phone keypad (digits with their letters), built in code so the dialer and the
 * in-call screen share it. Reports key press and release (for DTMF tones) and long presses.
 */
public class DialpadView extends LinearLayout {

    public interface Listener {
        void onKeyPressed(char key);

        void onKeyReleased(char key);

        /** @return true if the long press was handled (the key is then not typed) */
        boolean onKeyLongPressed(char key);
    }

    private static final char[] KEYS = {
            '1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#',
    };
    private static final String[] LETTERS = {
            "∞", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", "",
    };

    private Listener mListener;

    public DialpadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        final float d = getResources().getDisplayMetrics().density;
        // shorter keys on short screens (e.g. 720x1280 @ 320 dpi) leave room for suggestions
        final int keyHeight = getResources().getDimensionPixelSize(R.dimen.dialpad_key_height);
        for (int r = 0; r < 4; r++) {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                final int i = r * 3 + c;
                row.addView(makeKey(context, KEYS[i], LETTERS[i]),
                        new LayoutParams(0, keyHeight, 1f));
            }
            final LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(6 * d);
            addView(row, lp);
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    private View makeKey(Context context, final char key, String letters) {
        final LinearLayout v = new LinearLayout(context);
        v.setOrientation(VERTICAL);
        v.setGravity(Gravity.CENTER);
        final float d = getResources().getDisplayMetrics().density;
        final int inset = Math.round(4 * d);
        final LayoutParams margin = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        v.setBackground(SquircleDrawable.button(getContext().getColor(R.color.key_fill),
                getContext().getColor(R.color.kiro_ripple)));
        v.setPadding(inset, 0, inset, 0);

        final TextView digit = new TextView(context);
        digit.setText(String.valueOf(key));
        digit.setTextColor(getContext().getColor(R.color.kiro_text));
        digit.setTextSize(TypedValue.COMPLEX_UNIT_SP, key == '*' || key == '#' ? 28 : 30);
        digit.setIncludeFontPadding(false);
        digit.setGravity(Gravity.CENTER);
        v.addView(digit, margin);

        final TextView sub = new TextView(context);
        sub.setText(letters);
        sub.setTextColor(getContext().getColor(R.color.kiro_text_secondary));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setLetterSpacing(0.12f);
        sub.setGravity(Gravity.CENTER);
        v.addView(sub, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (letters.isEmpty()) {
            sub.setVisibility(INVISIBLE);
        }

        v.setContentDescription(String.valueOf(key));
        v.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start();
                        if (mListener != null) {
                            mListener.onKeyPressed(key);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        if (mListener != null) {
                            mListener.onKeyReleased(key);
                        }
                        break;
                    default:
                        break;
                }
                return false; // keep the ripple and the long-click detection
            }
        });
        v.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });
        v.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                return mListener != null && mListener.onKeyLongPressed(key);
            }
        });
        final FrameWrap wrap = new FrameWrap(context, v, inset);
        return wrap;
    }

    /** Leaves a small gap around each key so the squircles don't touch. */
    private static final class FrameWrap extends android.widget.FrameLayout {
        FrameWrap(Context context, View child, int gap) {
            super(context);
            final LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT);
            lp.leftMargin = gap;
            lp.rightMargin = gap;
            addView(child, lp);
        }
    }
}
