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

package com.kiroos.atlas;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The map: tiles drawn by TileRenderer on worker threads, shown as pictures, so panning and
 * pinching only move pictures and stay at 60 fps; while a tile is prepared, its parent at a lower
 * zoom stands in. Labels are placed per frame, the most important first, without overlaps.
 */
public class MapView extends View {

    interface Listener {
        /** the user moved the map by hand (navigation stops following) */
        void onUserMoved();
        void onLongPress(double lat, double lon);
        void onTap(double lat, double lon);
    }

    static final double MIN_ZOOM = 2, MAX_ZOOM = 19;

    private final float mDensity;
    /** a tile's size on screen at its own zoom: 192 dp, drawn 1:1 */
    private final int mTileSize;
    private final TileStore mStore;
    /**
     * One renderer per worker: a renderer reuses its Path and Paints, and two threads drawing
     * with one Path left Skia iterating a path being rebuilt (endless loop in its convexity check)
     */
    private final ThreadLocal<TileRenderer> mRenderer;
    private final ExecutorService mWorkers = Executors.newFixedThreadPool(2);
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Set<String> mLoading = new HashSet<>();
    private final LruCache<String, TileRenderer.Result> mTiles;
    /** decoded data tiles; a low zoom one is large (600 KB of protobuf at zoom 5) */
    private final LruCache<String, Mvt> mData = new LruCache<>(8);
    /** the tiles on screen (zoom, x0, y0, x1, y1), for the workers to skip ones gone far away */
    private volatile int[] mVisible = new int[5];

    // camera: center in world units, fractional zoom
    private double mX = Geo.x(12.4964), mY = Geo.y(41.9028), mZoom = 5.5;

    private final GestureDetector mGestures;
    private final ScaleGestureDetector mScale;
    private final OverScroller mScroller;
    private ValueAnimator mAnimator;
    private Listener mListener;

    // overlays
    private double mLocLat = Double.NaN, mLocLon, mLocAccuracy;
    private double mPinLat = Double.NaN, mPinLon;
    private List<double[]> mRoute;
    private final Path mRoutePath = new Path();

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRoutePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRouteCasing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    public MapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mTileSize = Math.round(192 * mDensity);
        mStore = new TileStore(context);
        // the map follows the system theme like the rest of the suite (values / values-notnight)
        final TileRenderer.Style style =
                new TileRenderer.Style(getResources().getBoolean(R.bool.atlas_dark));
        mRenderer = new ThreadLocal<TileRenderer>() {
            @Override
            protected TileRenderer initialValue() {
                return new TileRenderer(mDensity, mTileSize, style);
            }
        };
        final int memory = (int) Math.min(96L << 20, Runtime.getRuntime().maxMemory() / 3);
        mTiles = new LruCache<String, TileRenderer.Result>(memory) {
            @Override
            protected int sizeOf(String key, TileRenderer.Result value) {
                return value.bitmap.getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, TileRenderer.Result old,
                    TileRenderer.Result fresh) {
                // a picture may still be on screen this frame: the GC takes it
            }
        };
        mScroller = new OverScroller(context);
        mGestures = new GestureDetector(context, new Gestures());
        mScale = new ScaleGestureDetector(context, new Pinch());
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        mHalo.setTextAlign(Paint.Align.CENTER);
        mHalo.setTypeface(mText.getTypeface());
        mHalo.setStyle(Paint.Style.STROKE);
        mHalo.setStrokeWidth(3 * mDensity);
        mHalo.setColor(style.halo);
        mRoutePaint.setStyle(Paint.Style.STROKE);
        mRoutePaint.setStrokeCap(Paint.Cap.ROUND);
        mRoutePaint.setStrokeJoin(Paint.Join.ROUND);
        mRoutePaint.setColor(style.route);
        mRoutePaint.setStrokeWidth(5 * mDensity);
        mRouteCasing.set(mRoutePaint);
        mRouteCasing.setColor(style.routeCasing);
        mRouteCasing.setStrokeWidth(8 * mDensity);
        setBackgroundColor(style.background);
        mWorkers.execute(new Runnable() {
            @Override
            public void run() {
                if (mStore.refreshTemplate()) postInvalidate();
            }
        });
    }

    /** tiles that could not be loaded (offline) are asked for again while on screen */
    private final Runnable mRetry = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    void setListener(Listener l) {
        mListener = l;
    }

    // ---- camera

    double zoom() {
        return mZoom;
    }

    double centerLat() {
        return Geo.lat(mY);
    }

    double centerLon() {
        return Geo.lon(mX);
    }

    private double worldPx() {
        return mTileSize * Math.pow(2, mZoom);
    }

    void setCamera(double lat, double lon, double zoom) {
        cancelMotion();
        mX = wrapX(Geo.x(lon));
        mY = clampY(Geo.y(lat));
        mZoom = clampZoom(zoom);
        invalidate();
    }

    /** a smooth move (and zoom) to a place */
    void animateTo(double lat, double lon, double zoom) {
        cancelMotion();
        final double x0 = mX, y0 = mY, z0 = mZoom;
        final double x1 = Geo.x(lon), y1 = Geo.y(lat), z1 = clampZoom(zoom);
        mAnimator = ValueAnimator.ofFloat(0f, 1f);
        mAnimator.setDuration(650);
        mAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                final double t = (Float) a.getAnimatedValue();
                mX = x0 + (x1 - x0) * t;
                mY = y0 + (y1 - y0) * t;
                mZoom = z0 + (z1 - z0) * t;
                invalidate();
            }
        });
        mAnimator.start();
    }

    private boolean mTilted;

    /**
     * A slight 3D view for your position and navigation: the map leans back around its middle
     * (and is scaled up so its far corners still fill the screen). Touches go through the
     * view's matrix, so dragging and pinching keep working.
     */
    void setTilt(boolean on) {
        if (on == mTilted) return;
        mTilted = on;
        setCameraDistance(9000 * mDensity);
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight() / 2f);
        animate().rotationX(on ? 34f : 0f).scaleX(on ? 1.32f : 1f).scaleY(on ? 1.32f : 1f)
                .setDuration(500).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    boolean tilted() {
        return mTilted;
    }

    private void cancelMotion() {
        if (mAnimator != null) mAnimator.cancel();
        mScroller.forceFinished(true);
    }

    private static double clampZoom(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    private static double wrapX(double x) {
        return x - Math.floor(x);
    }

    private static double clampY(double y) {
        return Math.max(0, Math.min(1, y));
    }

    /** world units of a screen point */
    private double[] world(float sx, float sy) {
        final double w = worldPx();
        return new double[] {mX + (sx - getWidth() / 2.0) / w, mY + (sy - getHeight() / 2.0) / w};
    }

    private float screenX(double wx) {
        double dx = wx - mX;
        if (dx > 0.5) dx -= 1;
        if (dx < -0.5) dx += 1;
        return (float) (dx * worldPx() + getWidth() / 2.0);
    }

    private float screenY(double wy) {
        return (float) ((wy - mY) * worldPx() + getHeight() / 2.0);
    }

    // ---- overlays

    void setLocation(double lat, double lon, double accuracy) {
        mLocLat = lat;
        mLocLon = lon;
        mLocAccuracy = accuracy;
        invalidate();
    }

    void setPin(double lat, double lon) {
        mPinLat = lat;
        mPinLon = lon;
        invalidate();
    }

    void clearPin() {
        mPinLat = Double.NaN;
        invalidate();
    }

    void setRoute(List<double[]> latLons) {
        mRoute = latLons;
        invalidate();
    }

    /** the zoom that shows all these points */
    void fit(List<double[]> latLons) {
        if (latLons == null || latLons.isEmpty() || getWidth() == 0) return;
        double minX = 1, maxX = 0, minY = 1, maxY = 0;
        for (double[] p : latLons) {
            final double x = Geo.x(p[1]), y = Geo.y(p[0]);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        final double spanX = Math.max(maxX - minX, 1e-7), spanY = Math.max(maxY - minY, 1e-7);
        final double z = Math.min(Math.log(getWidth() * 0.8 / (spanX * mTileSize)),
                Math.log(getHeight() * 0.6 / (spanY * mTileSize))) / Math.log(2);
        animateTo(Geo.lat((minY + maxY) / 2), Geo.lon((minX + maxX) / 2), Math.min(z, 17));
    }

    // ---- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (mScroller.computeScrollOffset()) {
            final double w = worldPx();
            mX = wrapX(mScroller.getCurrX() / w);
            mY = clampY(mScroller.getCurrY() / w);
            postInvalidateOnAnimation();
        }
        final int tz = (int) Math.max(0, Math.min(MAX_ZOOM, Math.round(mZoom)));
        final double n = Math.pow(2, tz);
        final double size = mTileSize * Math.pow(2, mZoom - tz);
        final double w = worldPx();
        final double left = mX - getWidth() / 2.0 / w, top = mY - getHeight() / 2.0 / w;
        final int x0 = (int) Math.floor(left * n), y0 = (int) Math.floor(top * n);
        final int x1 = (int) Math.floor((left + getWidth() / w) * n);
        final int y1 = (int) Math.floor((top + getHeight() / w) * n);
        final List<TileRenderer.Result> shown = new ArrayList<>();
        final int max = (int) n;
        mVisible = new int[] {tz, x0, y0, x1, y1};
        for (int ty = Math.max(0, y0); ty <= Math.min(max - 1, y1); ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                final int wx = ((tx % max) + max) % max;
                final float sx = (float) ((tx / n - left) * w), sy = (float) ((ty / n - top) * w);
                mDst.set(sx, sy, (float) (sx + size), (float) (sy + size));
                final TileRenderer.Result t = mTiles.get(key(tz, wx, ty));
                if (t != null) {
                    canvas.drawBitmap(t.bitmap, null, mDst, mBitmapPaint);
                    shown.add(t);
                } else {
                    request(tz, wx, ty);
                    drawParent(canvas, tz, wx, ty, shown);
                }
            }
        }
        drawRoute(canvas);
        drawLabels(canvas, shown);
        drawPin(canvas);
        drawLocation(canvas);
    }

    /** a lower-zoom picture of the area while the tile is prepared */
    private void drawParent(Canvas canvas, int z, int x, int y, List<TileRenderer.Result> shown) {
        for (int k = 1; k <= 5 && z - k >= 0; k++) {
            final TileRenderer.Result p = mTiles.get(key(z - k, x >> k, y >> k));
            if (p == null) continue;
            final int part = mTileSize >> k;
            final int px = (x - ((x >> k) << k)) * part, py = (y - ((y >> k) << k)) * part;
            mSrc.set(px, py, px + part, py + part);
            canvas.drawBitmap(p.bitmap, mSrc, mDst, mBitmapPaint);
            if (!shown.contains(p)) shown.add(p);
            return;
        }
    }

    private static String key(int z, int x, int y) {
        return z + "/" + x + "/" + y;
    }

    private void request(final int z, final int x, final int y) {
        final String k = key(z, x, y);
        if (!mLoading.add(k)) return;
        mWorkers.execute(new Runnable() {
            @Override
            public void run() {
                TileRenderer.Result r = null;
                try {
                    // a tile the map moved away from (or zoomed past) before its turn is dropped
                    if (stillNear(z, x, y)) r = load(z, x, y);
                } catch (Throwable e) {
                    android.util.Log.w("Atlas", "tile " + k, e);
                }
                final TileRenderer.Result result = r;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mLoading.remove(k);
                        if (result != null) {
                            mTiles.put(k, result);
                            invalidate();
                        } else {
                            mMain.removeCallbacks(mRetry);
                            mMain.postDelayed(mRetry, 5000);
                        }
                    }
                });
            }
        });
    }

    private boolean stillNear(int z, int x, int y) {
        final int[] v = mVisible;
        if (v[0] != z) return false;
        final int max = 1 << z;
        // x wraps around the world
        boolean inX = false;
        for (int tx = v[1] - 1; tx <= v[3] + 1 && !inX; tx++) {
            inX = ((tx % max) + max) % max == x;
        }
        return inX && y >= v[2] - 1 && y <= v[4] + 1;
    }

    private TileRenderer.Result load(int z, int x, int y) {
        final int dataZ = Math.min(z, TileStore.MAX_DATA_ZOOM);
        final int sub = z - dataZ;
        final int dx = x >> sub, dy = y >> sub;
        final String dk = key(dataZ, dx, dy);
        Mvt data;
        synchronized (mData) {
            data = mData.get(dk);
        }
        // timings with `setprop log.tag.Atlas DEBUG`
        final boolean debug = android.util.Log.isLoggable("Atlas", android.util.Log.DEBUG);
        final long t0 = android.os.SystemClock.uptimeMillis();
        long t1 = t0, t2 = t0;
        int size = 0;
        if (data == null) {
            byte[] bytes = mStore.cached(dataZ, dx, dy);
            if (bytes == null || mStore.stale(dataZ, dx, dy)) {
                final byte[] fresh = mStore.fetch(dataZ, dx, dy);
                if (fresh != null) bytes = fresh;
            }
            if (bytes == null) return null;
            size = bytes.length;
            t1 = android.os.SystemClock.uptimeMillis();
            data = Mvt.decode(bytes);
            t2 = android.os.SystemClock.uptimeMillis();
            synchronized (mData) {
                mData.put(dk, data);
            }
        }
        final TileRenderer.Result r = mRenderer.get().render(data, z, x, y, dataZ);
        if (debug) {
            android.util.Log.d("Atlas", "tile " + key(z, x, y) + ": " + size + " B, load "
                    + (t1 - t0) + " ms, decode " + (t2 - t1) + " ms, draw "
                    + (android.os.SystemClock.uptimeMillis() - t2) + " ms");
        }
        return r;
    }

    private void drawRoute(Canvas canvas) {
        if (mRoute == null || mRoute.size() < 2) return;
        // Zoomed in, the far points of a route are millions of pixels away and the hardware
        // renderer drops the whole path ("path too large"): the line vanished up close. Only
        // the segments near the screen are drawn, cut at a margin around it.
        mRoutePath.reset();
        final double m = 64 * mDensity;
        final double minX = -m, minY = -m, maxX = getWidth() + m, maxY = getHeight() + m;
        final double w = worldPx();
        final double left = mX - getWidth() / 2.0 / w, top = mY - getHeight() / 2.0 / w;
        double px = 0, py = 0;
        boolean have = false, open = false;
        float lastX = Float.NaN, lastY = Float.NaN;
        for (double[] p : mRoute) {
            final double x = (Geo.x(p[1]) - left) * w, y = (Geo.y(p[0]) - top) * w;
            if (have) {
                final double[] c = clip(px, py, x, y, minX, minY, maxX, maxY);
                if (c == null) {
                    open = false;
                } else {
                    final float ax = (float) c[0], ay = (float) c[1];
                    final float bx = (float) c[2], by = (float) c[3];
                    if (!open || ax != lastX || ay != lastY) mRoutePath.moveTo(ax, ay);
                    mRoutePath.lineTo(bx, by);
                    lastX = bx;
                    lastY = by;
                    // the segment left the area at its end: the next one starts anew
                    open = c[2] == x && c[3] == y;
                }
            }
            px = x;
            py = y;
            have = true;
        }
        canvas.drawPath(mRoutePath, mRouteCasing);
        canvas.drawPath(mRoutePath, mRoutePaint);
    }

    /** Liang–Barsky: the part of the segment inside the rectangle, or null */
    private static double[] clip(double x0, double y0, double x1, double y1,
            double minX, double minY, double maxX, double maxY) {
        final double dx = x1 - x0, dy = y1 - y0;
        double t0 = 0, t1 = 1;
        final double[] p = {-dx, dx, -dy, dy};
        final double[] q = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
            } else {
                final double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return null;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return null;
                    if (r < t1) t1 = r;
                }
            }
        }
        return new double[] {
                t0 == 0 ? x0 : x0 + t0 * dx, t0 == 0 ? y0 : y0 + t0 * dy,
                t1 == 1 ? x1 : x0 + t1 * dx, t1 == 1 ? y1 : y0 + t1 * dy};
    }

    private void drawLabels(Canvas canvas, List<TileRenderer.Result> shown) {
        final List<TileRenderer.Label> all = new ArrayList<>();
        final Map<String, Boolean> seen = new HashMap<>();
        for (TileRenderer.Result r : shown) {
            for (TileRenderer.Label l : r.labels) {
                final String k = l.text + "@" + Math.round(l.x * 1e5) + "," + Math.round(l.y * 1e5);
                if (seen.put(k, Boolean.TRUE) == null) all.add(l);
            }
        }
        Collections.sort(all, new Comparator<TileRenderer.Label>() {
            @Override
            public int compare(TileRenderer.Label a, TileRenderer.Label b) {
                return Integer.compare(a.priority, b.priority);
            }
        });
        final List<RectF> taken = new ArrayList<>();
        final float pad = 4 * mDensity;
        for (TileRenderer.Label l : all) {
            final float sx = screenX(l.x), sy = screenY(l.y);
            if (sx < -100 || sy < -50 || sx > getWidth() + 100 || sy > getHeight() + 50) continue;
            mText.setTextSize(l.size);
            final float half = mText.measureText(l.text) / 2;
            final RectF r = new RectF(sx - half - pad, sy - l.size - pad, sx + half + pad, sy + pad);
            boolean free = true;
            for (RectF t : taken) {
                if (RectF.intersects(t, r)) {
                    free = false;
                    break;
                }
            }
            if (!free) continue;
            taken.add(r);
            mHalo.setTextSize(l.size);
            canvas.drawText(l.text, sx, sy, mHalo);
            mText.setColor(l.color);
            canvas.drawText(l.text, sx, sy, mText);
        }
    }

    private void drawPin(Canvas canvas) {
        if (Double.isNaN(mPinLat)) return;
        final float sx = screenX(Geo.x(mPinLon)), sy = screenY(Geo.y(mPinLat));
        final float r = 9 * mDensity;
        mDot.setStyle(Paint.Style.FILL);
        mDot.setColor(0xFFFF453A);
        canvas.drawCircle(sx, sy - r * 1.6f, r, mDot);
        final Path stem = new Path();
        stem.moveTo(sx - r * 0.7f, sy - r * 1.2f);
        stem.lineTo(sx, sy);
        stem.lineTo(sx + r * 0.7f, sy - r * 1.2f);
        stem.close();
        canvas.drawPath(stem, mDot);
        mDot.setColor(0xFFFFFFFF);
        canvas.drawCircle(sx, sy - r * 1.6f, r * 0.38f, mDot);
    }

    private void drawLocation(Canvas canvas) {
        if (Double.isNaN(mLocLat)) return;
        final float sx = screenX(Geo.x(mLocLon)), sy = screenY(Geo.y(mLocLat));
        // the accuracy circle in meters at this latitude
        final double metersPerPx = 40075016.686 * Math.cos(Math.toRadians(mLocLat)) / worldPx();
        final float acc = (float) (mLocAccuracy / metersPerPx);
        mDot.setStyle(Paint.Style.FILL);
        if (acc > 8 * mDensity) {
            mDot.setColor(0x223987E5);
            canvas.drawCircle(sx, sy, acc, mDot);
        }
        mDot.setColor(0xFFFFFFFF);
        canvas.drawCircle(sx, sy, 9 * mDensity, mDot);
        mDot.setColor(0xFF3987E5);
        canvas.drawCircle(sx, sy, 6.5f * mDensity, mDot);
    }

    // ---- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled = mScale.onTouchEvent(e);
        if (!mScale.isInProgress()) handled |= mGestures.onTouchEvent(e);
        return handled || super.onTouchEvent(e);
    }

    private void moved() {
        if (mListener != null) mListener.onUserMoved();
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            cancelMotion();
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
            final double w = worldPx();
            mX = wrapX(mX + dx / w);
            mY = clampY(mY + dy / w);
            moved();
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
            final double w = worldPx();
            mScroller.fling((int) (mX * w), (int) (mY * w), (int) -vx, (int) -vy,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, 0, (int) w);
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            final double[] p = world(e.getX(), e.getY());
            // zoom in by one level towards the tapped point
            final double z1 = clampZoom(mZoom + 1);
            final double f = 1 - Math.pow(2, mZoom - z1);
            animateTo(Geo.lat(mY + (p[1] - mY) * f), Geo.lon(mX + (p[0] - mX) * f), z1);
            moved();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (mListener != null) {
                final double[] p = world(e.getX(), e.getY());
                mListener.onTap(Geo.lat(p[1]), Geo.lon(p[0]));
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (mListener != null) {
                final double[] p = world(e.getX(), e.getY());
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                mListener.onLongPress(Geo.lat(p[1]), Geo.lon(wrapX(p[0])));
            }
        }
    }

    private final class Pinch extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector d) {
            final double z1 = clampZoom(mZoom + Math.log(d.getScaleFactor()) / Math.log(2));
            // the point under the fingers stays under them
            final double[] before = world(d.getFocusX(), d.getFocusY());
            mZoom = z1;
            final double[] after = world(d.getFocusX(), d.getFocusY());
            mX = wrapX(mX + before[0] - after[0]);
            mY = clampY(mY + before[1] - after[1]);
            // zoomed far out the lean only hides the map: flat again
            if (mTilted && mZoom < 13) setTilt(false);
            moved();
            invalidate();
            return true;
        }
    }

    void release() {
        mWorkers.shutdownNow();
    }
}
