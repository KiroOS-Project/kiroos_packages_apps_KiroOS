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

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * A widget on the home (KiroOS Beta 4). Most widgets are clickable all over, so their own
 * views took every touch and a long press never reached the home: the widget could not be
 * moved or removed. Like Launcher3, the long press is watched here before the widget sees the
 * touch; once it fires, the rest of the gesture belongs to the home (the widget's views get a
 * cancel). It also remembers the last widget tapped, so the home can hand it over to the app
 * it opens (it leaves with the app and comes back to catch it).
 */
final class KiroWidgetView extends AppWidgetHostView {

    /** the host that makes these instead of plain AppWidgetHostViews */
    static final class Host extends AppWidgetHost {
        Host(Context context, int id) {
            super(context, id);
        }

        @Override
        protected AppWidgetHostView onCreateView(Context context, int appWidgetId,
                AppWidgetProviderInfo appWidget) {
            return new KiroWidgetView(context);
        }
    }

    private static KiroWidgetView sLastTapped;
    private static long sLastTapTime;

    private final int mSlop;
    private float mDownX, mDownY;
    private boolean mLongPressed;
    private final Runnable mLongPress = new Runnable() {
        @Override
        public void run() {
            if (getParent() != null && hasWindowFocus()) {
                mLongPressed = true;
                performLongClick();
            }
        }
    };

    KiroWidgetView(Context context) {
        super(context);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** the widget tapped in the last ms, if any (it opened what is starting now) */
    static KiroWidgetView tappedWithin(long ms) {
        final KiroWidgetView v = sLastTapped;
        return v != null && v.getParent() != null
                && SystemClock.uptimeMillis() - sLastTapTime <= ms ? v : null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mLongPressed = false;
                removeCallbacks(mLongPress);
                postDelayed(mLongPress, ViewConfiguration.getLongPressTimeout());
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - mDownX) > mSlop || Math.abs(ev.getY() - mDownY) > mSlop) {
                    removeCallbacks(mLongPress);
                }
                break;
            case MotionEvent.ACTION_UP:
                removeCallbacks(mLongPress);
                if (!mLongPressed) {
                    sLastTapped = this;
                    sLastTapTime = SystemClock.uptimeMillis();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(mLongPress);
                break;
        }
        // after a long press the gesture is the home's: the widget's views get a cancel
        return mLongPressed;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mLongPressed) {
            final int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                mLongPressed = false;
            }
            return true;
        }
        return super.onTouchEvent(ev);
    }
}
