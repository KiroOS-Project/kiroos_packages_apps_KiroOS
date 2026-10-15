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

package com.kiroos.kai;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

/**
 * Typing on the home (KiroOS Beta 4): tapping "Ask kAI…" on the kAI bar widget turns the bar
 * into a text field right where it is, with the keyboard up, and the home stays behind it.
 * Send opens kAI with the question, which it asks at once. Back or a tap outside closes it.
 *
 * The widget launches this with the pill's bounds on the screen (the source bounds of the
 * click), so the field covers the pill exactly; when the keyboard would hide it (a bar low on
 * the home) it slides up above the keyboard.
 */
public class KaiQuickAskActivity extends Activity {

    private static final TimeInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);

    private View mRoot;
    private View mBar;
    private EditText mInput;
    private ImageButton mSend;
    private boolean mClosing;
    private final Rect mVisible = new Rect();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kai_quick_ask);
        mRoot = findViewById(R.id.quick_root);
        mBar = findViewById(R.id.quick_bar);
        mInput = (EditText) findViewById(R.id.quick_input);
        mSend = (ImageButton) findViewById(R.id.quick_send);

        // screen coordinates: the window starts at the top of the screen, under the status bar
        mRoot.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        placeOnWidget(getIntent().getSourceBounds());

        mRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        mBar.setClickable(true);   // a tap on the bar is not a tap outside

        mSend.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        mSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });
        KiroTouch.apply(mSend);
        updateSend();
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSend();
            }
        });
        mInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND || (event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    send();
                    return true;
                }
                return false;
            }
        });

        // keep the bar above the keyboard
        mRoot.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        liftAboveKeyboard();
                    }
                });

        // the dim comes in softly; the bar is already where the widget's pill was
        mRoot.setAlpha(0f);
        mRoot.animate().alpha(1f).setDuration(160).setInterpolator(EASE).start();
        mInput.requestFocus();
        mInput.post(new Runnable() {
            @Override
            public void run() {
                final InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) {
                    imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    /** puts the field on the pill of the widget (its bounds on the screen) */
    private void placeOnWidget(Rect src) {
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mBar.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (src != null && src.width() > 0 && src.height() > 0) {
            lp.width = src.width();
            lp.height = src.height();
            lp.leftMargin = src.left;   // screen coordinates: left, not start
            lp.topMargin = src.top;
        } else {
            // no bounds (an old launcher): a quarter of the way down the screen
            final int side = Math.round(16 * getResources().getDisplayMetrics().density);
            lp.leftMargin = side;
            lp.rightMargin = side;
            lp.topMargin = getResources().getDisplayMetrics().heightPixels / 4;
        }
        mBar.setLayoutParams(lp);
    }

    private void liftAboveKeyboard() {
        mRoot.getWindowVisibleDisplayFrame(mVisible);
        final float margin = 12 * getResources().getDisplayMetrics().density;
        final float over = mBar.getBottom() + margin - mVisible.bottom;
        final float target = over > 0 ? -over : 0f;
        if (mBar.getTranslationY() != target) {
            mBar.animate().translationY(target).setDuration(220).setInterpolator(EASE).start();
        }
    }

    private void updateSend() {
        final boolean any = mInput.getText().toString().trim().length() > 0;
        mSend.setEnabled(any);
        mSend.setAlpha(any ? 1f : 0.4f);
    }

    private void send() {
        final String text = mInput.getText().toString().trim();
        if (text.isEmpty() || mClosing) {
            return;
        }
        mClosing = true;
        hideKeyboard();
        startActivity(new Intent(this, KaiActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(KaiActivity.EXTRA_SEND, true));
        finish();
        overridePendingTransition(0, 0);
    }

    private void close() {
        if (mClosing) {
            return;
        }
        mClosing = true;
        hideKeyboard();
        mBar.animate().translationY(0f).setDuration(160).setInterpolator(EASE).start();
        mRoot.animate().alpha(0f).setDuration(160).setInterpolator(EASE)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                }).start();
    }

    private void hideKeyboard() {
        final InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mInput.getWindowToken(), 0);
        }
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Home or the recents key: it does not stay over the home
        if (!mClosing && !isChangingConfigurations()) {
            mClosing = true;
            finish();
            overridePendingTransition(0, 0);
        }
    }
}
