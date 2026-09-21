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

package com.kiroos.pulse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** A vertical equalizer slider (−range … +range dB) with a filled bar from the centre. */
public class BandSlider extends View {

    interface OnChange {
        void onChange(BandSlider slider, float db);
    }

    private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mKnob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private float mRange = 12f;
    private float mValue;
    private OnChange mListener;
    private int mLastStep;

    public BandSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        mTrack.setColor(context.getColor(R.color.kiro_surface_raised));
        mFill.setColor(context.getColor(R.color.kiro_accent));
        mKnob.setColor(context.getColor(R.color.kiro_text));
    }

    void setRange(float range) {
        mRange = range;
        invalidate();
    }

    void setValue(float db) {
        mValue = Math.max(-mRange, Math.min(mRange, db));
        invalidate();
    }

    float value() {
        return mValue;
    }

    void setOnChange(OnChange l) {
        mListener = l;
    }

    private float yFor(float db) {
        final float pad = getWidth() / 2f;
        final float h = getHeight() - pad * 2;
        return pad + h * (1 - (db + mRange) / (2 * mRange));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f;
        final float tw = getResources().getDisplayMetrics().density * 6;
        final float pad = getWidth() / 2f;
        mRect.set(cx - tw / 2, pad, cx + tw / 2, getHeight() - pad);
        canvas.drawRoundRect(mRect, tw / 2, tw / 2, mTrack);
        final float y0 = yFor(0);
        final float y = yFor(mValue);
        mRect.set(cx - tw / 2, Math.min(y0, y), cx + tw / 2, Math.max(y0, y));
        canvas.drawRoundRect(mRect, tw / 2, tw / 2, mFill);
        canvas.drawCircle(cx, y, getResources().getDisplayMetrics().density * 10, mKnob);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                final float pad = getWidth() / 2f;
                final float h = getHeight() - pad * 2;
                float db = (1 - (e.getY() - pad) / h) * 2 * mRange - mRange;
                db = Math.round(db * 2) / 2f; // 0.5 dB steps
                setValue(db);
                final int step = Math.round(mValue);
                if (step != mLastStep) {
                    mLastStep = step;
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                if (mListener != null) {
                    mListener.onChange(this, mValue);
                }
                return true;
            default:
                return true;
        }
    }
}
