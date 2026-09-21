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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The music as it plays: a mirrored, smoothed waveform of the decoded PCM, drawn with a
 * gradient stroke. Falls back to a still line when paused.
 */
public class WaveView extends View implements MusicService.WaveListener {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private float[] mWave = new float[0];
    private final float[] mSmooth = new float[256];

    public WaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float d = getResources().getDisplayMetrics().density;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(2.5f * d);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mGlow.set(mPaint);
        mGlow.setStrokeWidth(8 * d);
        mGlow.setAlpha(50);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        final int a = getContext().getColor(R.color.kiro_accent);
        final Shader s = new LinearGradient(0, 0, w, 0, new int[] {0xFF5E5CE6, a, 0xFF30D158},
                null, Shader.TileMode.CLAMP);
        mPaint.setShader(s);
        mGlow.setShader(s);
    }

    @Override
    public void onWave(final float[] wave) {
        post(new Runnable() {
            @Override
            public void run() {
                mWave = wave;
                invalidate();
            }
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float mid = getHeight() / 2f;
        final float amp = getHeight() * 0.45f;
        final int n = Math.min(mWave.length, mSmooth.length);
        mPath.reset();
        if (n < 2) {
            mPath.moveTo(0, mid);
            mPath.lineTo(w, mid);
        } else {
            for (int i = 0; i < n; i++) {
                // ease towards the new value: calmer, fluid motion
                mSmooth[i] += (mWave[i] - mSmooth[i]) * 0.55f;
                // taper the ends so the line starts and ends at the centre
                final float t = (float) Math.sin(Math.PI * i / (n - 1));
                final float x = w * i / (n - 1);
                final float y = mid - mSmooth[i] * amp * t;
                if (i == 0) {
                    mPath.moveTo(x, y);
                } else {
                    final float px = w * (i - 1) / (n - 1);
                    mPath.quadTo(px, y, (px + x) / 2, y);
                }
            }
            mPath.lineTo(w, mid);
        }
        canvas.drawPath(mPath, mGlow);
        canvas.drawPath(mPath, mPaint);
    }
}
