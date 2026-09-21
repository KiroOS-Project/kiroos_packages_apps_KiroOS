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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * The Kiro Home icon pack: every app icon sits on a squircle plate (superellipse, n = 4).
 * Full-bleed square icons are clipped to the squircle; icons with transparent margins
 * (round or irregular legacy icons) are centred on a near-black plate with a hairline rim,
 * which keeps them high contrast on any wallpaper. Bitmaps are cached (LRU, 6 MB).
 */
final class IconFactory {

    private static final int PLATE = 0xFF1C1C1F;
    private static final int RIM = 0x33FFFFFF;
    /** how much of the plate a padded icon covers */
    private static final float INNER = 0.74f;

    private final Context mContext;
    private final int mSize;
    private final Path mSquircle = new Path();
    private final LruCache<String, Bitmap> mCache =
            new LruCache<String, Bitmap>(6 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    IconFactory(Context context) {
        mContext = context;
        mSize = context.getResources().getDimensionPixelSize(R.dimen.icon_size);
        buildSquircle(mSquircle, mSize);
    }

    int size() {
        return mSize;
    }

    static void buildSquircle(Path path, int size) {
        path.reset();
        final float r = size / 2f;
        for (int i = 0; i <= 96; i++) {
            final double t = 2 * Math.PI * i / 96;
            final double c = Math.cos(t);
            final double s = Math.sin(t);
            final float x = r + (float) (r * Math.signum(c) * Math.sqrt(Math.abs(c)));
            final float y = r + (float) (r * Math.signum(s) * Math.sqrt(Math.abs(s)));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    Drawable icon(AppInfo app) {
        final String key = app.key() + "#" + app.user.hashCode();
        Bitmap b = mCache.get(key);
        if (b == null) {
            b = render(app.info.getBadgedIcon(mContext.getResources().getDisplayMetrics().densityDpi));
            mCache.put(key, b);
        }
        return new BitmapDrawable(mContext.getResources(), b);
    }

    void invalidate(String packageName) {
        for (String key : mCache.snapshot().keySet()) {
            if (key.startsWith(packageName + "/")) {
                mCache.remove(key);
            }
        }
    }

    private Bitmap render(Drawable source) {
        final Bitmap raw = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas rc = new Canvas(raw);
        source.setBounds(0, 0, mSize, mSize);
        source.draw(rc);
        final boolean fullBleed = isOpaqueSquare(raw);

        final Bitmap out = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.save();
        c.clipPath(mSquircle);
        if (fullBleed) {
            c.drawBitmap(raw, 0, 0, paint);
        } else {
            c.drawColor(PLATE);
            final int inset = Math.round(mSize * (1 - INNER) / 2);
            source.setBounds(inset, inset, mSize - inset, mSize - inset);
            source.draw(c);
        }
        c.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, mSize / 96f));
        paint.setColor(RIM);
        c.drawPath(mSquircle, paint);
        raw.recycle();
        return out;
    }

    /** True if the icon fills its square (opaque corners and edges). */
    private static boolean isOpaqueSquare(Bitmap b) {
        final int w = b.getWidth();
        final int h = b.getHeight();
        final int m = w / 12;
        final int[][] points = {{m, m}, {w - 1 - m, m}, {m, h - 1 - m}, {w - 1 - m, h - 1 - m},
                {w / 2, 1}, {w / 2, h - 2}, {1, h / 2}, {w - 2, h / 2}};
        for (int[] p : points) {
            if (Color.alpha(b.getPixel(p[0], p[1])) < 250) {
                return false;
            }
        }
        return true;
    }

    /** A bitmap copy of a drawable at icon size (for drag shadows). */
    Bitmap toBitmap(Drawable d) {
        final Bitmap b = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(b);
        final Rect old = d.copyBounds();
        d.setBounds(0, 0, mSize, mSize);
        d.draw(c);
        d.setBounds(old);
        return b;
    }
}
