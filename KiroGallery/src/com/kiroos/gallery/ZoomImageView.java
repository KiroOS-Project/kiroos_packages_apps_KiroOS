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

package com.kiroos.gallery;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Photo view with pinch zoom (up to 6×), double-tap zoom and pan, clamped to the image
 * edges. {@link #isZoomed()} lets the pager decide who gets a horizontal swipe.
 */
public class ZoomImageView extends ImageView {

    interface OnTapListener {
        void onTap();
    }

    private static final float MAX_SCALE = 6f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private final Matrix mBase = new Matrix();
    private final Matrix mUser = new Matrix();
    private final Matrix mDraw = new Matrix();
    private final float[] mValues = new float[9];
    private final ScaleGestureDetector mScale;
    private final GestureDetector mGestures;
    private OnTapListener mTapListener;

    public ZoomImageView(Context context) {
        this(context, null);
    }

    public ZoomImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        mScale = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        final float cur = userScale();
                        float f = d.getScaleFactor();
                        if (cur * f > MAX_SCALE) {
                            f = MAX_SCALE / cur;
                        }
                        mUser.postScale(f, f, d.getFocusX(), d.getFocusY());
                        clamp();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        if (userScale() < 1f) {
                            animateTo(1f, getWidth() / 2f, getHeight() / 2f);
                        }
                    }
                });
        mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mTapListener != null) {
                    mTapListener.onTap();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateTo(isZoomed() ? 1f : DOUBLE_TAP_SCALE, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                if (isZoomed()) {
                    mUser.postTranslate(-dx, -dy);
                    clamp();
                }
                return true;
            }
        });
    }

    void setOnTapListener(OnTapListener l) {
        mTapListener = l;
    }

    boolean isZoomed() {
        return userScale() > 1.01f;
    }

    private float userScale() {
        mUser.getValues(mValues);
        return mValues[Matrix.MSCALE_X];
    }

    void resetZoom() {
        mUser.reset();
        apply();
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        mUser.reset();
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    /** base matrix: the whole picture centred in the view */
    private void fit() {
        final Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) {
            return;
        }
        mBase.setRectToRect(new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight()),
                new RectF(0, 0, getWidth(), getHeight()), Matrix.ScaleToFit.CENTER);
        apply();
    }

    private void apply() {
        mDraw.set(mBase);
        mDraw.postConcat(mUser);
        setImageMatrix(mDraw);
    }

    /** keep the picture on screen: no gaps at the edges when it is larger than the view */
    private void clamp() {
        final Drawable d = getDrawable();
        if (d == null) {
            return;
        }
        mDraw.set(mBase);
        mDraw.postConcat(mUser);
        final RectF r = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        mDraw.mapRect(r);
        float dx = 0;
        float dy = 0;
        if (r.width() <= getWidth()) {
            dx = getWidth() / 2f - r.centerX();
        } else if (r.left > 0) {
            dx = -r.left;
        } else if (r.right < getWidth()) {
            dx = getWidth() - r.right;
        }
        if (r.height() <= getHeight()) {
            dy = getHeight() / 2f - r.centerY();
        } else if (r.top > 0) {
            dy = -r.top;
        } else if (r.bottom < getHeight()) {
            dy = getHeight() - r.bottom;
        }
        mUser.postTranslate(dx, dy);
        apply();
    }

    private void animateTo(final float target, final float fx, final float fy) {
        final float start = userScale();
        final ValueAnimator a = ValueAnimator.ofFloat(start, target);
        a.setDuration(220);
        a.setInterpolator(com.kiroos.ui.KiroTouch.EASE);
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            private float mLast = start;

            @Override
            public void onAnimationUpdate(ValueAnimator anim) {
                final float s = (float) anim.getAnimatedValue();
                final float f = s / mLast;
                mLast = s;
                mUser.postScale(f, f, fx, fy);
                clamp();
            }
        });
        a.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScale.onTouchEvent(event);
        mGestures.onTouchEvent(event);
        if (isZoomed() || event.getPointerCount() > 1) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return true;
    }
}
