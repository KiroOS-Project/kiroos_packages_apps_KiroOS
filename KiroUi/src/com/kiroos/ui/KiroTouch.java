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
    private static final TimeInterpolator RELEASE = new OvershootInterpolator(1.6f);
    private static final float PRESSED_SCALE = 0.94f;

    private KiroTouch() {
    }

    /** Adds the press animation to each view. */
    public static void apply(View... views) {
        for (View v : views) {
            if (v != null) {
                // the theme's icon-button press animation would fight over the scale
                v.setStateListAnimator(null);
                v.setOnTouchListener(LISTENER);
            }
        }
    }

    /** a press that turns into a scroll within this time never shows the shrink */
    private static final long PRESS_DELAY = 70;

    private static final View.OnTouchListener LISTENER = new View.OnTouchListener() {
        @Override
        public boolean onTouch(final View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    final Runnable press = new Runnable() {
                        @Override
                        public void run() {
                            v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                                    .setDuration(90).setInterpolator(EASE).start();
                        }
                    };
                    v.setTag(R.id.kiro_touch_press, press);
                    v.postDelayed(press, PRESS_DELAY);
                    break;
                }
                case MotionEvent.ACTION_UP:
                    cancelPending(v);
                    // a quick tap still gets the full press-and-spring
                    v.animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                            .setDuration(60).setInterpolator(EASE)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                                            .setInterpolator(RELEASE).start();
                                }
                            }).start();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    // the gesture became a scroll (or a drag): settle back quietly,
                    // no overshoot wobble while the page moves
                    cancelPending(v);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).setInterpolator(EASE)
                            .start();
                    break;
                default:
                    break;
            }
            return false;
        }
    };

    private static void cancelPending(View v) {
        final Object press = v.getTag(R.id.kiro_touch_press);
        if (press instanceof Runnable) {
            v.removeCallbacks((Runnable) press);
            v.setTag(R.id.kiro_touch_press, null);
        }
    }

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
