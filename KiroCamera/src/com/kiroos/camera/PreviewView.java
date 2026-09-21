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
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * The viewfinder: a TextureView sized to the preview's aspect ratio (no stretching), with
 * an optional rule-of-thirds grid drawn by the overlay.
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

    /** 3 × 3 grid over the viewfinder */
    static final class Grid extends android.view.View {
        private final Paint mPaint = new Paint();

        Grid(Context context) {
            super(context);
            mPaint.setColor(0x55FFFFFF);
            mPaint.setStrokeWidth(context.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth();
            final float h = getHeight();
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(w * i / 3, 0, w * i / 3, h, mPaint);
                canvas.drawLine(0, h * i / 3, w, h * i / 3, mPaint);
            }
        }
    }
}
