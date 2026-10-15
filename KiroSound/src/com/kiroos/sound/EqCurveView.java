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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * The equalizer's real response: the same ten RBJ peaking filters the KiroOS Equalizer runs
 * (KiroEqualizer.c), evaluated from 20 Hz to 20 kHz, so what is drawn is what plays (the
 * neighbouring bands add up, as they do in the sound).
 */
public class EqCurveView extends View {

    private static final float RATE = 48000f;
    private static final float F_MIN = 20f;
    private static final float F_MAX = 20000f;
    private static final float DB_RANGE = 12f;
    private static final int POINTS = 160;

    private final short[] mLevels = new short[Profiles.BANDS];
    private final Paint mGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mZero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final Path mArea = new Path();
    private final int mAccent;

    public EqCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mAccent = context.getColor(com.kiroos.ui.R.color.kiro_accent);
        final int secondary = context.getColor(com.kiroos.ui.R.color.kiro_text_secondary);
        mGrid.setColor(secondary);
        mGrid.setAlpha(40);
        mGrid.setStrokeWidth(dp(1));
        mZero.setColor(secondary);
        mZero.setAlpha(90);
        mZero.setStrokeWidth(dp(1));
        mLine.setColor(mAccent);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(dp(2.5f));
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mLabel.setColor(secondary);
        mLabel.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                getResources().getDisplayMetrics()));
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    void setLevels(short[] levels) {
        System.arraycopy(levels, 0, mLevels, 0, Math.min(levels.length, mLevels.length));
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mFill.setShader(new LinearGradient(0, 0, 0, h, (mAccent & 0x00ffffff) | 0x55000000,
                mAccent & 0x00ffffff, Shader.TileMode.CLAMP));
    }

    /** the response in dB at one frequency: the sum of the bands' RBJ peaking filters */
    private float responseDb(float hz) {
        final double w = 2 * Math.PI * hz / RATE;
        final double cw = Math.cos(w), c2w = Math.cos(2 * w);
        final double sw = Math.sin(w), s2w = Math.sin(2 * w);
        double total = 0;
        for (int b = 0; b < Profiles.BANDS; b++) {
            if (mLevels[b] == 0) continue;
            final double A = Math.pow(10, mLevels[b] / 100.0 / 40.0);
            final double w0 = 2 * Math.PI * Profiles.CENTERS[b] / RATE;
            final double alpha = Math.sin(w0) / (2 * Profiles.Q);
            final double cw0 = Math.cos(w0);
            final double b0 = 1 + alpha * A, b1 = -2 * cw0, b2 = 1 - alpha * A;
            final double a0 = 1 + alpha / A, a1 = -2 * cw0, a2 = 1 - alpha / A;
            // |H(e^jw)| with z^-1 = e^-jw
            final double nr = b0 + b1 * cw + b2 * c2w, ni = -(b1 * sw + b2 * s2w);
            final double dr = a0 + a1 * cw + a2 * c2w, di = -(a1 * sw + a2 * s2w);
            total += 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
        }
        return (float) total;
    }

    private float x(float hz, float w) {
        return (float) (Math.log(hz / F_MIN) / Math.log(F_MAX / F_MIN)) * w;
    }

    private float y(float db, float top, float h) {
        final float c = Math.max(-DB_RANGE, Math.min(DB_RANGE, db));
        return top + h / 2f - c / DB_RANGE * (h / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float labelH = mLabel.getTextSize() + dp(4);
        final float top = dp(6);
        final float h = getHeight() - top - labelH;

        // grid: every 6 dB, and the decades
        for (int db = -12; db <= 12; db += 6) {
            final float gy = y(db, top, h);
            canvas.drawLine(0, gy, w, gy, db == 0 ? mZero : mGrid);
        }
        final float[] marks = { 100, 1000, 10000 };
        final String[] names = { "100", "1k", "10k" };
        for (int i = 0; i < marks.length; i++) {
            final float gx = x(marks[i], w);
            canvas.drawLine(gx, top, gx, top + h, mGrid);
            canvas.drawText(names[i], gx - mLabel.measureText(names[i]) / 2,
                    getHeight() - dp(2), mLabel);
        }

        mPath.reset();
        mArea.reset();
        final float zero = y(0, top, h);
        for (int i = 0; i <= POINTS; i++) {
            final float hz = (float) (F_MIN * Math.pow(F_MAX / F_MIN, i / (double) POINTS));
            final float px = x(hz, w);
            final float py = y(responseDb(hz), top, h);
            if (i == 0) {
                mPath.moveTo(px, py);
                mArea.moveTo(px, zero);
            }
            mPath.lineTo(px, py);
            mArea.lineTo(px, py);
        }
        mArea.lineTo(w, zero);
        mArea.close();
        canvas.drawPath(mArea, mFill);
        canvas.drawPath(mPath, mLine);
    }
}
