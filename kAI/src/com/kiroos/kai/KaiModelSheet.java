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

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

/**
 * kAI's model picker: a sheet that rises from the bottom with one card per Keng 1.5 model,
 * what it is good at, how fast it is and how much it knows, and the one in use ticked.
 */
final class KaiModelSheet {

    interface Listener {
        void onPicked(LocalEngine.Model model);
    }

    private KaiModelSheet() {
    }

    private static int speed(LocalEngine.Model m) {
        switch (m.id) {
            case "lite": return 5;
            case "base": return 4;
            default: return 2;
        }
    }

    private static int smarts(LocalEngine.Model m) {
        switch (m.id) {
            case "lite": return 1;
            case "base": return 3;
            default: return 4;
        }
    }

    private static int desc(LocalEngine.Model m) {
        switch (m.id) {
            case "lite": return R.string.model_desc_lite;
            case "base": return R.string.model_desc_base;
            default: return R.string.model_desc_pro;
        }
    }

    /** the badge colours: one gradient per model */
    private static int[] colors(LocalEngine.Model m) {
        switch (m.id) {
            case "lite": return new int[] {0xFF22B8A6, 0xFF3FD08A};
            case "base": return new int[] {0xFF2F7BDA, 0xFF45B3F0};
            default: return new int[] {0xFFF08A3C, 0xFFE8526E};
        }
    }

    private static String tag(LocalEngine.Model m) {
        switch (m.id) {
            case "lite": return "FLASH";
            case "base": return "BASE";
            default: return "PRO";
        }
    }

    static void show(final Activity a, final Listener listener) {
        final float dp = a.getResources().getDisplayMetrics().density;
        final int text = a.getColor(R.color.kiro_text);
        final int text2 = a.getColor(R.color.kiro_text_secondary);
        final int text3 = a.getColor(R.color.kiro_text_tertiary);
        final int accent = a.getColor(R.color.kiro_accent);
        final int surface = a.getColor(R.color.kiro_surface);
        final int raised = a.getColor(R.color.kiro_surface_raised);
        final int ripple = a.getColor(R.color.kiro_ripple);

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(dp, 16), px(dp, 10), px(dp, 16), px(dp, 22));
        final GradientDrawable sheet = new GradientDrawable();
        final float r = 30 * dp;
        sheet.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setColor(raised);
        root.setBackground(sheet);

        final View handle = new View(a);
        final GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(2 * dp);
        hb.setColor(text3);
        handle.setBackground(hb);
        final LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(px(dp, 36), px(dp, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(handle, hl);

        final TextView title = new TextView(a);
        title.setText(R.string.model_sheet_title);
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(px(dp, 6), px(dp, 16), px(dp, 6), px(dp, 12));
        root.addView(title);

        final View[] cards = new View[LocalEngine.MODELS.length];
        for (int i = 0; i < LocalEngine.MODELS.length; i++) {
            final LocalEngine.Model m = LocalEngine.MODELS[i];
            final boolean inUse = m == LocalEngine.selected();
            final boolean ok = m.installed();

            final LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(px(dp, 14), px(dp, 14), px(dp, 14), px(dp, 14));
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(22 * dp);
            bg.setColor(surface);
            if (inUse) {
                bg.setStroke(px(dp, 2), accent);
            }
            card.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), bg, null));
            card.setAlpha(ok ? 1f : 0.45f);

            final TextView badge = new TextView(a);
            badge.setText(tag(m));
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            badge.setLetterSpacing(0.06f);
            badge.setGravity(Gravity.CENTER);
            final GradientDrawable bb = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR, colors(m));
            bb.setShape(GradientDrawable.OVAL);
            badge.setBackground(bb);
            card.addView(badge, new LinearLayout.LayoutParams(px(dp, 50), px(dp, 50)));

            final LinearLayout texts = new LinearLayout(a);
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView name = new TextView(a);
            name.setText(m.name);
            name.setTextColor(text);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            texts.addView(name);
            final TextView sub = new TextView(a);
            sub.setText(ok ? a.getString(desc(m))
                    : a.getString(desc(m)) + " · " + a.getString(R.string.model_missing));
            sub.setTextColor(text2);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sub.setMaxLines(2);
            texts.addView(sub);
            final TextView meters = new TextView(a);
            meters.setText(meters(a.getString(R.string.model_speed), speed(m),
                    a.getString(R.string.model_smarts), smarts(m), accent, text3, text2));
            meters.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            meters.setPadding(0, px(dp, 5), 0, 0);
            texts.addView(meters);
            final LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tl.setMarginStart(px(dp, 14));
            tl.setMarginEnd(px(dp, 8));
            card.addView(texts, tl);

            final ImageView check = new ImageView(a);
            check.setImageResource(R.drawable.kai_check);
            check.setColorFilter(accent);
            check.setVisibility(inUse ? View.VISIBLE : View.INVISIBLE);
            card.addView(check, new LinearLayout.LayoutParams(px(dp, 24), px(dp, 24)));
            if (inUse) {
                card.setContentDescription(m.name + ", " + a.getString(R.string.model_in_use));
            }

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!ok) {
                        return;
                    }
                    dismiss(d, root, new Runnable() {
                        @Override
                        public void run() {
                            listener.onPicked(m);
                        }
                    });
                }
            });
            KiroTouch.apply(card);
            final LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = px(dp, 10);
            root.addView(card, cl);
            cards[i] = card;
        }

        d.setContentView(root);
        final Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
        w.setDimAmount(0.45f);
        w.setWindowAnimations(0);
        d.show();

        // the sheet rises, its cards follow one after the other
        root.setTranslationY(420 * dp);
        root.animate().translationY(0f).setDuration(380).setInterpolator(KiroTouch.EASE).start();
        for (int i = 0; i < cards.length; i++) {
            final View c = cards[i];
            final float target = c.getAlpha();
            c.setAlpha(0f);
            c.setTranslationY(18 * dp);
            c.animate().alpha(target).translationY(0f).setStartDelay(90 + 45L * i)
                    .setDuration(320).setInterpolator(KiroTouch.EASE).start();
        }
    }

    private static void dismiss(final Dialog d, View root, final Runnable then) {
        root.animate().translationY(root.getHeight()).setDuration(220)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        d.dismiss();
                        then.run();
                    }
                }).start();
    }

    /** "Speed ●●●●○   Knowledge ●●●○○" */
    private static CharSequence meters(String speed, int s, String smarts, int k, int on, int off,
            int label) {
        final SpannableStringBuilder b = new SpannableStringBuilder();
        dots(b, speed, s, on, off, label);
        b.append("    ");
        dots(b, smarts, k, on, off, label);
        return b;
    }

    private static void dots(SpannableStringBuilder b, String label, int n, int on, int off,
            int labelColor) {
        int start = b.length();
        b.append(label).append(' ');
        b.setSpan(new ForegroundColorSpan(labelColor), start, b.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int i = 0; i < 5; i++) {
            start = b.length();
            b.append('●');
            b.setSpan(new ForegroundColorSpan(i < n ? on : off), start, b.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int px(float density, float v) {
        return Math.round(v * density);
    }
}
