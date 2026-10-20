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

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Kiro Home Beta 5: frosted glass. The wallpaper, as it lies behind the home, blurred once (a
 * small copy blurred on the GPU, made again only when the wallpaper changes) and shared by
 * every glass surface of the home: the app drawer's sheet (this view, fixed to the screen while
 * the sheet moves over it), the open folder's card and the widget picker (GlassDrawable, a
 * background that shows the blurred wallpaper exactly behind its view). Nothing is blurred
 * per frame.
 */
final class Glass extends View {

    /** the blurred copy is this much smaller than the screen (then blurred, then scaled up) */
    private static final int DOWNSCALE = 8;
    /** the tint over the glass, so white text and icons on it always read */
    static final int TINT = 0x9E0C0D10;

    // --- the blurred wallpaper, shared --------------------------------------------------------

    private static Bitmap sBlur;
    private static boolean sMaking;
    private static final List<Runnable> sListeners = new ArrayList<>();

    /** the blurred wallpaper, or null while it is being made (or there is no bitmap wallpaper) */
    static Bitmap blurred() {
        return sBlur;
    }

    /** called on the main thread each time a new blurred wallpaper is ready */
    static void listen(Runnable r) {
        if (!sListeners.contains(r)) sListeners.add(r);
    }

    static void unlisten(Runnable r) {
        sListeners.remove(r);
    }

    /** makes the blurred wallpaper again (now, and whenever the wallpaper changes) */
    static void refresh(Context context) {
        if (sMaking) return;
        sMaking = true;
        final Context c = context.getApplicationContext();
        final DisplayMetrics dm = c.getResources().getDisplayMetrics();
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... v) {
                return make(c, dm.widthPixels, dm.heightPixels);
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                sMaking = false;
                if (b == null) return;
                sBlur = b;
                for (Runnable r : new ArrayList<>(sListeners)) r.run();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** the part of the wallpaper behind the screen (centred), small and blurred */
    private static Bitmap make(Context c, int screenW, int screenH) {
        Drawable d;
        try {
            d = WallpaperManager.getInstance(c).getDrawable();
        } catch (RuntimeException e) {
            return null;
        }
        if (!(d instanceof BitmapDrawable)) return null;
        final Bitmap wall = ((BitmapDrawable) d).getBitmap();
        if (wall == null || wall.getWidth() == 0 || wall.getHeight() == 0) return null;
        // the wallpaper is scaled to the screen's height and centred (the home keeps it still)
        final float scale = screenH / (float) wall.getHeight();
        final float visibleW = Math.min(wall.getWidth(), screenW / scale);
        final int left = Math.round((wall.getWidth() - visibleW) / 2f);
        final Rect src = new Rect(left, 0, left + Math.round(visibleW), wall.getHeight());
        final int w = Math.max(1, screenW / DOWNSCALE);
        final int h = Math.max(1, screenH / DOWNSCALE);
        final Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(wall, src, new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        RenderScript rs = null;
        try {
            rs = RenderScript.create(c);
            final Allocation in = Allocation.createFromBitmap(rs, small);
            final Allocation out = Allocation.createTyped(rs, in.getType());
            final ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            // twice at the largest radius: soft like frosted glass, no shapes left
            blur.setRadius(25f);
            blur.setInput(in);
            blur.forEach(out);
            blur.setInput(out);
            blur.forEach(in);
            in.copyTo(small);
            in.destroy();
            out.destroy();
            blur.destroy();
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (rs != null) rs.destroy();
        }
        return small;
    }

    // --- the drawer's glass: a full-screen view, drawn under the sheet ------------------------

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint mTint = new Paint();
    private final Path mClip = new Path();
    private final RectF mRect = new RectF();
    private final Rect mDst = new Rect();
    private final float mRadius;
    private float mTop;
    private final Runnable mOnReady = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    Glass(Context context) {
        super(context);
        mRadius = 28 * getResources().getDisplayMetrics().density;
        mTint.setColor(TINT);
        setWillNotDraw(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        listen(mOnReady);
    }

    @Override
    protected void onDetachedFromWindow() {
        unlisten(mOnReady);
        super.onDetachedFromWindow();
    }

    /** where the sheet's top edge is on screen now (it covers the rest down to the bottom) */
    void setSheetTop(float top) {
        if (top != mTop) {
            mTop = top;
            invalidate();
        }
    }

    boolean ready() {
        return sBlur != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mTop >= getHeight()) return;
        mRect.set(0, mTop, getWidth(), getHeight() + mRadius);
        mClip.reset();
        mClip.addRoundRect(mRect, mRadius, mRadius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mClip);
        final Bitmap b = sBlur;
        if (b != null) {
            mDst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(b, null, mDst, mPaint);
        }
        canvas.drawRect(0, mTop, getWidth(), getHeight(), mTint);
        canvas.restore();
    }

    // --- glass as a background: a card, a sheet ------------------------------------------------

    /**
     * A rounded background of frosted glass for a view: the blurred wallpaper that lies exactly
     * behind the view on screen, tinted. Without a blurred wallpaper (yet), a solid fallback.
     * Give the view setClipToOutline(true) so its content keeps the round corners.
     */
    static final class GlassDrawable extends Drawable {
        private final View mHost;
        private final float[] mRadii;
        private final int mFallback;
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint mTint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mRect = new RectF();
        private final Rect mDst = new Rect();
        private final int[] mLoc = new int[2];
        private final Runnable mOnReady = new Runnable() {
            @Override
            public void run() {
                invalidateSelf();
            }
        };

        /** radii: top-left, top-right, bottom-right, bottom-left (px) */
        GlassDrawable(View host, float tl, float tr, float br, float bl, int fallback) {
            mHost = host;
            mRadii = new float[] {tl, tl, tr, tr, br, br, bl, bl};
            mFallback = fallback;
            mTint.setColor(TINT);
            listen(mOnReady);
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    listen(mOnReady);
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                    unlisten(mOnReady);
                }
            });
            host.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    // the round corners of the top (a sheet) or of all four (a card)
                    final float r = Math.max(mRadii[0], mRadii[4]);
                    if (mRadii[4] == 0f) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight() + Math.round(r), r);
                    } else {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                    }
                }
            });
        }

        @Override
        public void draw(Canvas canvas) {
            final Rect b = getBounds();
            mRect.set(b);
            mPath.reset();
            mPath.addRoundRect(mRect, mRadii, Path.Direction.CW);
            final Bitmap blur = sBlur;
            if (blur == null) {
                mTint.setColor(mFallback);
                canvas.drawPath(mPath, mTint);
                mTint.setColor(TINT);
                return;
            }
            // the wallpaper's blurred copy, placed so the part behind this view shows in it
            mHost.getLocationOnScreen(mLoc);
            final DisplayMetrics dm = mHost.getResources().getDisplayMetrics();
            final float sx = mHost.getScaleX() == 0 ? 1f : mHost.getScaleX();
            final float sy = mHost.getScaleY() == 0 ? 1f : mHost.getScaleY();
            mDst.set(Math.round(-mLoc[0] / sx), Math.round(-mLoc[1] / sy),
                    Math.round((dm.widthPixels - mLoc[0]) / sx),
                    Math.round((dm.heightPixels - mLoc[1]) / sy));
            canvas.save();
            canvas.clipPath(mPath);
            canvas.drawBitmap(blur, null, mDst, mPaint);
            canvas.drawPath(mPath, mTint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            mPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
