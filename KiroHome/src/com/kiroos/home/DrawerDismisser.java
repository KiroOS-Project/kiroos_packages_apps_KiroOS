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

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridView;

/**
 * Closes the app drawer with a downward swipe that starts while the list is scrolled to the
 * top, like pulling the drawer back down. Other gestures reach the list untouched.
 */
final class DrawerDismisser implements View.OnTouchListener {

    private final GridView mGrid;
    private final HomeActivity mActivity;
    private final int mSlop;
    private float mDownY;
    private float mDownX;
    private boolean mAtTopOnDown;
    private boolean mFired;

    DrawerDismisser(GridView grid, HomeActivity activity) {
        mGrid = grid;
        mActivity = activity;
        mSlop = ViewConfiguration.get(activity).getScaledTouchSlop() * 3;
        grid.setOnTouchListener(this);
    }

    private boolean atTop() {
        if (mGrid.getChildCount() == 0) {
            return true;
        }
        return mGrid.getFirstVisiblePosition() == 0
                && mGrid.getChildAt(0).getTop() >= mGrid.getPaddingTop();
    }

    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mAtTopOnDown = atTop();
                mFired = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mAtTopOnDown && !mFired && atTop()) {
                    final float dy = ev.getY() - mDownY;
                    if (dy > mSlop && dy > Math.abs(ev.getX() - mDownX) * 1.5f) {
                        mFired = true;
                        final MotionEvent cancel = MotionEvent.obtain(ev);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        mGrid.onTouchEvent(cancel);
                        cancel.recycle();
                        mActivity.animateDrawer(false);
                        return true;
                    }
                }
                break;
            default:
                break;
        }
        return mFired;
    }
}
