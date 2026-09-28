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
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The kAI aura: soft coloured lights (blue, violet, pink, cyan) rising from the bottom of the
 * screen behind the overlay card. They drift slowly while kAI waits, swell with the voice while
 * it listens and swirl faster while it thinks. Radial gradients only: no outlines, no edges.
 */
public class KaiAuraView extends View {

    static final int OFF = 0;
    static final int IDLE = 1;
    static final int LISTENING = 2;
    static final int THINKING = 3;

    private static final int[] COLORS = {0x4C8DFF, 0xA66BFF, 0xFF6FB5, 0x4FD6FF};
    private static final float[] PHASE = {0f, 1.7f, 3.3f, 4.9f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDp;
    private int mMode = OFF;
    private float mShown;       // drawn intensity, eased towards the mode's
    private float mLevel;       // voice level 0..1 (target)
    private float mLevelShown;  // eased
    private float mT;           // animation clock, seconds
    private long mLastFrame;

    public KaiAuraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
    }

    void setMode(int mode) {
        mMode = mode;
        if (mode != LISTENING) {
            mLevel = 0;
        }
        postInvalidateOnAnimation();
    }

    void setLevel(float level) {
        mLevel = Math.max(0f, Math.min(1f, level));
    }

    private float target() {
        switch (mMode) {
            case LISTENING: return 0.8f + 0.2f * mLevelShown;
            case THINKING: return 0.95f;
            case IDLE: return 0.5f;
            default: return 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = System.nanoTime();
        final float dt = mLastFrame == 0 ? 0.016f : Math.min(0.05f, (now - mLastFrame) / 1e9f);
        mLastFrame = now;
        final float speed = mMode == THINKING ? 1.8f : mMode == LISTENING ? 1.0f : 0.45f;
        mT += dt * speed;
        // ease: about 200 ms to follow a change, faster for the voice
        mShown += (target() - mShown) * Math.min(1f, dt * 6f);
        mLevelShown += (mLevel - mLevelShown) * Math.min(1f, dt * 14f);
        if (mMode == OFF && mShown < 0.01f) {
            mShown = 0f;
            mLastFrame = 0;
            return;            // nothing to draw, no more frames
        }
        final int w = getWidth();
        final int h = getHeight();
        final float rise = mDp * (mMode == LISTENING ? 70 + 110 * mLevelShown
                : mMode == THINKING ? 120 : 60);
        for (int i = 0; i < COLORS.length; i++) {
            final float x = w * (0.5f + 0.42f * (float) Math.sin(mT * 0.55f + PHASE[i]));
            final float y = h + mDp * 70
                    - rise * (0.65f + 0.35f * (float) Math.sin(mT * 1.1f + i * 1.3f));
            final float r = w * (0.46f + 0.08f * (float) Math.sin(mT * 0.8f + i * 1.9f))
                    * (1f + 0.25f * mLevelShown);
            final int a = Math.round(255 * mShown * 0.55f);
            final int c = COLORS[i];
            mPaint.setShader(new RadialGradient(x, y, r,
                    new int[] {(a << 24) | c, ((a * 45 / 100) << 24) | c, c},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, mPaint);
        }
        postInvalidateOnAnimation();
    }
}
