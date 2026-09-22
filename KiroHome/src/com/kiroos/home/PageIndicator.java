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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Home page dots: the active one stretches into a short pill that slides with the finger
 * between pages. Hidden with a single page.
 */
public class PageIndicator extends View {

    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mActive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mRingWidth;
    private final RectF mRect = new RectF();
    private final float mSize;
    private final float mGap;
    private int mCount = 1;
    private float mPosition;

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float d = getResources().getDisplayMetrics().density;
        mSize = 6 * d;
        mGap = 8 * d;
        mDot.setColor(0x66FFFFFF);
        mActive.setColor(0xFFFFFFFF);
        // a thin dark ring keeps the dots visible on light wallpapers (drawn on the GPU;
        // a blurred shadow layer would need a software redraw on every scroll frame)
        mRing.setColor(0x40000000);
        mRingWidth = 1.2f * d;
    }

    void setCount(int count) {
        mCount = count;
        setVisibility(count > 1 ? VISIBLE : INVISIBLE);
        invalidate();
    }

    void setPosition(float position) {
        mPosition = position;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mCount < 2) {
            return;
        }
        final float total = mCount * mSize + (mCount - 1) * mGap;
        final float x0 = (getWidth() - total) / 2f;
        final float cy = getHeight() / 2f;
        final float r = mSize / 2f;
        for (int i = 0; i < mCount; i++) {
            final float cx = x0 + i * (mSize + mGap) + r;
            canvas.drawCircle(cx, cy, r + mRingWidth, mRing);
            canvas.drawCircle(cx, cy, r, mDot);
        }
        // the active pill: stretches toward the next page while the finger moves
        final float p = Math.max(0, Math.min(mCount - 1, mPosition));
        final int base = (int) Math.floor(p);
        final float frac = p - base;
        final float step = mSize + mGap;
        final float left = x0 + base * step + Math.max(0, frac - 0.5f) * 2 * step;
        final float right = x0 + base * step + mSize + Math.min(1, frac * 2) * step;
        mRect.set(left, cy - r, Math.min(right, x0 + total), cy + r);
        canvas.drawRoundRect(mRect, r, r, mActive);
    }
}
