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

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.kiroos.ui.KiroTouch;

/**
 * Three pages (previous, current, next) side by side: a horizontal drag moves them with the
 * finger and settles on a neighbour past a third of the width or on a fling. A zoomed photo
 * keeps its own gestures (it disallows the intercept).
 */
public class SwipePager extends FrameLayout {

    interface Adapter {
        int count();

        /** fill a page for position (the view is reused) */
        void bind(View page, int position);

        View create(Context context);

        void onPageSelected(int position);
    }

    private final View[] mPages = new View[3];
    private final int mSlop;
    private final int mMinFling;
    private Adapter mAdapter;
    private int mPosition;
    private float mDownX;
    private float mDownY;
    private float mLastX;
    private boolean mDragging;
    private float mOffset;
    private VelocityTracker mVelocity;

    public SwipePager(Context context, AttributeSet attrs) {
        super(context, attrs);
        final ViewConfiguration vc = ViewConfiguration.get(context);
        mSlop = vc.getScaledTouchSlop();
        mMinFling = vc.getScaledMinimumFlingVelocity() * 6;
    }

    void setAdapter(Adapter adapter, int position) {
        mAdapter = adapter;
        removeAllViews();
        for (int i = 0; i < 3; i++) {
            mPages[i] = adapter.create(getContext());
            addView(mPages[i], new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
        }
        setPosition(position);
    }

    int position() {
        return mPosition;
    }

    View current() {
        return mPages[1];
    }

    void setPosition(int position) {
        mPosition = Math.max(0, Math.min(position, mAdapter.count() - 1));
        for (int i = 0; i < 3; i++) {
            final int p = mPosition + i - 1;
            if (p >= 0 && p < mAdapter.count()) {
                mAdapter.bind(mPages[i], p);
                mPages[i].setVisibility(VISIBLE);
            } else {
                mPages[i].setVisibility(INVISIBLE);
            }
        }
        mOffset = 0;
        layoutPages();
        mAdapter.onPageSelected(mPosition);
    }

    /** the list changed (e.g. a photo was deleted) */
    void refresh() {
        setPosition(Math.min(mPosition, mAdapter.count() - 1));
    }

    private void layoutPages() {
        final float w = getWidth();
        for (int i = 0; i < 3; i++) {
            mPages[i].setTranslationX((i - 1) * w + mOffset);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutPages();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = mLastX = ev.getX();
                mDownY = ev.getY();
                mDragging = false;
                track(ev, true);
                break;
            case MotionEvent.ACTION_MOVE:
                track(ev, false);
                final float dx = ev.getX() - mDownX;
                if (ev.getPointerCount() == 1 && Math.abs(dx) > mSlop
                        && Math.abs(dx) > Math.abs(ev.getY() - mDownY) * 1.5f) {
                    mDragging = true;
                    mLastX = ev.getX();
                    return true;
                }
                break;
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        track(ev, false);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (mDragging) {
                    float d = ev.getX() - mLastX;
                    mLastX = ev.getX();
                    // resist at the ends
                    if ((mPosition == 0 && mOffset + d > 0)
                            || (mPosition == mAdapter.count() - 1 && mOffset + d < 0)) {
                        d /= 3;
                    }
                    mOffset += d;
                    layoutPages();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mDragging) {
                    float vx = 0;
                    if (mVelocity != null) {
                        mVelocity.computeCurrentVelocity(1000);
                        vx = mVelocity.getXVelocity();
                    }
                    int target = 0;
                    if ((mOffset < -getWidth() / 3f || vx < -mMinFling)
                            && mPosition < mAdapter.count() - 1) {
                        target = 1;
                    } else if ((mOffset > getWidth() / 3f || vx > mMinFling) && mPosition > 0) {
                        target = -1;
                    }
                    settle(target);
                }
                mDragging = false;
                return true;
            default:
                return true;
        }
    }

    private void settle(final int direction) {
        final float from = mOffset;
        final float to = -direction * getWidth();
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(from, to);
        a.setDuration(200);
        a.setInterpolator(KiroTouch.EASE);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator anim) {
                mOffset = (float) anim.getAnimatedValue();
                layoutPages();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (direction != 0) {
                    // rotate the three pages instead of rebinding all of them
                    if (direction > 0) {
                        final View first = mPages[0];
                        mPages[0] = mPages[1];
                        mPages[1] = mPages[2];
                        mPages[2] = first;
                    } else {
                        final View last = mPages[2];
                        mPages[2] = mPages[1];
                        mPages[1] = mPages[0];
                        mPages[0] = last;
                    }
                    mPosition += direction;
                    final int fresh = direction > 0 ? 2 : 0;
                    final int p = mPosition + fresh - 1;
                    if (p >= 0 && p < mAdapter.count()) {
                        mAdapter.bind(mPages[fresh], p);
                        mPages[fresh].setVisibility(VISIBLE);
                    } else {
                        mPages[fresh].setVisibility(INVISIBLE);
                    }
                    mAdapter.onPageSelected(mPosition);
                }
                mOffset = 0;
                layoutPages();
            }
        });
        a.start();
    }

    private void track(MotionEvent ev, boolean reset) {
        if (reset && mVelocity != null) {
            mVelocity.clear();
        }
        if (mVelocity == null) {
            mVelocity = VelocityTracker.obtain();
        }
        mVelocity.addMovement(ev);
    }
}
