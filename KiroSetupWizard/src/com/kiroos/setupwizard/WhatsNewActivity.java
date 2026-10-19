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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;

/**
 * KiroOS Beta 4.1: after an update, a short setup of three steps: Start, what changed since the
 * beta the phone had (new, improvements, optimizations, fixes), Next, Start. Shown once per
 * release, after the first unlock (the boot receiver starts it behind the lock screen); a
 * phone that was just set up has seen the setup and does not get it.
 */
public class WhatsNewActivity extends Activity {

    /** the release the phone last showed (or set up with) */
    static final String KEY_SEEN = "kiroos_seen_release";
    /** its channel name ("Developer Beta 4"), for "since ..." */
    static final String KEY_SEEN_CHANNEL = "kiroos_seen_channel";

    private FrameLayout mContainer;
    private LinearLayout mDots;
    private Button mBack;
    private Button mNext;
    private int mStep;
    private View mCurrent;
    private boolean mStarting;

    /** BOOT_COMPLETED: a new release on a phone already set up shows what is new */
    public static class BootReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!SetupActivity.isSetupComplete(context)) {
                return; // a new phone: the setup itself shows KiroOS
            }
            final String release = SystemProperties.get("ro.kiroos.release", "");
            final String seen = Settings.Global.getString(context.getContentResolver(), KEY_SEEN);
            if (release.isEmpty() || release.equals(seen)) {
                return;
            }
            context.startActivity(new Intent(context, WhatsNewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** the setup or this screen was seen for the release running now */
    static void markSeen(Context context) {
        Settings.Global.putString(context.getContentResolver(), KEY_SEEN,
                SystemProperties.get("ro.kiroos.release", ""));
        Settings.Global.putString(context.getContentResolver(), KEY_SEEN_CHANNEL,
                SystemProperties.get("ro.kiro.release.channel", ""));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        mContainer = (FrameLayout) findViewById(R.id.page_container);
        mDots = (LinearLayout) findViewById(R.id.progress_dots);
        mBack = (Button) findViewById(R.id.button_back);
        mNext = (Button) findViewById(R.id.button_next);
        KiroTouch.apply(mBack, mNext);
        final int size = Math.round(getResources().getDisplayMetrics().density * 6);
        final int gap = Math.round(getResources().getDisplayMetrics().density * 4);
        for (int i = 0; i < 3; i++) {
            final View dot = new View(this);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.setup_dot);
            mDots.addView(dot);
        }
        mBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(mStep - 1, false);
            }
        });
        mNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mStep == 2) {
                    playStart(v);
                } else {
                    show(mStep + 1, true);
                }
            }
        });
        show(savedInstanceState != null ? savedInstanceState.getInt("step", 0) : 0, true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("step", mStep);
    }

    @Override
    public void onBackPressed() {
        if (mStep > 0) {
            show(mStep - 1, false);
        }
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private void show(int step, boolean forward) {
        if (step < 0 || step > 2) return;
        final float dx = getResources().getDisplayMetrics().widthPixels * 0.25f;
        if (mCurrent != null) {
            final View old = mCurrent;
            old.animate().alpha(0f).translationX(forward ? -dx : dx).setDuration(220)
                    .setInterpolator(KiroTouch.EASE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mContainer.removeView(old);
                        }
                    }).start();
        }
        mStep = step;
        final View view = step == 0 ? hero() : step == 1 ? changes() : ready();
        mContainer.addView(view);
        view.setAlpha(0f);
        view.setTranslationX(forward ? dx : -dx);
        view.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(KiroTouch.EASE)
                .start();
        mCurrent = view;
        for (int i = 0; i < mDots.getChildCount(); i++) {
            final boolean current = i == step;
            mDots.getChildAt(i).animate().alpha(current ? 1f : 0.3f).scaleX(current ? 1.6f : 1f)
                    .setDuration(200).start();
        }
        mBack.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        mNext.setText(step == 1 ? R.string.next : R.string.whatsnew_start);
    }

    private String channel() {
        final String c = SystemProperties.get("ro.kiro.release.channel", "");
        return c.isEmpty() ? "KiroOS" : c;
    }

    /** step 1: the logo, KiroOS 1.0, "<channel> is ready" */
    private View hero() {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(32), 0, dp(32), dp(40));
        final ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_setup);
        v.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));
        final TextView title = text(getString(R.string.whatsnew_title), 34, true);
        title.setPadding(0, dp(24), 0, 0);
        v.addView(title);
        final TextView sub = text(getString(R.string.whatsnew_ready, channel()), 18, false);
        sub.setPadding(0, dp(10), 0, 0);
        v.addView(sub);
        logo.setScaleX(0.6f);
        logo.setScaleY(0.6f);
        logo.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(520)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        return v;
    }

    /** step 2: what changed since the beta the phone had, by kind */
    private View changes() {
        final ScrollView scroll = new ScrollView(this);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(24), dp(12), dp(24), dp(24));
        String since = Settings.Global.getString(getContentResolver(), KEY_SEEN_CHANNEL);
        if (since == null || since.isEmpty()) {
            since = getString(R.string.whatsnew_previous);
        }
        final TextView title = text(getString(R.string.whatsnew_since, since), 26, true);
        title.setGravity(Gravity.START);
        list.addView(title);
        section(list, R.string.whatsnew_section_new, R.array.whatsnew_new);
        section(list, R.string.whatsnew_section_improvements, R.array.whatsnew_improvements);
        section(list, R.string.whatsnew_section_optimizations, R.array.whatsnew_optimizations);
        section(list, R.string.whatsnew_section_fixes, R.array.whatsnew_fixes);
        scroll.addView(list);
        return scroll;
    }

    private void section(LinearLayout list, int heading, int items) {
        final String[] points = getResources().getStringArray(items);
        if (points.length == 0) return;
        final LayoutInflater inf = LayoutInflater.from(this);
        final TextView h = (TextView) inf.inflate(R.layout.section_heading, list, false);
        h.setText(heading);
        list.addView(h);
        for (String p : points) {
            final View row = inf.inflate(R.layout.row_point, list, false);
            ((ImageView) row.findViewById(R.id.row_icon)).setImageResource(R.drawable.ic_check);
            ((TextView) row.findViewById(R.id.row_title)).setText(p);
            list.addView(row);
        }
    }

    /** step 3: all set */
    private View ready() {
        final LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(32), 0, dp(32), dp(40));
        v.addView(text(getString(R.string.whatsnew_all_set), 30, true));
        final TextView sub = text(getString(R.string.whatsnew_all_set_summary), 17, false);
        sub.setPadding(0, dp(12), 0, 0);
        v.addView(sub);
        return v;
    }

    private TextView text(String s, int sp, boolean strong) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(strong ? com.kiroos.ui.R.color.kiro_text
                : com.kiroos.ui.R.color.kiro_text_secondary));
        t.setTypeface(android.graphics.Typeface.create(strong ? "sans-serif-medium"
                : "sans-serif", android.graphics.Typeface.NORMAL));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    /** Start: the accent colour pours out of the button, then the phone is back */
    private void playStart(final View button) {
        if (mStarting) return;
        mStarting = true;
        mNext.setEnabled(false);
        mBack.setEnabled(false);
        markSeen(this);
        final ViewGroup root = (ViewGroup) getWindow().getDecorView();
        final View fill = new View(this);
        fill.setBackgroundColor(getColor(com.kiroos.ui.R.color.kiro_accent));
        root.addView(fill, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final TextView hello = new TextView(this);
        hello.setText(getString(R.string.whatsnew_enjoy, channel()));
        hello.setTextColor(getColor(com.kiroos.ui.R.color.kiro_on_accent));
        hello.setTextSize(28);
        hello.setTypeface(android.graphics.Typeface.create("sans-serif-light",
                android.graphics.Typeface.NORMAL));
        hello.setGravity(Gravity.CENTER);
        hello.setAlpha(0f);
        root.addView(hello, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final int[] loc = new int[2];
        button.getLocationOnScreen(loc);
        final int cx = loc[0] + button.getWidth() / 2;
        final int cy = loc[1] + button.getHeight() / 2;
        fill.post(new Runnable() {
            @Override
            public void run() {
                final float radius = (float) Math.hypot(Math.max(cx, root.getWidth() - cx),
                        Math.max(cy, root.getHeight() - cy));
                final android.animation.Animator reveal =
                        android.view.ViewAnimationUtils.createCircularReveal(fill, cx, cy,
                                button.getHeight() / 2f, radius);
                reveal.setDuration(520);
                reveal.setInterpolator(new android.view.animation.PathInterpolator(
                        0.3f, 0f, 0.1f, 1f));
                reveal.start();
                hello.setTranslationY(28 * getResources().getDisplayMetrics().density);
                hello.animate().alpha(1f).translationY(0f).setStartDelay(260).setDuration(420)
                        .setInterpolator(KiroTouch.EASE).start();
                hello.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                        overridePendingTransition(0, android.R.anim.fade_out);
                    }
                }, 1250);
            }
        });
    }
}
