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

import android.view.animation.Interpolator;

/**
 * Beta 4.1 "Fluida": a damped spring in real time, the model the system's app transitions use
 * (AppTransition.KiroSpring), so the home and the windows move as one. Started with a velocity
 * v0 (distances per second) it carries a gesture on; it ends exactly at 1 when the animation
 * does: x(t) = 1 - e^(-z w t) (cos(wd t) + ((z w - v0) / wd) sin(wd t)).
 *
 * Use the spring with its own duration (duration()), so it has the time to settle.
 */
final class KiroSpring implements Interpolator {
    /** opening, resizing, moving: stiffness 170, damping 0.80 (1.4% past, then home) */
    static final KiroSpring FLUID = new KiroSpring(0.80, 13.04, 0, 620);
    /** the same feel with no overshoot, for what must not pass its end (fades, the drawer) */
    static final KiroSpring SMOOTH = new KiroSpring(0.95, 13.04, 0, 560);
    /** a quick response to the finger: a widget or an icon lifted, a hint moving */
    static final KiroSpring QUICK = new KiroSpring(0.75, 22, 0, 340);
    /** an icon catching its app as it comes home: a visible landing bounce */
    static final KiroSpring CATCH = new KiroSpring(0.55, 15, 0, 520);

    private final double mZeta;
    private final double mOmega;
    private final double mV0;
    private final double mSeconds;
    private final double mEnd;
    private final long mDuration;

    KiroSpring(double zeta, double omega, double v0, int durationMs) {
        mZeta = zeta;
        mOmega = omega;
        mV0 = v0;
        mSeconds = durationMs / 1000.0;
        mDuration = durationMs;
        mEnd = raw(1f);
    }

    /** the time the spring needs to settle */
    long duration() {
        return mDuration;
    }

    private double raw(float t) {
        final double s = t * mSeconds;
        if (mZeta >= 1) {
            // critically damped: x = 1 - (1 + (w - v0) s) e^(-w s)
            return 1 - (1 + (mOmega - mV0) * s) * Math.exp(-mOmega * s);
        }
        final double wd = mOmega * Math.sqrt(1 - mZeta * mZeta);
        final double decay = Math.exp(-mZeta * mOmega * s);
        return 1 - decay * (Math.cos(wd * s) + ((mZeta * mOmega - mV0) / wd) * Math.sin(wd * s));
    }

    @Override
    public float getInterpolation(float t) {
        if (t >= 1f) return 1f;
        return (float) (raw(t) / mEnd);
    }
}
