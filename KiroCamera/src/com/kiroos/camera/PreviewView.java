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

package com.kiroos.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * The viewfinder: a TextureView sized to the preview's aspect ratio (no stretching), with
 * an optional rule-of-thirds grid and level drawn by the overlay.
 */
public class PreviewView extends TextureView {

    private int mRatioW;
    private int mRatioH;

    public PreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** portrait ratio of the preview, e.g. 3 × 4 */
    void setAspectRatio(int width, int height) {
        mRatioW = width;
        mRatioH = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = MeasureSpec.getSize(heightMeasureSpec);
        if (mRatioW == 0 || mRatioH == 0) {
            setMeasuredDimension(w, h);
        } else if (w * mRatioH < h * mRatioW) {
            setMeasuredDimension(w, w * mRatioH / mRatioW);
        } else {
            setMeasuredDimension(h * mRatioW / mRatioH, h);
        }
    }

    /**
     * 3 × 3 grid over the viewfinder, and (1.2) a level: a line through the centre that
     * follows the horizon, between two fixed marks; within 1° of level (upright or sideways)
     * it turns green and joins the marks, with a light tick. The accelerometer is only read
     * while the grid is on screen; held flat (pointing down or up) the level hides.
     */
    static final class Grid extends android.view.View implements SensorEventListener {
        private static final float LEVEL_DEGREES = 1f;
        private static final int GREEN = 0xFF34C759;

        private final Paint mPaint = new Paint();
        private final Paint mLevel = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SensorManager mSensors;
        private final float mDensity;
        private boolean mListening;
        /** the gravity on the screen plane (low-pass), device axes: x right, y up */
        private float mGx;
        private float mGy;
        private float mGz;
        private boolean mHaveGravity;
        private boolean mWasLevel;

        Grid(Context context) {
            super(context);
            mDensity = context.getResources().getDisplayMetrics().density;
            mPaint.setColor(0x55FFFFFF);
            mPaint.setStrokeWidth(mDensity);
            mLevel.setStrokeCap(Paint.Cap.ROUND);
            mSensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(w * i / 3, 0, w * i / 3, h, mPaint);
                canvas.drawLine(0, h * i / 3, w, h * i / 3, mPaint);
            }
            drawLevel(canvas, w, h);
        }

        private void drawLevel(Canvas canvas, float w, float h) {
            final float g = (float) Math.sqrt(mGx * mGx + mGy * mGy + mGz * mGz);
            if (!mHaveGravity || g < 1f || Math.abs(mGz) > 0.85f * g) {
                mWasLevel = false;
                return; // no reading yet, or held flat: no horizon to show
            }
            // device angle from upright, and the nearest upright or sideways holding
            final double angle = Math.toDegrees(Math.atan2(mGx, mGy));
            final double snapped = Math.round(angle / 90.0) * 90.0;
            final double roll = angle - snapped;
            final boolean level = Math.abs(roll) < LEVEL_DEGREES;
            if (level && !mWasLevel) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            }
            mWasLevel = level;

            final float cx = w / 2f;
            final float cy = h / 2f;
            final float size = Math.min(w, h);
            // the marks: on the axis of the holding (horizontal upright, vertical sideways)
            final double ref = Math.toRadians(snapped);
            final float rx = (float) Math.cos(ref);
            final float ry = (float) Math.sin(ref);
            mLevel.setColor(level ? GREEN : 0xCCFFFFFF);
            mLevel.setStrokeWidth(2.5f * mDensity);
            final float in = size * 0.22f;
            final float out = size * 0.30f;
            canvas.drawLine(cx - rx * out, cy - ry * out, cx - rx * in, cy - ry * in, mLevel);
            canvas.drawLine(cx + rx * in, cy + ry * in, cx + rx * out, cy + ry * out, mLevel);
            // the horizon: across the gravity seen on the screen (canvas y points down)
            final float len = (float) Math.sqrt(mGx * mGx + mGy * mGy);
            final float hx = mGy / len;
            final float hy = mGx / len;
            final float half = level ? out : in * 0.9f;
            mLevel.setStrokeWidth((level ? 2.5f : 2f) * mDensity);
            canvas.drawLine(cx - hx * half, cy - hy * half, cx + hx * half, cy + hy * half, mLevel);
        }

        // --- the accelerometer, only while shown -------------------------------------------

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateListening();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopListening();
        }

        @Override
        protected void onVisibilityChanged(android.view.View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            updateListening();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            updateListening();
        }

        private void updateListening() {
            if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE) {
                if (!mListening && mSensors != null) {
                    final Sensor accel = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    if (accel != null) {
                        mListening = mSensors.registerListener(this, accel,
                                SensorManager.SENSOR_DELAY_UI);
                    }
                }
            } else {
                stopListening();
            }
        }

        private void stopListening() {
            if (mListening) {
                mSensors.unregisterListener(this);
                mListening = false;
                mHaveGravity = false;
            }
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            final float a = mHaveGravity ? 0.15f : 1f; // low-pass: a steady line
            mGx += a * (e.values[0] - mGx);
            mGy += a * (e.values[1] - mGy);
            mGz += a * (e.values[2] - mGz);
            mHaveGravity = true;
            invalidate();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }
}
