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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

import java.util.List;

/**
 * The Kiro Home icon pack: every app icon becomes a squircle (superellipse, n = 4), the
 * shape of the KiroOS suite icons, so the home looks uniform whatever the app ships.
 * <ul>
 * <li>Shaped icons that fill their square (squares, rounded squares, squircles) are clipped
 * to the squircle at full size.</li>
 * <li>Icons with a solid background disc (most round legacy icons) are extended: the plate
 * takes the disc's own colour and the icon is drawn over it, as an adaptive icon would.</li>
 * <li>Anything else (transparent or irregular icons) sits centred on a graphite plate.</li>
 * </ul>
 * Every plate gets a soft top-light and a hairline rim. Bitmaps are cached (LRU sized to
 * the heap) and can be rendered ahead of time off the main thread.
 */
final class IconFactory {

    private static final int PLATE_TOP = 0xFF2E2E33;
    private static final int PLATE_BOTTOM = 0xFF18181B;
    private static final int RIM = 0x2EFFFFFF;
    /** how much of the plate a transparent icon covers */
    private static final float INNER = 0.78f;

    private final Context mContext;
    private final int mSize;
    private final int mDensity;
    private final Path mSquircle = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LruCache<String, Bitmap> mCache;

    IconFactory(Context context) {
        mContext = context;
        mSize = context.getResources().getDimensionPixelSize(R.dimen.icon_size);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        buildSquircle(mSquircle, mSize);
        // top-light: a faint white wash fading out by the middle of the icon
        mSheen.setShader(new LinearGradient(0, 0, 0, mSize * 0.6f,
                0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        mPlate.setShader(new LinearGradient(0, 0, 0, mSize,
                PLATE_TOP, PLATE_BOTTOM, Shader.TileMode.CLAMP));
        // a screen and a half of icons for the drawer, but never more than 1/8 of the heap
        final int bytes = (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 32L * 1024 * 1024);
        mCache = new LruCache<String, Bitmap>(bytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
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
        return new BitmapDrawable(mContext.getResources(), bitmap(app));
    }

    /** Renders the icons that are not cached yet; safe to call on a worker thread. */
    void prewarm(List<AppInfo> apps) {
        for (AppInfo app : apps) {
            if (mCache.get(key(app)) == null) {
                bitmap(app);
            }
        }
    }

    private Bitmap bitmap(AppInfo app) {
        final String key = key(app);
        Bitmap b = mCache.get(key);
        if (b == null) {
            b = render(app.info.getBadgedIcon(mDensity));
            mCache.put(key, b);
        }
        return b;
    }

    private static String key(AppInfo app) {
        return app.key() + "#" + app.user.hashCode();
    }

    void invalidate(String packageName) {
        for (String key : mCache.snapshot().keySet()) {
            if (key.startsWith(packageName + "/")) {
                mCache.remove(key);
            }
        }
    }

    /** Serialized: the main thread and the prewarm thread share the paints and the path. */
    private synchronized Bitmap render(Drawable source) {
        final Bitmap raw = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas rc = new Canvas(raw);
        source.setBounds(0, 0, mSize, mSize);
        source.draw(rc);

        final Bitmap out = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        c.save();
        c.clipPath(mSquircle);
        if (fillsSquare(raw)) {
            c.drawBitmap(raw, 0, 0, mPaint);
        } else {
            final int disc = discColor(raw);
            if (disc != 0) {
                // round icon with a solid background: grow the background to the squircle
                // (drawn 8% larger so the legacy drop shadow around the disc falls outside)
                c.drawColor(disc);
                c.save();
                c.scale(1.08f, 1.08f, mSize / 2f, mSize / 2f);
                c.drawBitmap(raw, 0, 0, mPaint);
                c.restore();
            } else {
                c.drawPaint(mPlate);
                final int inset = Math.round(mSize * (1 - INNER) / 2);
                source.setBounds(inset, inset, mSize - inset, mSize - inset);
                source.draw(c);
            }
        }
        c.drawRect(0, 0, mSize, mSize * 0.6f, mSheen);
        c.restore();
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(Math.max(1f, mSize / 96f));
        mPaint.setColor(RIM);
        c.drawPath(mSquircle, mPaint);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(Color.BLACK);
        raw.recycle();
        return out;
    }

    /**
     * True if the icon covers its square up to the corners of a squircle: opaque at the four
     * edge midpoints and near the four corners (12% in), which squares, rounded squares and
     * squircles are, and circles are not.
     */
    private static boolean fillsSquare(Bitmap b) {
        final int w = b.getWidth();
        final int h = b.getHeight();
        final int m = Math.round(w * 0.12f);
        final int[][] points = {{m, m}, {w - 1 - m, m}, {m, h - 1 - m}, {w - 1 - m, h - 1 - m},
                {w / 2, 1}, {w / 2, h - 2}, {1, h / 2}, {w - 2, h / 2}};
        for (int[] p : points) {
            if (Color.alpha(b.getPixel(p[0], p[1])) < 250) {
                return false;
            }
        }
        return true;
    }

    /**
     * The background colour of a round icon: sampled on a ring just inside the disc edge
     * (and on a second, smaller ring). Returns 0 unless all samples are opaque and nearly
     * the same colour, i.e. the icon really has a flat background disc.
     */
    private static int discColor(Bitmap b) {
        final int w = b.getWidth();
        final float cx = w / 2f;
        final float cy = b.getHeight() / 2f;
        long r = 0;
        long g = 0;
        long bl = 0;
        int n = 0;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (float ring : new float[] {0.455f, 0.42f}) {
            for (int i = 0; i < 24; i++) {
                final double t = 2 * Math.PI * i / 24;
                final int x = Math.round(cx + (float) Math.cos(t) * w * ring);
                final int y = Math.round(cy + (float) Math.sin(t) * w * ring);
                final int p = b.getPixel(Math.min(w - 1, Math.max(0, x)),
                        Math.min(b.getHeight() - 1, Math.max(0, y)));
                if (Color.alpha(p) < 250) {
                    return 0;
                }
                final int pr = Color.red(p);
                final int pg = Color.green(p);
                final int pb = Color.blue(p);
                minR = Math.min(minR, pr);
                maxR = Math.max(maxR, pr);
                minG = Math.min(minG, pg);
                maxG = Math.max(maxG, pg);
                minB = Math.min(minB, pb);
                maxB = Math.max(maxB, pb);
                r += pr;
                g += pg;
                bl += pb;
                n++;
            }
        }
        if (maxR - minR > 28 || maxG - minG > 28 || maxB - minB > 28) {
            return 0; // a gradient or a picture: no flat colour to extend
        }
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
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
