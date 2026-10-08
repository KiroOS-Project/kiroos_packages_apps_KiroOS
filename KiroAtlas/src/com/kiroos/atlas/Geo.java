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

/**
 * Web Mercator in "world units": the whole world is the square [0,1) x [0,1), x east, y south.
 * A tile (z, x, y) covers [x / 2^z, (x+1) / 2^z) on each axis.
 */
final class Geo {

    static final double MAX_LAT = 85.05112878;
    private static final double EARTH_RADIUS = 6371008.8;

    private Geo() {
    }

    static double x(double lon) {
        return (lon + 180.0) / 360.0;
    }

    static double y(double lat) {
        final double l = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
        final double r = Math.toRadians(l);
        return (1.0 - Math.log(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0;
    }

    static double lon(double x) {
        return x * 360.0 - 180.0;
    }

    static double lat(double y) {
        final double n = Math.PI * (1.0 - 2.0 * y);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** great-circle distance in meters */
    static double distance(double lat1, double lon1, double lat2, double lon2) {
        final double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        final double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        final double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** meters from a point to the segment a-b (all lat/lon), on a local flat projection */
    static double distanceToSegment(double lat, double lon, double lat1, double lon1,
            double lat2, double lon2) {
        final double k = Math.cos(Math.toRadians(lat)) * 111320.0;
        final double px = lon * k, py = lat * 110540.0;
        final double ax = lon1 * k, ay = lat1 * 110540.0;
        final double bx = lon2 * k, by = lat2 * 110540.0;
        final double dx = bx - ax, dy = by - ay;
        final double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        final double cx = ax + t * dx - px, cy = ay + t * dy - py;
        return Math.sqrt(cx * cx + cy * cy);
    }

    /** the distance for the voice: words, not "200 m" (Pico spelled the unit out) */
    static String spokenDistance(android.content.Context c, double meters, boolean metric) {
        if (!metric) {
            final double feet = meters * 3.28084;
            if (feet < 1000) {
                return c.getString(R.string.say_feet, Math.round(feet / 10) * 10);
            }
            return c.getString(R.string.say_miles, decimal(meters / 1609.344));
        }
        if (meters < 1000) return c.getString(R.string.say_meters, Math.round(meters / 10) * 10);
        return c.getString(R.string.say_km, decimal(meters / 1000));
    }

    /** 1.5 -> "1,5" (the locale's way), 2.0 -> "2", 12.3 -> "12" */
    private static String decimal(double v) {
        if (v >= 10 || Math.abs(v - Math.round(v)) < 0.05) return String.valueOf(Math.round(v));
        return String.format(java.util.Locale.getDefault(), "%.1f", v);
    }

    static String formatDistance(double meters, boolean metric) {
        if (!metric) {
            final double feet = meters * 3.28084;
            if (feet < 1000) return Math.round(feet / 10) * 10 + " ft";
            final double miles = meters / 1609.344;
            return (miles < 10 ? String.format("%.1f", miles) : String.valueOf(Math.round(miles)))
                    + " mi";
        }
        if (meters < 1000) return (Math.round(meters / 10) * 10) + " m";
        final double km = meters / 1000;
        return (km < 10 ? String.format("%.1f", km) : String.valueOf(Math.round(km))) + " km";
    }
}
