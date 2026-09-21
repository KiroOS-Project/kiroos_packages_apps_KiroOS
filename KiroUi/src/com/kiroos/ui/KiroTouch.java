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

package com.kiroos.ui;

import android.animation.TimeInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;

/**
 * The KiroOS v1.0 touch feedback: a view shrinks softly while pressed and springs back on
 * release. It does not consume the event, so clicks and ripples keep working.
 */
public final class KiroTouch {

    public static final TimeInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);
    private static final TimeInterpolator RELEASE = new OvershootInterpolator(2.2f);
    private static final float PRESSED_SCALE = 0.94f;

    private KiroTouch() {
    }

    /** Adds the press animation to each view. */
    public static void apply(View... views) {
        for (View v : views) {
            if (v != null) {
                v.setOnTouchListener(LISTENER);
            }
        }
    }

    private static final View.OnTouchListener LISTENER = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                            .setDuration(90).setInterpolator(EASE).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(260).setInterpolator(RELEASE).start();
                    break;
                default:
                    break;
            }
            return false;
        }
    };

    /** Fades and lifts the views in one after another (screen entrance). */
    public static void enter(View... views) {
        long delay = 0;
        for (View v : views) {
            if (v == null) {
                continue;
            }
            v.setAlpha(0f);
            v.setTranslationY(v.getResources().getDisplayMetrics().density * 16);
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                    .setDuration(320).setInterpolator(EASE).start();
            delay += 40;
        }
    }
}
