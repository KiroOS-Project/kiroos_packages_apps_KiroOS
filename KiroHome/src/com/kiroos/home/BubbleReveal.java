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

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

/**
 * KiroOS Beta 4 first boot: the setup's "Start" filled the screen with the accent colour; the
 * home opens out of it like a bubble. A hole grows from the centre with a soft edge that widens
 * with its speed (a motion blur: fast in the middle of the move, sharp as it settles), a bright
 * membrane rides the edge with two fading wakes behind it, and the home comes forward from
 * slightly further away. Played once, right after the setup (Settings.Global
 * kiroos_setup_finished), never after an update.
 */
final class BubbleReveal extends View {

    private static final String KEY = "bubble_played_for";
    private static final long MAX_AGE_MS = 2 * 60 * 1000;
    private static final long DURATION = 1100;

    static void maybePlay(Activity activity, View homeContent) {
        final long finished = Settings.Global.getLong(activity.getContentResolver(),
                "kiroos_setup_finished", 0);
        final SharedPreferences p = activity.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (finished <= 0 || p.getLong(KEY, 0) >= finished
                || System.currentTimeMillis() - finished > MAX_AGE_MS) {
            return;
        }
        p.edit().putLong(KEY, finished).apply();
        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        final BubbleReveal v = new BubbleReveal(activity);
        decor.addView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        v.start(homeContent);
    }

    private final int mAccent;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private float mRadius;
    private float mFeather;
    private float mMax;

    private BubbleReveal(Context c) {
        super(c);
        mAccent = c.getColor(com.kiroos.ui.R.color.kiro_accent);
        mDensity = c.getResources().getDisplayMetrics().density;
        mRing.setStyle(Paint.Style.STROKE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    private void start(final View home) {
        if (home != null) {
            home.setScaleX(0.9f);
            home.setScaleY(0.9f);
        }
        final PathInterpolator ease = new PathInterpolator(0.45f, 0f, 0.15f, 1f);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(DURATION);
        a.setStartDelay(60);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            private float mLastR;
            private long mLastT;

            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                final float t = (float) anim.getAnimatedValue();
                final float e = ease.getInterpolation(t);
                mRadius = e * mMax;
                // the edge blurs with the speed of the bubble (px per ms)
                final long now = anim.getCurrentPlayTime();
                final float speed = mLastT > 0 && now > mLastT
                        ? Math.abs(mRadius - mLastR) / (now - mLastT) : 0f;
                mLastR = mRadius;
                mLastT = now;
                mFeather = 18 * mDensity + speed * 34 * mDensity;
                if (home != null) {
                    final float s = 0.9f + 0.1f * e;
                    home.setScaleX(s);
                    home.setScaleY(s);
                }
                invalidate();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator anim) {
                if (home != null) {
                    home.setScaleX(1f);
                    home.setScaleY(1f);
                }
                ((ViewGroup) getParent()).removeView(BubbleReveal.this);
            }
        });
        post(new Runnable() {
            @Override
            public void run() {
                mMax = (float) Math.hypot(getWidth() / 2f, getHeight() / 2f) + 60 * mDensity;
                a.start();
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f, cy = getHeight() / 2f;
        if (mMax <= 0) {
            canvas.drawColor(mAccent);
            return;
        }
        final float outer = Math.max(mRadius + mFeather, 1f);
        // the colour, with a hole of mRadius whose edge fades over mFeather
        final float inner = Math.max(0f, Math.min(0.999f, mRadius / outer));
        final int clear = mAccent & 0x00ffffff;
        mFill.setShader(new RadialGradient(cx, cy, outer,
                new int[] { clear, clear, mAccent }, new float[] { 0f, inner, 1f },
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), mFill);
        if (mRadius > 0) {
            // the bubble's membrane and its two wakes, fading as it reaches the corners
            final float fade = 1f - Math.min(1f, mRadius / mMax);
            for (int k = 0; k < 3; k++) {
                final float r = mRadius + mFeather * (0.35f + 0.25f * k);
                mRing.setStrokeWidth((3 - k) * 1.2f * mDensity);
                mRing.setColor(Color.argb((int) (fade * (170 - 55 * k)), 255, 255, 255));
                canvas.drawCircle(cx, cy, r, mRing);
            }
        }
    }
}
