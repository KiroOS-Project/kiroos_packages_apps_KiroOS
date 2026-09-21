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

package com.kiroos.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * A month as a 7 × 6 grid (weeks start on Monday): today ringed, the selected day filled,
 * up to three coloured dots per day for its events. Swipe sideways to change month.
 */
public class MonthView extends View {

    interface Listener {
        void onDaySelected(int year, int month, int day);

        void onMonthSwipe(int delta);
    }

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final GestureDetector mGestures;
    private final String[] mWeekdays = new String[7];
    private final float mDensity;
    private Listener mListener;
    private int mYear;
    private int mMonth;
    private int mSelected = 1;
    /** day of month → colours of its events */
    private SparseArray<List<Integer>> mDots = new SparseArray<>();

    public MonthView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(15 * getResources().getDisplayMetrics().scaledDensity);
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(1.5f * mDensity);
        mRing.setColor(context.getColor(R.color.kiro_accent));
        final String[] names = new DateFormatSymbols().getShortWeekdays();
        final int[] order = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        for (int i = 0; i < 7; i++) {
            mWeekdays[i] = names[order[i]].substring(0, 1).toUpperCase();
        }
        mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                final int day = dayAt(e.getX(), e.getY());
                if (day > 0) {
                    mSelected = day;
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                    if (mListener != null) {
                        mListener.onDaySelected(mYear, mMonth, day);
                    }
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && mListener != null) {
                    mListener.onMonthSwipe(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    void setListener(Listener l) {
        mListener = l;
    }

    void setMonth(int year, int month, int selected) {
        mYear = year;
        mMonth = month;
        mSelected = selected;
        invalidate();
    }

    void setDots(SparseArray<List<Integer>> dots) {
        mDots = dots;
        invalidate();
    }

    /** column (0 = Monday) of the 1st and the number of days */
    private int[] layout() {
        final Calendar c = Calendar.getInstance();
        c.clear();
        c.set(mYear, mMonth, 1);
        final int firstCol = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        return new int[] {firstCol, c.getActualMaximum(Calendar.DAY_OF_MONTH)};
    }

    private float headerHeight() {
        return 28 * mDensity;
    }

    private int dayAt(float x, float y) {
        if (y < headerHeight()) {
            return -1;
        }
        final float cw = getWidth() / 7f;
        final float ch = (getHeight() - headerHeight()) / 6f;
        final int col = (int) (x / cw);
        final int row = (int) ((y - headerHeight()) / ch);
        final int[] l = layout();
        final int day = row * 7 + col - l[0] + 1;
        return day >= 1 && day <= l[1] ? day : -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mGestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cw = getWidth() / 7f;
        final float ch = (getHeight() - headerHeight()) / 6f;
        final int[] l = layout();
        final Calendar today = Calendar.getInstance();
        final boolean thisMonth = today.get(Calendar.YEAR) == mYear
                && today.get(Calendar.MONTH) == mMonth;

        mText.setColor(getContext().getColor(R.color.kiro_text_tertiary));
        mText.setFakeBoldText(false);
        for (int i = 0; i < 7; i++) {
            canvas.drawText(mWeekdays[i], cw * i + cw / 2, headerHeight() * 0.7f, mText);
        }
        final float radius = Math.min(cw, ch) * 0.38f;
        for (int day = 1; day <= l[1]; day++) {
            final int idx = l[0] + day - 1;
            final float cx = (idx % 7) * cw + cw / 2;
            final float cy = headerHeight() + (idx / 7) * ch + ch * 0.42f;
            final boolean selected = day == mSelected;
            final boolean isToday = thisMonth && day == today.get(Calendar.DAY_OF_MONTH);
            if (selected) {
                mFill.setColor(getContext().getColor(R.color.kiro_accent));
                canvas.drawCircle(cx, cy, radius, mFill);
            } else if (isToday) {
                canvas.drawCircle(cx, cy, radius, mRing);
            }
            final boolean weekend = idx % 7 >= 5;
            mText.setColor(getContext().getColor(selected ? R.color.kiro_on_accent
                    : weekend ? R.color.kiro_text_secondary : R.color.kiro_text));
            mText.setFakeBoldText(isToday || selected);
            canvas.drawText(String.valueOf(day), cx, cy - (mText.descent() + mText.ascent()) / 2,
                    mText);
            final List<Integer> dots = mDots.get(day);
            if (dots != null) {
                final int n = Math.min(3, dots.size());
                final float dr = 2.2f * mDensity;
                final float gap = 6 * mDensity;
                float dx = cx - (n - 1) * gap / 2;
                for (int i = 0; i < n; i++) {
                    mFill.setColor(dots.get(i) | 0xFF000000);
                    canvas.drawCircle(dx, cy + radius + 5 * mDensity, dr, mFill);
                    dx += gap;
                }
            }
        }
    }

    static SparseArray<List<Integer>> dotsFor(List<EventStore.Instance> instances, int year,
            int month) {
        final SparseArray<List<Integer>> dots = new SparseArray<>();
        final Calendar c = Calendar.getInstance();
        for (EventStore.Instance i : instances) {
            c.setTimeInMillis(i.begin);
            // an event spanning several days marks each of them
            final long last = Math.max(i.begin, i.end - 1);
            while (c.getTimeInMillis() <= last) {
                if (c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month) {
                    final int d = c.get(Calendar.DAY_OF_MONTH);
                    List<Integer> l = dots.get(d);
                    if (l == null) {
                        l = new ArrayList<>();
                        dots.put(d, l);
                    }
                    l.add(i.color);
                }
                c.add(Calendar.DAY_OF_MONTH, 1);
                c.set(Calendar.HOUR_OF_DAY, 0);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
            }
        }
        return dots;
    }
}
