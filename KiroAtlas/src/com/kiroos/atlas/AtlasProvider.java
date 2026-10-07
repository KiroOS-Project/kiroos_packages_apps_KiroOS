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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.List;

/**
 * Places and travel times for other apps (kAI), blocking, so called off the main thread:
 * <ul>
 * <li>content://com.kiroos.atlas/geocode?q=Colosseo[&lat=&lon=][&limit=5]:
 *     name, detail, lat, lon</li>
 * <li>content://com.kiroos.atlas/route?from=lat,lon&to=lat,lon[&mode=car|bike|foot]:
 *     distance (m), duration (s)</li>
 * </ul>
 * Callers need INTERNET: the provider gives nothing they could not fetch themselves.
 */
public class AtlasProvider extends ContentProvider {

    private static final String TAG = "AtlasProvider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sort) {
        try {
            if ("/geocode".equals(uri.getPath())) return geocode(uri);
            if ("/route".equals(uri.getPath())) return route(uri);
        } catch (Exception e) {
            Log.w(TAG, "query " + uri.getPath() + ": " + e);
        }
        return null;
    }

    private Cursor geocode(Uri uri) throws Exception {
        final String q = uri.getQueryParameter("q");
        final MatrixCursor c = new MatrixCursor(new String[] {"name", "detail", "lat", "lon"});
        if (q == null || q.trim().isEmpty()) return c;
        final double lat = number(uri.getQueryParameter("lat"), Double.NaN);
        final double lon = number(uri.getQueryParameter("lon"), Double.NaN);
        final int limit = (int) Math.max(1, Math.min(10, number(uri.getQueryParameter("limit"), 5)));
        final List<Places.Place> found = Places.search(q.trim(), Double.isNaN(lon) ? Double.NaN
                : lat, lon, limit);
        for (Places.Place p : found) c.addRow(new Object[] {p.name, p.detail, p.lat, p.lon});
        return c;
    }

    private Cursor route(Uri uri) throws Exception {
        final double[] from = point(uri.getQueryParameter("from"));
        final double[] to = point(uri.getQueryParameter("to"));
        final MatrixCursor c = new MatrixCursor(new String[] {"distance", "duration"});
        if (from == null || to == null) return c;
        final String m = uri.getQueryParameter("mode");
        final int mode = "bike".equals(m) ? Router.BIKE : "foot".equals(m) ? Router.FOOT
                : Router.CAR;
        final Router.Route r = Router.route(getContext(), mode, from[0], from[1], to[0], to[1]);
        c.addRow(new Object[] {r.distance, r.duration});
        return c;
    }

    private static double[] point(String s) {
        if (s == null) return null;
        final String[] p = s.split(",");
        if (p.length != 2) return null;
        final double lat = number(p[0], Double.NaN), lon = number(p[1], Double.NaN);
        return Double.isNaN(lat) || Double.isNaN(lon) ? null : new double[] {lat, lon};
    }

    private static double number(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
