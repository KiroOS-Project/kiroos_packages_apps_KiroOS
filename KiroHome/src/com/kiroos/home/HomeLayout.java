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
 * The gesture follows one finger: the fingers holding the phone often rest on the screen
 * edge, before or during a swipe, and they must neither drive the pages nor keep a page
 * swipe from settling when the swiping finger lifts. A new finger takes the lead unless it
 * lands on a side edge while the leading one is not there (a grip, not a new swipe).
 */
public class HomeLayout extends FrameLayout {

    interface Callbacks {
        boolean isDrawerOpen();

        /** an open folder takes all touches */
        boolean isFolderOpen();

        /** 0 = closed, 1 = fully open; called while the finger drags the drawer up. */
        void onDrawerDrag(float progress);

        /**
         * Finger lifted during a drawer drag: settle open or closed, gliding on from the
         * finger's own speed (px/s, toward the target; 0 when it stood still).
         */
        void onDrawerRelease(boolean open, float speed);

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

        /** Kiro Home 2.0: an app (or folder) is lifted and follows the finger */
        boolean isDragging();

        /** the lifted app moves to this point of the home */
        void onDragMove(float x, float y);

        /** the finger lifted (dropped = true) or the touch was cancelled */
        void onDragEnd(float x, float y, boolean dropped);
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
        mEdge = 24 * context.getResources().getDisplayMetrics().density;
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

    /** where the finger is now (the point of the last touch event anywhere on the home) */
    float lastX() {
        return mLastX;
    }

    float lastY() {
        return mLastY;
    }

    private float mLastX;
    private float mLastY;
    /** the pointer that lifted the app: the drag follows it alone */
    private int mDragPointer = -1;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int i = mDragPointer >= 0 ? ev.findPointerIndex(mDragPointer) : 0;
        if (i >= 0 && i < ev.getPointerCount()) {
            mLastX = ev.getX(i);
            mLastY = ev.getY(i);
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDragPointer = ev.getPointerId(0);
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Kiro Home 2.0: while an app is lifted every touch is the drag's, wherever it started
     * (on an icon, in the drawer, in a folder): the home takes the gesture over and the view
     * that saw the long press gets a cancel.
     */
    private boolean handleDrag(MotionEvent ev) {
        final int i = ev.findPointerIndex(mDragPointer);
        final float x = i >= 0 ? ev.getX(i) : mLastX;
        final float y = i >= 0 ? ev.getY(i) : mLastY;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mCallbacks.onDragMove(x, y);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (ev.getPointerId(ev.getActionIndex()) == mDragPointer) {
                    mCallbacks.onDragEnd(x, y, true);
                }
                break;
            case MotionEvent.ACTION_UP:
                mCallbacks.onDragEnd(x, y, true);
                finish();
                break;
            case MotionEvent.ACTION_CANCEL:
                mCallbacks.onDragEnd(x, y, false);
                finish();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mCallbacks != null && mCallbacks.isDragging()) {
            // the event that makes the home take over goes to the child as a cancel and
            // never reaches onTouchEvent: handle it here (a quick lift right after the long
            // press would otherwise leave the app hanging)
            return handleDrag(ev);
        }
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
                if (isGrip(ev)) {
                    break; // a hand settling on the edge: the swiping finger keeps the lead
                }
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
        if (mCallbacks.isDragging()) {
            return handleDrag(ev);
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
                cancelLongPress(ev);
                if (isGrip(ev)) {
                    return true; // a hand settling on the edge: the swiping finger keeps the lead
                }
                mPointers = Math.max(mPointers, ev.getPointerCount());
                if (mMode == DONE) {
                    // a swipe ended while the hand stays on the edge: a new finger starts a
                    // new gesture (catching the glide like a first touch would)
                    down(ev, ev.getActionIndex());
                    mMode = NONE;
                    if (mCallbacks.onTouchDown()) {
                        mMode = PAGE;
                        mCallbacks.onPageDragStart();
                    }
                    return true;
                }
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
                    // Beta 5: the sheet stays under the finger, one to one
                    final float travel = getHeight() * 0.95f;
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

    /**
     * True if the finger that just touched down lands on a side edge while the leading
     * finger is elsewhere: the hand holding the phone, not a new swipe.
     */
    private boolean isGrip(MotionEvent ev) {
        final float x = ev.getX(ev.getActionIndex());
        if (x > mEdge && x < getWidth() - mEdge) {
            return false;
        }
        final int lead = ev.findPointerIndex(mActiveId);
        if (lead < 0 || lead == ev.getActionIndex()) {
            return false;
        }
        final float lx = ev.getX(lead);
        return lx > mEdge && lx < getWidth() - mEdge;
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
                    || (vy < mMinFling && -dy > getHeight() * 0.3f));
            // the speed toward where it settles (up when opening, down when closing)
            final float speed = open ? Math.max(0f, -vy) : Math.max(0f, vy);
            mCallbacks.onDrawerRelease(open, speed);
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
