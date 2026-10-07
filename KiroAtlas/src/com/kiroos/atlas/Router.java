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

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Routes from BRouter (brouter.de, OpenStreetMap data) by car, bike or on foot, with its voice
 * hints as the turn-by-turn steps. The OSRM servers of FOSSGIS (routing.openstreetmap.de) take
 * only TLS 1.3 with an ISRG Root X2 chain, which Android 7 cannot open; brouter.de works over
 * TLS 1.2. BRouter gives no street names, so the steps say the maneuver only.
 */
final class Router {

    static final int CAR = 0, BIKE = 1, FOOT = 2;
    private static final String[] PROFILES = {"car-fast", "trekking", "hiking-mountain"};

    // BRouter VoiceHint commands
    private static final int C = 1, TL = 2, TSLL = 3, TSHL = 4, TR = 5, TSLR = 6, TSHR = 7,
            KL = 8, KR = 9, TLU = 10, TU = 11, TRU = 12, RNDB = 14, RNLB = 15;

    static final class Step {
        String text;
        double lat, lon;
        /** meters of this step, to the next maneuver */
        double distance;
    }

    static final class Route {
        double distance, duration;
        final List<double[]> points = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
    }

    private Router() {
    }

    /** Blocking. */
    static Route route(Context c, int mode, double lat1, double lon1, double lat2, double lon2)
            throws Exception {
        final String url = String.format(Locale.US,
                "https://brouter.de/brouter?lonlats=%.6f,%.6f%%7C%.6f,%.6f&profile=%s"
                        + "&alternativeidx=0&format=geojson&timode=2",
                lon1, lat1, lon2, lat2, PROFILES[mode]);
        final String body = new String(TileStore.get(url), "UTF-8");
        if (!body.trim().startsWith("{")) {
            // BRouter answers errors as plain text ("from-position not mapped in existing
            // datafile", ...)
            throw new IllegalStateException(body.trim());
        }
        final JSONObject f = new JSONObject(body).getJSONArray("features").getJSONObject(0);
        final JSONObject p = f.getJSONObject("properties");
        final Route route = new Route();
        route.distance = Double.parseDouble(p.getString("track-length"));
        route.duration = Double.parseDouble(p.getString("total-time"));
        final JSONArray coords = f.getJSONObject("geometry").getJSONArray("coordinates");
        for (int i = 0; i < coords.length(); i++) {
            final JSONArray pt = coords.getJSONArray(i);
            route.points.add(new double[] {pt.getDouble(1), pt.getDouble(0)});
        }
        if (route.points.isEmpty()) throw new IllegalStateException("empty route");

        // the steps: the start, each hint (point index, command, roundabout exit), the arrival
        final List<int[]> hints = new ArrayList<>();
        hints.add(new int[] {0, -1, 0});
        final JSONArray vh = p.optJSONArray("voicehints");
        if (vh != null) {
            for (int i = 0; i < vh.length(); i++) {
                final JSONArray h = vh.getJSONArray(i);
                final int at = h.getInt(0);
                if (at <= 0 || at >= route.points.size()) continue;
                hints.add(new int[] {at, h.getInt(1), h.optInt(2, 0)});
            }
        }
        hints.add(new int[] {route.points.size() - 1, -2, 0});
        final double[] along = new double[route.points.size()];
        for (int i = 1; i < along.length; i++) {
            final double[] a = route.points.get(i - 1), b = route.points.get(i);
            along[i] = along[i - 1] + Geo.distance(a[0], a[1], b[0], b[1]);
        }
        for (int i = 0; i < hints.size(); i++) {
            final int[] h = hints.get(i);
            final String text = h[1] == -1 ? c.getString(R.string.nav_depart)
                    : h[1] == -2 ? c.getString(R.string.nav_arrive)
                    : instruction(c, h[1], h[2]);
            if (text == null) continue;
            final Step step = new Step();
            final double[] pt = route.points.get(h[0]);
            step.lat = pt[0];
            step.lon = pt[1];
            step.text = text;
            final int next = i + 1 < hints.size() ? hints.get(i + 1)[0] : h[0];
            step.distance = along[next] - along[h[0]];
            route.steps.add(step);
        }
        return route;
    }

    /** a BRouter voice hint as a sentence in the phone's language; null for none */
    private static String instruction(Context c, int cmd, int exit) {
        switch (cmd) {
            case C: return c.getString(R.string.nav_continue);
            case TL: return turn(c, R.string.dir_left);
            case TSLL: return turn(c, R.string.dir_slight_left);
            case TSHL: return turn(c, R.string.dir_sharp_left);
            case TR: return turn(c, R.string.dir_right);
            case TSLR: return turn(c, R.string.dir_slight_right);
            case TSHR: return turn(c, R.string.dir_sharp_right);
            case KL: return capitalize(c.getString(R.string.nav_keep,
                    c.getString(R.string.dir_left)));
            case KR: return capitalize(c.getString(R.string.nav_keep,
                    c.getString(R.string.dir_right)));
            case TLU: case TU: case TRU: return c.getString(R.string.nav_uturn);
            case RNDB: case RNLB:
                return exit > 0 ? c.getString(R.string.nav_roundabout_exit, exit)
                        : c.getString(R.string.nav_roundabout);
            default: return null; // off route, beeline, end: not maneuvers
        }
    }

    private static String turn(Context c, int dir) {
        return capitalize(c.getString(R.string.nav_turn, c.getString(dir)));
    }

    /** the templates of some languages start with the direction ("sola dönün") */
    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }
}
