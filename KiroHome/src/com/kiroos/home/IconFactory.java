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
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
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
 * <li>Round icons with a picture or gradient disc, and rounded squares, are drawn with
 * their edge on the squircle's sides; the corners are filled by stretching the icon's own
 * edge colours outwards (no crop, no plate).</li>
 * <li>Anything else (transparent or irregular icons) is trimmed and centred on a light
 * plate (graphite for light icons).</li>
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
    /** a trimmed transparent icon's longest side, as a share of the plate */
    private static final float INNER_FIT = 0.64f;
    /** the n = 4 squircle's corner lies this far from the centre, in radii (sqrt 2 * cos(45)^0.5) */
    private static final float SQUIRCLE_CORNER = 1.19f;
    private static final int LIGHT_TOP = 0xFFFAFAFC;
    private static final int LIGHT_BOTTOM = 0xFFE6E7EC;
    /** the dark theme: other apps' icons a little darker (KiroOS icons have dark twins) */
    private static final int DARK_DIM = 0x29000000;

    private final Context mContext;
    private final boolean mDark;
    private final int mSize;
    private final int mDensity;
    private final Path mSquircle = new Path();
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint mSheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLightPlate = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** fills the squircle with the square artwork, antialiased at the edge */
    private final Paint mShape = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final LruCache<String, Bitmap> mCache;

    IconFactory(Context context) {
        mContext = context;
        // the factory lives with the activity, which is recreated when the theme changes
        mDark = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        mSize = context.getResources().getDimensionPixelSize(R.dimen.icon_size);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        buildSquircle(mSquircle, mSize);
        // top-light: a faint white wash fading out by the middle of the icon
        mSheen.setShader(new LinearGradient(0, 0, 0, mSize * 0.6f,
                0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        mPlate.setShader(new LinearGradient(0, 0, 0, mSize,
                PLATE_TOP, PLATE_BOTTOM, Shader.TileMode.CLAMP));
        mLightPlate.setShader(new LinearGradient(0, 0, 0, mSize,
                LIGHT_TOP, LIGHT_BOTTOM, Shader.TileMode.CLAMP));
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

    /**
     * A folder: a frosted squircle with its first four apps in a 2 x 2 grid (the icon is
     * small enough to render on every bind, the apps' own icons are cached).
     */
    synchronized Drawable folderIcon(List<AppInfo> apps) {
        final Bitmap out = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        plate.setColor(0x4DFFFFFF);
        c.drawPath(mSquircle, plate);
        final float pad = mSize * 0.15f;
        final float gap = mSize * 0.07f;
        final float cell = (mSize - 2 * pad - gap) / 2f;
        final android.graphics.RectF dst = new android.graphics.RectF();
        for (int i = 0; i < apps.size() && i < 4; i++) {
            final float x = pad + (i % 2) * (cell + gap);
            final float y = pad + (i / 2) * (cell + gap);
            dst.set(x, y, x + cell, y + cell);
            c.drawBitmap(bitmap(apps.get(i)), null, dst, mPaint);
        }
        return new BitmapDrawable(mContext.getResources(), out);
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
            // the xxxhdpi icon: zoomed round icons stay sharp
            final boolean own = hasDarkTwin(app.info.getComponentName().getPackageName());
            b = render(app.info.getBadgedIcon(Math.max(mDensity, DisplayMetrics.DENSITY_XXXHIGH)),
                    mDark && !own, own);
            mCache.put(key, b);
        }
        return b;
    }

    /** the KiroOS suite and Settings ship their own dark-theme icons (drawable-night) */
    private static boolean hasDarkTwin(String packageName) {
        return packageName.startsWith("com.kiroos.") || "com.android.settings".equals(packageName);
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

    /**
     * Serialized: the main thread and the prewarm thread share the paints and the path.
     * own: a KiroOS icon, which has its own top light and rim (only clipped here).
     */
    private synchronized Bitmap render(Drawable source, boolean dim, boolean own) {
        final Bitmap raw = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas rc = new Canvas(raw);
        source.setBounds(0, 0, mSize, mSize);
        source.draw(rc);

        // the artwork is drawn square, then the squircle is filled with it through a bitmap
        // shader: an antialiased path edge (clipPath on a software canvas is stair-stepped)
        final Bitmap art = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(art);
        final float half = mSize / 2f;
        final float disc = fillsSquare(raw) ? 0 : discRadius(raw);
        if (fillsSquare(raw)) {
            drawScaled(c, source, 1f, 0, 0);
        } else if (disc > 0) {
            final int flat = discColor(raw);
            if (flat != 0) {
                // round icon with a solid background: grow the background to the squircle,
                // the symbol keeps its size (a little larger so the legacy drop shadow around
                // the disc falls outside)
                c.drawColor(flat);
                drawScaled(c, source, Math.min(1.25f, (half * 0.94f) / disc), 0, 0);
            } else if (roundedSquare(raw, disc)) {
                // a rounded square (Instagram, DevCheck): its sides meet the squircle's, and
                // its round corners are filled by stretching its own edge colours outwards,
                // so the artwork covers the whole icon without being cropped
                drawScaled(c, source, half / disc, 0, 0);
                extendEdges(art, 0);
            } else {
                // a disc with a picture or a gradient (Chrome, Telegram): its edge meets the
                // squircle's sides and the corners take the colour of the disc's edge along
                // each ray (a light rim around the disc, like Telegram's, is left out)
                final float inner = innerRadius(raw, disc);
                final float scale = half / inner;
                drawScaled(c, source, scale, 0, 0);
                extendEdges(art, inner * scale - 1.5f);
            }
        } else {
            // transparent or irregular icon: trimmed of its empty margins and centred on a
            // plate that contrasts with it (light plate, graphite for light icons)
            final Rect box = opaqueBounds(raw);
            if (lightness(raw) > 0.72f) {
                c.drawPaint(mPlate);
            } else {
                c.drawPaint(mLightPlate);
            }
            final float fit = box.isEmpty() ? INNER
                    : INNER_FIT * mSize / Math.max(box.width(), box.height());
            final float s = Math.min(fit, 1.4f);
            drawScaled(c, source, s, half - box.exactCenterX(), half - box.exactCenterY());
        }
        if (dim) {
            c.drawColor(DARK_DIM, android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
        if (!own) {
            c.drawRect(0, 0, mSize, mSize * 0.6f, mSheen);
        }
        final Bitmap out = Bitmap.createBitmap(mSize, mSize, Bitmap.Config.ARGB_8888);
        final Canvas oc = new Canvas(out);
        mShape.setShader(new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        oc.drawPath(mSquircle, mShape);
        mShape.setShader(null);
        art.recycle();
        if (!own) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(Math.max(1f, mSize / 96f));
            mPaint.setColor(RIM);
            oc.drawPath(mSquircle, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
        mPaint.setColor(Color.BLACK);
        raw.recycle();
        return out;
    }

    /**
     * Draws the icon scaled by s about the centre, after moving its content by (dx, dy): the
     * drawable itself is drawn (not the analysis bitmap) so zoomed icons stay sharp.
     */
    private void drawScaled(Canvas c, Drawable d, float s, float dx, float dy) {
        final float half = mSize / 2f;
        c.save();
        c.scale(s, s, half, half);
        c.translate(dx, dy);
        d.setBounds(0, 0, mSize, mSize);
        d.draw(c);
        c.restore();
    }

    /**
     * The radius of a round opaque icon (a disc centred in its square), 0 when the icon is
     * not one: measured along the four axes, which must agree, with transparent corners.
     */
    private static float discRadius(Bitmap b) {
        final int w = b.getWidth();
        final int c = w / 2;
        final int m = Math.round(w * 0.1f);
        if (Color.alpha(b.getPixel(m, m)) > 40 || Color.alpha(b.getPixel(w - 1 - m, w - 1 - m)) > 40
                || Color.alpha(b.getPixel(c, c)) < 250) {
            return 0;
        }
        final int[] r = new int[4];
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int d = 0; d < 4; d++) {
            int i = 0;
            while (i < c - 1 && Color.alpha(b.getPixel(c + dirs[d][0] * i, c + dirs[d][1] * i)) > 200) {
                i++;
            }
            r[d] = i;
        }
        int min = r[0];
        int max = r[0];
        for (int v : r) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (min < w * 0.3f || max - min > w * 0.04f) {
            return 0;
        }
        // the ring just inside the edge must be opaque all round (not a ring of shapes)
        final float ring = min * 0.9f;
        for (int i = 0; i < 24; i++) {
            final double t = 2 * Math.PI * i / 24;
            final int x = Math.round(c + (float) Math.cos(t) * ring);
            final int y = Math.round(c + (float) Math.sin(t) * ring);
            if (Color.alpha(b.getPixel(x, y)) < 240) {
                return 0;
            }
        }
        return (min + max) / 2f;
    }

    /**
     * The radius where a disc's own colour ends: some round icons have a white or light ring
     * around the coloured disc (Telegram), which must not end up in the squircle's corners.
     * Walks out from 60 % of the disc on the four axes and stops at a sharp colour change.
     */
    private static float innerRadius(Bitmap b, float disc) {
        final int c = b.getWidth() / 2;
        final int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        float inner = disc;
        for (int[] d : dirs) {
            final int from = Math.round(disc * 0.6f);
            final int ref = b.getPixel(c + d[0] * from, c + d[1] * from);
            for (int i = from; i < disc; i++) {
                final int p = b.getPixel(c + d[0] * i, c + d[1] * i);
                final int diff = Math.abs(Color.red(p) - Color.red(ref))
                        + Math.abs(Color.green(p) - Color.green(ref))
                        + Math.abs(Color.blue(p) - Color.blue(ref));
                if (diff > 120) {
                    inner = Math.min(inner, i);
                    break;
                }
            }
        }
        // a real inner ring is thin: anything smaller is the artwork, not a rim
        return inner >= disc * 0.8f ? inner : disc;
    }

    /**
     * Fills the icon to its corners with its own edge: every pixel beyond the edge takes the
     * colour found going back toward the centre. edge > 0: a disc of that radius (pixels
     * past it are replaced, a rim included); edge = 0: the first opaque pixel inward (only
     * transparent or half-transparent pixels are filled).
     */
    private static void extendEdges(Bitmap b, float edge) {
        final int w = b.getWidth();
        final int h = b.getHeight();
        final int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        final int[] out = px.clone();
        final float cx = (w - 1) / 2f;
        final float cy = (h - 1) / 2f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int i = y * w + x;
                final float dx = x - cx;
                final float dy = y - cy;
                final float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d < 1) {
                    continue;
                }
                final float ux = dx / d;
                final float uy = dy / d;
                int src = 0;
                if (edge > 0) {
                    if (d <= edge) {
                        continue;
                    }
                    src = sample(px, w, h, cx + ux * edge, cy + uy * edge);
                    out[i] = src | 0xFF000000;
                    continue;
                }
                if (Color.alpha(px[i]) >= 250) {
                    continue;
                }
                for (float r = d; r > 0; r -= 1f) {
                    final int p = sample(px, w, h, cx + ux * r, cy + uy * r);
                    if (Color.alpha(p) >= 250) {
                        // two pixels further in: past the antialiased fringe
                        src = sample(px, w, h, cx + ux * Math.max(0, r - 2), cy + uy * Math.max(0, r - 2));
                        break;
                    }
                }
                if (src == 0) {
                    continue;
                }
                // what is there (a soft edge) lies over the stretched colour
                final int a = Color.alpha(px[i]);
                final int r = (Color.red(px[i]) * a + Color.red(src) * (255 - a)) / 255;
                final int g = (Color.green(px[i]) * a + Color.green(src) * (255 - a)) / 255;
                final int bl = (Color.blue(px[i]) * a + Color.blue(src) * (255 - a)) / 255;
                out[i] = Color.rgb(r, g, bl);
            }
        }
        b.setPixels(out, 0, w, 0, 0, w, h);
    }

    private static int sample(int[] px, int w, int h, float x, float y) {
        final int xi = Math.max(0, Math.min(w - 1, Math.round(x)));
        final int yi = Math.max(0, Math.min(h - 1, Math.round(y)));
        return px[yi * w + xi];
    }

    /** a rounded square, not a disc: opaque on the diagonal past the disc's radius */
    private static boolean roundedSquare(Bitmap b, float disc) {
        final int c = b.getWidth() / 2;
        final int d = Math.round(disc * 0.8f); // 0.8 r on each axis = 1.13 r away
        return Color.alpha(b.getPixel(c + d, c + d)) > 200
                && Color.alpha(b.getPixel(c - d, c - d)) > 200
                && Color.alpha(b.getPixel(c + d, c - d)) > 200
                && Color.alpha(b.getPixel(c - d, c + d)) > 200;
    }

    /** the largest channel difference between colours on a circle of radius r */
    private static int ringSpread(Bitmap b, float r) {
        final float c = b.getWidth() / 2f;
        int minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;
        for (int i = 0; i < 32; i++) {
            final double t = 2 * Math.PI * i / 32;
            final int p = b.getPixel(Math.round(c + (float) Math.cos(t) * r),
                    Math.round(c + (float) Math.sin(t) * r));
            minR = Math.min(minR, Color.red(p));
            maxR = Math.max(maxR, Color.red(p));
            minG = Math.min(minG, Color.green(p));
            maxG = Math.max(maxG, Color.green(p));
            minB = Math.min(minB, Color.blue(p));
            maxB = Math.max(maxB, Color.blue(p));
        }
        return Math.max(maxR - minR, Math.max(maxG - minG, maxB - minB));
    }

    /** the mean colour on a circle of radius r around the centre */
    private static int ringColor(Bitmap b, float r) {
        final float c = b.getWidth() / 2f;
        long red = 0;
        long green = 0;
        long blue = 0;
        for (int i = 0; i < 32; i++) {
            final double t = 2 * Math.PI * i / 32;
            final int p = b.getPixel(Math.round(c + (float) Math.cos(t) * r),
                    Math.round(c + (float) Math.sin(t) * r));
            red += Color.red(p);
            green += Color.green(p);
            blue += Color.blue(p);
        }
        return Color.rgb((int) (red / 32), (int) (green / 32), (int) (blue / 32));
    }

    /** the box around the visible pixels */
    private static Rect opaqueBounds(Bitmap b) {
        final int w = b.getWidth();
        final int h = b.getHeight();
        int l = w;
        int t = h;
        int r = -1;
        int btm = -1;
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (Color.alpha(row[x]) > 24) {
                    l = Math.min(l, x);
                    r = Math.max(r, x);
                    t = Math.min(t, y);
                    btm = Math.max(btm, y);
                }
            }
        }
        return r < 0 ? new Rect() : new Rect(l, t, r + 1, btm + 1);
    }

    /** mean lightness (0-1) of the visible pixels, weighted by their opacity */
    private static float lightness(Bitmap b) {
        final int w = b.getWidth();
        final int[] px = new int[w * b.getHeight()];
        b.getPixels(px, 0, w, 0, 0, w, b.getHeight());
        double sum = 0;
        double weight = 0;
        for (int p : px) {
            final int a = Color.alpha(p);
            if (a < 24) {
                continue;
            }
            sum += a * (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
            weight += a;
        }
        return weight == 0 ? 0 : (float) (sum / weight / 255);
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
