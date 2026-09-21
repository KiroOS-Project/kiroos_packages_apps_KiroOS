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

package com.kiroos.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Live input level as rounded bars scrolling from right to left. */
public class WaveformView extends View {

    private static final int BARS = 64;

    private final float[] mLevels = new float[BARS];
    private int mHead;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setColor(context.getColor(R.color.kiro_accent));
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** @param amplitude 0…32767 from MediaRecorder */
    void push(int amplitude) {
        // perceptual scale: speech sits in the middle instead of at the bottom
        final float level = amplitude <= 0 ? 0f
                : (float) Math.min(1.0, Math.log10(1 + amplitude) / Math.log10(32768));
        mLevels[mHead] = level;
        mHead = (mHead + 1) % BARS;
        invalidate();
    }

    void clear() {
        java.util.Arrays.fill(mLevels, 0f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float step = w / BARS;
        mPaint.setStrokeWidth(step * 0.55f);
        final float mid = h / 2f;
        for (int i = 0; i < BARS; i++) {
            final float level = mLevels[(mHead + i) % BARS];
            final float half = Math.max(step * 0.3f, level * (h / 2f - step));
            final float x = i * step + step / 2f;
            mPaint.setAlpha(Math.round(80 + 175 * (i / (float) BARS)));
            canvas.drawLine(x, mid - half, x, mid + half, mPaint);
        }
    }
}
