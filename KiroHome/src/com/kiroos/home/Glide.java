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

import android.animation.TimeInterpolator;

/**
 * Ease-out that starts at a given speed and lands with none: what a release does when the
 * finger was moving (pages, the drawer). slope0 is the start speed relative to the average
 * one; a cubic Hermite curve, monotonic for slope0 <= 3.
 */
final class Glide implements TimeInterpolator {

    private final float mSlope0;

    Glide(float slope0) {
        mSlope0 = Math.max(0f, Math.min(2.5f, slope0));
    }

    @Override
    public float getInterpolation(float t) {
        final float t2 = t * t;
        final float t3 = t2 * t;
        return mSlope0 * (t3 - 2 * t2 + t) + (-2 * t3 + 3 * t2);
    }

    /**
     * Duration (ms) for a glide over distance px released at speed px/s toward the target;
     * fallback when the finger was slow.
     */
    static long duration(float distance, float speed, long minMs, long maxMs, long fallbackMs) {
        if (distance < 1f) {
            return minMs;
        }
        if (speed <= 0f) {
            return fallbackMs;
        }
        return Math.max(minMs, Math.min(maxMs, (long) (2000f * distance / speed)));
    }
}
