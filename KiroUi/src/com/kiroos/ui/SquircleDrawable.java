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

package com.kiroos.ui;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;

/**
 * A superellipse ("squircle", |x|^4 + |y|^4 = 1) filled with one color: the KiroOS v1.0
 * button shape. Its outline matches the path, so views clip and cast shadows to it.
 */
public class SquircleDrawable extends Drawable {

    private static final int SEGMENTS = 96;
    private static final double EXPONENT = 4.0;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    public SquircleDrawable(int color) {
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
    }

    /** A squircle with a ripple on top, for buttons. */
    public static RippleDrawable button(int color, int rippleColor) {
        final SquircleDrawable shape = new SquircleDrawable(color);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape,
                new SquircleDrawable(0xFFFFFFFF));
    }

    public void setColor(int color) {
        mPaint.setColor(color);
        invalidateSelf();
    }

    public int getColor() {
        return mPaint.getColor();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        buildPath(bounds, mPath);
    }

    static void buildPath(Rect b, Path path) {
        path.reset();
        final float cx = b.exactCenterX();
        final float cy = b.exactCenterY();
        final float rx = b.width() / 2f;
        final float ry = b.height() / 2f;
        for (int i = 0; i <= SEGMENTS; i++) {
            final double t = 2 * Math.PI * i / SEGMENTS;
            final double c = Math.cos(t);
            final double s = Math.sin(t);
            final float x = cx + (float) (rx * Math.signum(c) * Math.pow(Math.abs(c), 2 / EXPONENT));
            final float y = cy + (float) (ry * Math.signum(s) * Math.pow(Math.abs(s), 2 / EXPONENT));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(mPath, mPaint);
    }

    @Override
    public void getOutline(Outline outline) {
        if (mPath.isConvex()) {
            outline.setConvexPath(mPath);
        } else {
            outline.setRoundRect(getBounds(), getBounds().width() * 0.3f);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
