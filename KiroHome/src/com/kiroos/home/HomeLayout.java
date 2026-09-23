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
 *
 * The gesture follows one finger, the one that touched last: the fingers holding the phone
 * often rest on the screen edge, and they must neither drive the pages nor keep a page
 * swipe from settling when the swiping finger lifts.
 */
public class HomeLayout extends FrameLayout {

    interface Callbacks {
        boolean isDrawerOpen();

        /** an open folder takes all touches */
        boolean isFolderOpen();

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

        /** a touch ended without a page swipe: the pages must rest on a page */
        void onTouchDone();

        /** a finger touches down; true if it caught the pages mid-glide (it drives them now) */
        boolean onTouchDown();
    }

    private static final int NONE = 0;
    private static final int DRAWER = 1;
    private static final int SHADE = 2;
    private static final int PAGE = 3;
    /** the gesture was released while other fingers stay down: ignore the rest of it */
    private static final int DONE = 4;

    private final int mTouchSlop;
    private final float mMinFling;
    private final float mEdge;
    private final GestureDetector mLongPress;
    private Callbacks mCallbacks;
    private VelocityTracker mVelocity;
    private float mDownX;
    private float mDownY;
    private int mMode = NONE;
    private int mPointers;
    private int mActiveId = -1;

    public HomeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        final ViewConfiguration vc = ViewConfiguration.get(context);
        mTouchSlop = vc.getScaledTouchSlop();
        mMinFling = vc.getScaledMinimumFlingVelocity() * 4;
        mEdge = 16 * context.getResources().getDisplayMetrics().density;
        mLongPress = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                // a finger resting on the side edge is the hand holding the phone
                if (mCallbacks != null && mMode == NONE && mPointers == 1
                        && e.getX() > mEdge && e.getX() < getWidth() - mEdge
                        && !mCallbacks.isDrawerOpen()) {
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
        if (mCallbacks == null || mCallbacks.isDrawerOpen() || mCallbacks.isFolderOpen()) {
            return false;
        }
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                down(ev, 0);
                mMode = NONE;
                mPointers = 1;
                if (mCallbacks.onTouchDown()) {
                    // the pages were still gliding: they stop under the finger and follow
                    // it from the first pixel (no slop, no jump back)
                    mMode = PAGE;
                    mCallbacks.onPageDragStart();
                    return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // the new finger leads (a resting grip finger came first)
                mPointers = Math.max(mPointers, ev.getPointerCount());
                down(ev, ev.getActionIndex());
                break;
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(ev);
                break;
            case MotionEvent.ACTION_MOVE: {
                final int i = ev.findPointerIndex(mActiveId);
                if (i < 0) {
                    break;
                }
                final float dy = ev.getY(i) - mDownY;
                final float dx = ev.getX(i) - mDownX;
                if (Math.abs(dy) > mTouchSlop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                    mMode = dy < 0 ? DRAWER : SHADE;
                    return true;
                }
                if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy)) {
                    mMode = PAGE;
                    mDownX = ev.getX(i); // the pages start following from here, no jump
                    mCallbacks.onPageDragStart();
                    return true;
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // a child (an icon) had the whole touch
                finish();
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
                if (mMode == PAGE) {
                    return true; // caught by onInterceptTouchEvent for this same touch
                }
                down(ev, 0);
                mMode = NONE;
                mPointers = 1;
                if (mCallbacks.onTouchDown()) {
                    mMode = PAGE;
                    mCallbacks.onPageDragStart();
                    cancelLongPress(ev);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                mPointers = Math.max(mPointers, ev.getPointerCount());
                cancelLongPress(ev);
                if (mMode != NONE && mMode != PAGE) {
                    return true; // the drawer and the shade stay with their finger
                }
                down(ev, ev.getActionIndex());
                if (mMode == PAGE) {
                    // the pages go on from where they are, under the new finger
                    mCallbacks.onPageDragStart();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerId(ev.getActionIndex()) == mActiveId
                        && (mMode == PAGE || mMode == DRAWER)) {
                    // the swiping finger lifted, a grip finger may still rest on the edge
                    release(ev, true);
                    mMode = DONE;
                }
                pointerUp(ev);
                return true;
            case MotionEvent.ACTION_MOVE: {
                final int i = ev.findPointerIndex(mActiveId);
                if (i < 0 || mMode == DONE) {
                    return true;
                }
                final float dy = ev.getY(i) - mDownY;
                final float dx = ev.getX(i) - mDownX;
                if (mMode == NONE && (Math.abs(dy) > mTouchSlop || Math.abs(dx) > mTouchSlop)) {
                    if (Math.abs(dx) > Math.abs(dy)) {
                        mMode = PAGE;
                        mDownX = ev.getX(i);
                        mCallbacks.onPageDragStart();
                    } else {
                        mMode = dy < 0 ? DRAWER : SHADE;
                    }
                    // the long press must not fire once the finger moves
                    cancelLongPress(ev);
                }
                if (mMode == PAGE) {
                    mCallbacks.onPageDrag(ev.getX(i) - mDownX);
                } else if (mMode == DRAWER) {
                    final float travel = getHeight() * 0.6f;
                    mCallbacks.onDrawerDrag(Math.max(0f, Math.min(1f, -dy / travel)));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release(ev, ev.getActionMasked() == MotionEvent.ACTION_UP);
                finish();
                return true;
            default:
                return true;
        }
    }

    /** the home menu opens only for one finger held still */
    private void cancelLongPress(MotionEvent ev) {
        final MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        mLongPress.onTouchEvent(cancel);
        cancel.recycle();
    }

    /** the finger at index leads the gesture from here */
    private void down(MotionEvent ev, int index) {
        mActiveId = ev.getPointerId(index);
        mDownX = ev.getX(index);
        mDownY = ev.getY(index);
    }

    /** a finger lifted while others stay: if it was leading, the most recent other one leads */
    private void pointerUp(MotionEvent ev) {
        final int up = ev.getActionIndex();
        if (ev.getPointerId(up) != mActiveId) {
            return;
        }
        final int next = up == ev.getPointerCount() - 1 ? up - 1 : ev.getPointerCount() - 1;
        if (next >= 0) {
            down(ev, next);
        }
        if (mVelocity != null) {
            mVelocity.clear();
        }
    }

    /** ends the gesture in progress; up = the finger lifted (not cancelled) */
    private void release(MotionEvent ev, boolean up) {
        float vy = 0;
        float vx = 0;
        if (mVelocity != null && up) {
            mVelocity.computeCurrentVelocity(1000);
            vy = mVelocity.getYVelocity(mActiveId);
            vx = mVelocity.getXVelocity(mActiveId);
        }
        final int i = ev.findPointerIndex(mActiveId);
        final float dy = (i >= 0 ? ev.getY(i) : mDownY) - mDownY;
        if (mMode == PAGE) {
            mCallbacks.onPageRelease(vx, mMinFling);
        } else if (mMode == DRAWER) {
            final boolean open = up && (vy < -mMinFling
                    || (vy < mMinFling && -dy > getHeight() * 0.25f));
            mCallbacks.onDrawerRelease(open);
        } else if (mMode == SHADE && up && dy > mTouchSlop * 2) {
            mCallbacks.onSwipeDown(mPointers > 1);
        }
    }

    private void finish() {
        if (mMode != PAGE && mCallbacks != null) {
            mCallbacks.onTouchDone();
        }
        mMode = NONE;
        mActiveId = -1;
        if (mVelocity != null) {
            mVelocity.recycle();
            mVelocity = null;
        }
    }

    private void track(MotionEvent ev) {
        if (mVelocity == null) {
            mVelocity = VelocityTracker.obtain();
        }
        mVelocity.addMovement(ev);
    }
}
