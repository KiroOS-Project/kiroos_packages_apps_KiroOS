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
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Root of the home screen. A swipe that starts anywhere on the home (also over icons, once it
 * moves past the touch slop) is taken over: up opens the app drawer following the finger,
 * down expands the notification shade (two fingers: quick settings), sideways moves between
 * the home pages. Long-press on empty space opens the home menu.
 */
public class HomeLayout extends FrameLayout {

    interface Callbacks {
        boolean isDrawerOpen();

        /** 0 = closed, 1 = fully open; called while the finger drags the drawer up. */
        void onDrawerDrag(float progress);

        /** Finger lifted during a drawer drag: settle open or closed. */
        void onDrawerRelease(boolean open);

        void onSwipeDown(boolean twoFingers);

        void onEmptyLongPress(float x, float y);

        /** a horizontal drag starts: the home pages follow the finger */
        void onPageDragStart();

        /** dx: horizontal travel since the drag started */
        void onPageDrag(float dx);

        void onPageRelease(float velocityX, float minFling);
    }

    private static final int NONE = 0;
    private static final int DRAWER = 1;
    private static final int SHADE = 2;
    private static final int PAGE = 3;

    private final int mTouchSlop;
    private final float mMinFling;
    private final GestureDetector mLongPress;
    private Callbacks mCallbacks;
    private VelocityTracker mVelocity;
    private float mDownX;
    private float mDownY;
    private int mMode = NONE;
    private int mPointers;

    public HomeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFling = vc.getScaledMinimumFlingVelocity() * 4;
        mLongPress = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (mCallbacks != null && mMode == NONE && !mCallbacks.isDrawerOpen()) {
                    mCallbacks.onEmptyLongPress(e.getX(), e.getY());
                }
            }
        });
    }

    void setCallbacks(Callbacks callbacks) {
        mCallbacks = callbacks;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mCallbacks == null || mCallbacks.isDrawerOpen()) {
            return false;
        }
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mMode = NONE;
                mPointers = 1;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                mPointers = ev.getPointerCount();
                break;
            case MotionEvent.ACTION_MOVE:
                final float dy = ev.getY() - mDownY;
                final float dx = ev.getX() - mDownX;
                if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                    mMode = dy < 0 ? DRAWER : SHADE;
                    return true;
                }
                if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy)) {
                    mMode = PAGE;
                    mDownX = ev.getX(); // the pages start following from here, no jump
                    mCallbacks.onPageDragStart();
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
        if (mCallbacks == null) {
            return false;
        }
        track(ev);
        if (mMode == NONE) {
            mLongPress.onTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mMode = NONE;
                mPointers = 1;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                mPointers = ev.getPointerCount();
                return true;
            case MotionEvent.ACTION_MOVE: {
                final float dy = ev.getY() - mDownY;
                final float dx = ev.getX() - mDownX;
                if (mMode == NONE && (Math.abs(dy) > mTouchSlop || Math.abs(dx) > mTouchSlop)) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        mMode = PAGE;
                        mDownX = ev.getX();
                        mCallbacks.onPageDragStart();
                    } else {
                        mMode = dy < 0 ? DRAWER : SHADE;
                    }
                    // the long press must not fire once the finger moves
                    final MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    mLongPress.onTouchEvent(cancel);
                    cancel.recycle();
                }
                if (mMode == PAGE) {
                    mCallbacks.onPageDrag(ev.getX() - mDownX);
                } else if (mMode == DRAWER) {
                    final float travel = getHeight() * 0.6f;
                    mCallbacks.onDrawerDrag(Math.max(0f, Math.min(1f, -dy / travel)));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final boolean up = ev.getActionMasked() == MotionEvent.ACTION_UP;
                float vy = 0;
                float vx = 0;
                if (mVelocity != null) {
                    mVelocity.computeCurrentVelocity(1000);
                    vy = mVelocity.getYVelocity();
                    vx = mVelocity.getXVelocity();
                }
                final float dy = ev.getY() - mDownY;
                if (mMode == PAGE) {
                    mCallbacks.onPageRelease(up ? vx : 0, mMinFling);
                } else if (mMode == DRAWER) {
                    final boolean open = up && (vy < -mMinFling
                            || (vy < mMinFling && -dy > getHeight() * 0.25f));
                    mCallbacks.onDrawerRelease(open);
                } else if (mMode == SHADE && up && dy > mTouchSlop * 2) {
                    mCallbacks.onSwipeDown(mPointers > 1);
                }
                mMode = NONE;
                if (mVelocity != null) {
                    mVelocity.recycle();
                    mVelocity = null;
                }
                return true;
            }
            default:
                return true;
        }
    }

    private void track(MotionEvent ev) {
        if (mVelocity == null) {
            mVelocity = VelocityTracker.obtain();
        }
        mVelocity.addMovement(ev);
    }
}
