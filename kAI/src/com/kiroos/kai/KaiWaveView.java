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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The voice while kAI listens: rounded bars in the kAI colours that follow the voice, each a
 * little out of step with the next; while the speech is being understood they settle into a
 * gentle travelling ripple.
 */
public class KaiWaveView extends View {

    static final int LISTENING = 0;
    static final int THINKING = 1;

    private static final int BARS = 7;
    private static final float[] SHAPE = {0.45f, 0.7f, 0.9f, 1f, 0.9f, 0.7f, 0.45f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mDp;
    private int mMode = LISTENING;
    private float mLevel;
    private float mLevelShown;
    private float mT;
    private long mLastFrame;

    public KaiWaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
    }

    void setMode(int mode) {
        mMode = mode;
        invalidate();
    }

    void setLevel(float level) {
        mLevel = Math.max(0f, Math.min(1f, level));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        mPaint.setShader(new LinearGradient(0, 0, w, 0,
                new int[] {0xFF4C8DFF, 0xFFA66BFF, 0xFFFF6FB5}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            mLastFrame = 0;
            return;
        }
        final long now = System.nanoTime();
        final float dt = mLastFrame == 0 ? 0.016f : Math.min(0.05f, (now - mLastFrame) / 1e9f);
        mLastFrame = now;
        mT += dt;
        mLevelShown += (mLevel - mLevelShown) * Math.min(1f, dt * 16f);
        final float barW = 4.5f * mDp;
        final float gap = 5f * mDp;
        final float total = BARS * barW + (BARS - 1) * gap;
        final float left = (getWidth() - total) / 2f;
        final float cy = getHeight() / 2f;
        final float minH = 5f * mDp;
        final float maxH = Math.min(getHeight(), 30f * mDp);
        for (int i = 0; i < BARS; i++) {
            final float amount;
            if (mMode == LISTENING) {
                // the voice, each bar slightly out of step; a faint breath in the silences
                final float wobble = 0.6f + 0.4f * (float) Math.sin(mT * 11f + i * 1.3f);
                amount = Math.max(0.12f + 0.08f * (float) Math.sin(mT * 3f + i),
                        mLevelShown * SHAPE[i] * wobble * 1.4f);
            } else {
                amount = 0.18f + 0.3f * (0.5f + 0.5f * (float) Math.sin(mT * 5f - i * 0.8f));
            }
            final float bh = minH + (maxH - minH) * Math.min(1f, amount);
            final float x = left + i * (barW + gap);
            mRect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f);
            canvas.drawRoundRect(mRect, barW / 2f, barW / 2f, mPaint);
        }
        postInvalidateOnAnimation();
    }
}
