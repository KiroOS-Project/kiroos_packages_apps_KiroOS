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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * The light that runs round the kAI overlay card: a blue-violet-pink gradient turning along the
 * card's rounded edge, with a soft halo outside it. The card sits inside this view, inset by
 * {@link #inset()}, so the halo has room.
 *
 *   listening  bright, and it swells with the voice
 *   thinking   bright, turning faster
 *   idle       dim and slow (the answer is being read)
 */
public class GlowBorderView extends View {

    static final int OFF = 0;
    static final int IDLE = 1;
    static final int LISTENING = 2;
    static final int THINKING = 3;

    private static final int[] COLORS = {
        0xFF8FDBFF, 0xFFA58BFF, 0xFFFF7CC8, 0xFFFFB36B, 0xFF8FDBFF,
    };
    /** stroke widths (x the base width) and opacities of the edge and its halo */
    private static final float[] WIDTHS = {1f, 3f, 6f, 10f};
    private static final float[] ALPHAS = {1f, 0.34f, 0.14f, 0.06f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final Matrix mMatrix = new Matrix();
    private final float mStroke;
    private final float mInset;
    private final float mRadius;
    private SweepGradient mShader;
    private ValueAnimator mSpin;
    private float mAngle;
    private int mMode = OFF;
    private float mShown;           // the drawn intensity, eased towards the mode's
    private float mLevel;           // voice level 0..1

    public GlowBorderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float d = getResources().getDisplayMetrics().density;
        mStroke = 2.2f * d;
        mInset = 12 * d;
        mRadius = 30 * d;
        mPaint.setStyle(Paint.Style.STROKE);
        setWillNotDraw(false);
    }

    /** the card's distance from this view's edges */
    float inset() {
        return mInset;
    }

    void setMode(int mode) {
        if (mMode == mode) {
            return;
        }
        mMode = mode;
        if (mode == OFF) {
            mLevel = 0;
        }
        spin(mode == THINKING ? 1600 : mode == LISTENING ? 2400 : 4200);
        invalidate();
    }

    void setLevel(float level) {
        mLevel = Math.max(0f, Math.min(1f, level));
    }

    private float target() {
        switch (mMode) {
            case LISTENING: return 0.85f + 0.15f * mLevel;
            case THINKING: return 1f;
            case IDLE: return 0.4f;
            default: return 0f;
        }
    }

    private void spin(long periodMs) {
        if (mSpin == null) {
            mSpin = ValueAnimator.ofFloat(0f, 360f);
            mSpin.setInterpolator(new LinearInterpolator());
            mSpin.setRepeatCount(ValueAnimator.INFINITE);
            mSpin.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    mAngle = (float) a.getAnimatedValue();
                    invalidate();
                }
            });
        }
        // keep the angle where it is when the speed changes
        final float fraction = mSpin.isRunning() ? mSpin.getAnimatedFraction() : 0f;
        mSpin.cancel();
        mSpin.setDuration(periodMs);
        mSpin.start();
        mSpin.setCurrentFraction(fraction);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mSpin != null) {
            mSpin.cancel();
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mShader = new SweepGradient(w / 2f, h / 2f, COLORS, null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // ease the intensity: about 150 ms to follow a change
        final float t = target();
        mShown += (t - mShown) * 0.18f;
        if (Math.abs(t - mShown) < 0.004f) {
            mShown = t;
        }
        if (mShown <= 0.004f || mShader == null) {
            if (mMode == OFF && mSpin != null && mShown <= 0.004f) {
                mSpin.cancel();
            }
            return;
        }
        mMatrix.setRotate(mAngle, getWidth() / 2f, getHeight() / 2f);
        mShader.setLocalMatrix(mMatrix);
        mPaint.setShader(mShader);
        mRect.set(mInset, mInset, getWidth() - mInset, getHeight() - mInset);
        // the halo first (widest, faintest), the crisp edge last
        for (int i = WIDTHS.length - 1; i >= 0; i--) {
            final float swell = i == 0 ? 1f : 1f + 0.6f * mLevel;
            mPaint.setStrokeWidth(mStroke * WIDTHS[i] * swell);
            mPaint.setAlpha(Math.round(255 * ALPHAS[i] * mShown));
            canvas.drawRoundRect(mRect, mRadius, mRadius, mPaint);
        }
    }
}
