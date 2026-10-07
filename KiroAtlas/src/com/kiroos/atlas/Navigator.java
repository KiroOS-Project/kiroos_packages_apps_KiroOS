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

package com.kiroos.atlas;

import java.util.List;

/**
 * Where the phone is along a route: the point of the line it is nearest to, the next maneuver
 * and the meters to it, the meters left, and how far it is from the line (off the route).
 */
final class Navigator {

    static final class Progress {
        /** index in Route.steps of the next maneuver */
        int step;
        /** meters to that maneuver, along the route */
        double toStep;
        /** meters to the destination, along the route */
        double remaining;
        /** meters from the route line */
        double offRoute;
    }

    final Router.Route route;
    private final List<double[]> mPoints;
    /** meters along the line at each point */
    private final double[] mAlong;
    /** meters along the line of each maneuver */
    private final double[] mStepAt;
    /** the segment last matched: the search starts near it */
    private int mSeg;

    Navigator(Router.Route r) {
        route = r;
        mPoints = r.points;
        final int n = mPoints.size();
        mAlong = new double[Math.max(n, 1)];
        for (int i = 1; i < n; i++) {
            final double[] a = mPoints.get(i - 1), b = mPoints.get(i);
            mAlong[i] = mAlong[i - 1] + Geo.distance(a[0], a[1], b[0], b[1]);
        }
        // OSRM puts each maneuver on a vertex of the line: the first one at it, in order (a
        // route can pass the same place twice)
        mStepAt = new double[r.steps.size()];
        int from = 0;
        for (int s = 0; s < r.steps.size(); s++) {
            final Router.Step st = r.steps.get(s);
            int best = from;
            double bestD = Double.MAX_VALUE;
            for (int i = from; i < n; i++) {
                final double[] p = mPoints.get(i);
                final double d = Geo.distance(st.lat, st.lon, p[0], p[1]);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
                if (d < 1.5) break;
            }
            mStepAt[s] = n > 0 ? mAlong[best] : 0;
            from = best;
        }
    }

    double length() {
        return mAlong[mAlong.length - 1];
    }

    Progress update(double lat, double lon) {
        final Progress p = new Progress();
        final int n = mPoints.size();
        double along = 0;
        if (n >= 2) {
            double[] best = nearest(lat, lon, Math.max(0, mSeg - 3), Math.min(n - 2, mSeg + 150));
            if (best[1] > 60) {
                // far from where it was: maybe a shortcut, look along the whole route
                final double[] all = nearest(lat, lon, 0, n - 2);
                if (all[1] < best[1]) best = all;
            }
            mSeg = (int) best[0];
            along = mAlong[mSeg] + best[2] * (mAlong[mSeg + 1] - mAlong[mSeg]);
            p.offRoute = best[1];
        }
        p.remaining = Math.max(0, length() - along);
        p.step = mStepAt.length - 1;
        for (int s = 0; s < mStepAt.length; s++) {
            // a maneuver a few meters behind counts as done
            if (mStepAt[s] > along + 3) {
                p.step = s;
                break;
            }
        }
        p.toStep = p.step >= 0 ? Math.max(0, mStepAt[p.step] - along) : p.remaining;
        return p;
    }

    /** {segment, meters from it, position 0..1 on it} of the segment in [from, to] nearest */
    private double[] nearest(double lat, double lon, int from, int to) {
        final double kx = Math.cos(Math.toRadians(lat)) * 111320.0, ky = 110540.0;
        double bestD = Double.MAX_VALUE, bestT = 0;
        int bestI = from;
        for (int i = from; i <= to; i++) {
            final double[] a = mPoints.get(i), b = mPoints.get(i + 1);
            final double ax = (a[1] - lon) * kx, ay = (a[0] - lat) * ky;
            final double bx = (b[1] - lon) * kx, by = (b[0] - lat) * ky;
            final double dx = bx - ax, dy = by - ay;
            final double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            final double cx = ax + t * dx, cy = ay + t * dy;
            final double d = Math.sqrt(cx * cx + cy * cy);
            if (d < bestD) {
                bestD = d;
                bestT = t;
                bestI = i;
            }
        }
        return new double[] {bestI, bestD, bestT};
    }
}
