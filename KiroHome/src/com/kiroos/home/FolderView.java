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

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

import java.util.List;

/**
 * An open folder: a dimmed home with a floating card on it (the look of the power menu,
 * rounded and #181818), the folder's name on top (tap to rename) and its apps in a grid.
 * Tap an app to open it, long-press it to drag it out of the folder; tap outside or Back
 * to close.
 */
final class FolderView extends FrameLayout {

    interface Host {
        void onFolderAppClick(AppInfo app, View icon);

        void onFolderAppLongClick(AppInfo app, View cell);

        void onFolderRenamed(String name);

        void onFolderClosed();
    }

    private static final int COLUMNS = 4;

    private final LinearLayout mCard;
    private final EditText mName;
    private final Host mHost;
    private boolean mClosing;

    FolderView(Context context, IconFactory icons, String name, List<AppInfo> apps, Host host) {
        super(context);
        mHost = host;
        final float d = getResources().getDisplayMetrics().density;
        setBackgroundColor(0x99000000);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                close(true);
            }
        });

        mCard = new LinearLayout(context);
        mCard.setOrientation(LinearLayout.VERTICAL);
        mCard.setClickable(true); // taps on the card do not close it
        // Beta 5: frosted glass, the blurred wallpaper behind the card
        final float r = 24 * d;
        mCard.setBackground(new Glass.GlassDrawable(mCard, r, r, r, r, 0xFF181818));
        mCard.setClipToOutline(true);
        final int pad = Math.round(20 * d);
        mCard.setPadding(pad, pad, pad, Math.round(12 * d));
        mCard.setElevation(12 * d);

        mName = new EditText(context);
        mName.setText(name);
        mName.setTextColor(0xFFF4F4F6);
        mName.setHintTextColor(0x66FFFFFF);
        mName.setHint(R.string.folder_default);
        mName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        mName.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        mName.setBackground(null);
        mName.setGravity(Gravity.CENTER_HORIZONTAL);
        mName.setSingleLine(true);
        mName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mName.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mName.setShowSoftInputOnFocus(true);
        mName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mHost.onFolderRenamed(s.toString().trim());
            }
        });
        mName.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                hideKeyboard();
                mName.clearFocus();
                return true;
            }
        });
        mCard.addView(mName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final GridLayout grid = new GridLayout(context);
        grid.setColumnCount(COLUMNS);
        // the card is the screen minus 20 dp margins, minus its 20 dp padding
        final int cellWidth = (getResources().getDisplayMetrics().widthPixels
                - Math.round(80 * d)) / COLUMNS;
        final LayoutInflater inflater = LayoutInflater.from(context);
        for (final AppInfo app : apps) {
            final View cell = inflater.inflate(R.layout.item_app, grid, false);
            final ImageView icon = (ImageView) cell.findViewById(R.id.app_icon);
            icon.setImageDrawable(icons.icon(app));
            ((TextView) cell.findViewById(R.id.app_label)).setText(app.label);
            cell.setContentDescription(app.label);
            cell.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mHost.onFolderAppClick(app, icon);
                }
            });
            cell.setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    mHost.onFolderAppLongClick(app, v);
                    return true;
                }
            });
            KiroTouch.apply(cell);
            // four fixed columns, also when the folder has fewer apps
            final GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellWidth;
            lp.bottomMargin = Math.round(12 * d);
            grid.addView(cell, lp);
        }
        final LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = Math.round(16 * d);
        mCard.addView(grid, glp);

        final LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        clp.leftMargin = clp.rightMargin = Math.round(20 * d);
        addView(mCard, clp);
        // the name is edited only when tapped: the card holds the focus, no keyboard at once
        mCard.setFocusableInTouchMode(true);
        mCard.requestFocus();

        // the card opens with a soft zoom, the dim fades in
        setAlpha(0f);
        mCard.setScaleX(0.9f);
        mCard.setScaleY(0.9f);
        animate().alpha(1f).setDuration(180).setInterpolator(KiroTouch.EASE).start();
        mCard.animate().scaleX(1f).scaleY(1f).setDuration(KiroSpring.FLUID.duration()).setInterpolator(KiroSpring.FLUID)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // the glass lines up with the wallpaper again where the card settled
                        mCard.invalidate();
                    }
                }).start();
    }

    /** closes the folder (animated or at once, e.g. when an app is dragged out of it) */
    void close(boolean animate) {
        if (mClosing) {
            return;
        }
        mClosing = true;
        hideKeyboard();
        if (!animate) {
            finish();
            return;
        }
        mCard.animate().scaleX(0.92f).scaleY(0.92f).setDuration(160).start();
        animate().alpha(0f).setDuration(160).withEndAction(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }).start();
    }

    private void finish() {
        if (getParent() instanceof ViewGroup) {
            ((ViewGroup) getParent()).removeView(this);
        }
        mHost.onFolderClosed();
    }

    private void hideKeyboard() {
        final InputMethodManager imm = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }
}
