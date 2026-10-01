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

package com.kiroos.setupwizard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A small picture of a KiroOS screen in the light or the dark theme (the Appearance page):
 * a settings card with two lines and a switch, a row of icons, in the theme's own colours.
 */
class ThemePreviewView extends View {

    private final boolean mLight;
    private final int mAccent;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float mDp;
    private boolean mSelected;

    ThemePreviewView(Context context, boolean light) {
        super(context);
        mLight = light;
        mAccent = context.getColor(R.color.kiro_accent);
        mDp = context.getResources().getDisplayMetrics().density;
    }

    void setChosen(boolean chosen) {
        mSelected = chosen;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float stroke = 2.5f * mDp;
        final float r = 20 * mDp;

        // the screen
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mLight ? 0xFFF2F2F7 : 0xFF000000);
        mRect.set(stroke, stroke, w - stroke, h - stroke);
        canvas.drawRoundRect(mRect, r, r, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(stroke);
        mPaint.setColor(mSelected ? mAccent : (mLight ? 0x33000000 : 0x40FFFFFF));
        canvas.drawRoundRect(mRect, r, r, mPaint);
        mPaint.setStyle(Paint.Style.FILL);

        final float pad = 14 * mDp;
        final int card = mLight ? 0xFFFFFFFF : 0xFF1C1C1F;
        final int text = mLight ? 0xFF111114 : 0xFFFFFFFF;
        final int text2 = mLight ? 0xFFB4B4BC : 0xFF5A5A60;

        // a title
        mPaint.setColor(text);
        mRect.set(pad, pad + 4 * mDp, pad + (w - 2 * pad) * 0.55f, pad + 12 * mDp);
        canvas.drawRoundRect(mRect, 4 * mDp, 4 * mDp, mPaint);

        // a settings card: two lines and a switch
        final float cardTop = pad + 24 * mDp;
        mPaint.setColor(card);
        mRect.set(pad, cardTop, w - pad, cardTop + 58 * mDp);
        canvas.drawRoundRect(mRect, 12 * mDp, 12 * mDp, mPaint);
        for (int i = 0; i < 2; i++) {
            final float y = cardTop + 14 * mDp + i * 20 * mDp;
            mPaint.setColor(i == 0 ? text : text2);
            mRect.set(pad + 12 * mDp, y, pad + 12 * mDp + (w - 2 * pad) * (i == 0 ? 0.42f : 0.3f),
                    y + 8 * mDp);
            canvas.drawRoundRect(mRect, 4 * mDp, 4 * mDp, mPaint);
            // the switch
            final float sx = w - pad - 38 * mDp;
            mPaint.setColor(i == 0 ? mAccent : (mLight ? 0xFFDCDCE3 : 0xFF3A3A3F));
            mRect.set(sx, y - 3 * mDp, sx + 26 * mDp, y + 11 * mDp);
            canvas.drawRoundRect(mRect, 7 * mDp, 7 * mDp, mPaint);
            mPaint.setColor(0xFFFFFFFF);
            final float knob = i == 0 ? sx + 19 * mDp : sx + 7 * mDp;
            canvas.drawCircle(knob, y + 4 * mDp, 5 * mDp, mPaint);
        }

        // a row of app icons
        final int[] icons = {0xFF34C759, 0xFF2F7BDA, 0xFFFF9F0A, 0xFFAF52DE};
        final float size = (w - 2 * pad - 3 * 8 * mDp) / 4f;
        final float iy = cardTop + 58 * mDp + 14 * mDp;
        for (int i = 0; i < icons.length; i++) {
            final float x = pad + i * (size + 8 * mDp);
            mPaint.setColor(mLight ? icons[i] : darker(icons[i]));
            mRect.set(x, iy, x + size, iy + size);
            canvas.drawRoundRect(mRect, size * 0.28f, size * 0.28f, mPaint);
        }

        // the chosen mark
        if (mSelected) {
            final float cx = w / 2f;
            final float cy = h - pad - 4 * mDp;
            mPaint.setColor(mAccent);
            canvas.drawCircle(cx, cy, 10 * mDp, mPaint);
            mPaint.setColor(0xFFFFFFFF);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2.2f * mDp);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(cx - 4 * mDp, cy, cx - 1 * mDp, cy + 3 * mDp, mPaint);
            canvas.drawLine(cx - 1 * mDp, cy + 3 * mDp, cx + 4.5f * mDp, cy - 3 * mDp, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
    }

    /** the dark theme's icons are a little darker (as the KiroOS icons are) */
    private static int darker(int c) {
        final float f = 0.72f;
        return 0xFF000000 | (Math.round(((c >> 16) & 0xFF) * f) << 16)
                | (Math.round(((c >> 8) & 0xFF) * f) << 8) | Math.round((c & 0xFF) * f);
    }
}
