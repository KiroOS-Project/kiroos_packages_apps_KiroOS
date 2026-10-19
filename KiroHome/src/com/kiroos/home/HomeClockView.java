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
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The home's clock (KiroOS Beta 4.1): a widget of the grid like the others, so it can be moved
 * and comes in three sizes (2 x 2, 4 x 2, 4 x 4). The time and the date follow the room they
 * get: the time as large as fits the width and about half the height, the date in proportion.
 */
public class HomeClockView extends LinearLayout {

    private TextView mTime;
    private TextView mDate;

    public HomeClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTime = (TextView) findViewById(R.id.clock_time);
        mDate = (TextView) findViewById(R.id.clock_date);
    }

    /**
     * The sizes are set here, before the texts are measured: set in onSizeChanged (during the
     * layout) they came after the measure, so the time and the date kept the width measured for
     * the old size and were cut ("10:" and "Monday 19 O…" once the hour had two digits).
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = MeasureSpec.getSize(heightMeasureSpec);
        if (w > 0 && h > 0 && mTime != null) {
            final int inner = w - getPaddingLeft() - getPaddingRight();
            // "00:00" in the light face is about 2.6 em wide: fill the width, keep under half
            // the height so the date fits below
            final float time = Math.min(inner / 2.7f, h * 0.48f);
            final float date = Math.min(Math.max(time * 0.27f,
                    12 * getResources().getDisplayMetrics().density),
                    22 * getResources().getDisplayMetrics().scaledDensity);
            // only when it changes: setTextSize asks for a new layout
            if (Math.abs(mTime.getTextSize() - time) > 0.5f) {
                mTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, time);
            }
            if (Math.abs(mDate.getTextSize() - date) > 0.5f) {
                mDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, date);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
