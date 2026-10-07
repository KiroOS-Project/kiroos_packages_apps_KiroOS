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

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Place search with Photon (photon.komoot.io, OpenStreetMap data): fair use, at most one
 * request a second, results nearest the map first.
 */
final class Places {

    static final class Place {
        String name;
        String detail;
        double lat, lon;
    }

    private static final String API = "https://photon.komoot.io/";
    /** the languages Photon takes (any other is HTTP 400); without one, names are local */
    private static final String[] PHOTON_LANGS = {"en", "de", "fr"};
    private static long sLast;

    private Places() {
    }

    /** Blocking. */
    static List<Place> search(String query, double nearLat, double nearLon, int limit)
            throws Exception {
        final StringBuilder url = new StringBuilder(API).append("api/?q=")
                .append(URLEncoder.encode(query, "UTF-8")).append("&limit=").append(limit)
                .append(lang());
        if (!Double.isNaN(nearLat)) {
            url.append(String.format(Locale.US, "&lat=%.5f&lon=%.5f", nearLat, nearLon));
        }
        return parse(fetch(url.toString()), query);
    }

    /** Blocking: what is at a point (an address or a place), or null. */
    static Place reverse(double lat, double lon) throws Exception {
        final List<Place> l = parse(fetch(API + String.format(Locale.US,
                "reverse?lat=%.6f&lon=%.6f", lat, lon) + lang()), "");
        return l.isEmpty() ? null : l.get(0);
    }

    private static String lang() {
        final String lang = Locale.getDefault().getLanguage();
        for (String l : PHOTON_LANGS) {
            if (l.equals(lang)) return "&lang=" + l;
        }
        return "";
    }

    private static JSONObject fetch(String url) throws Exception {
        synchronized (Places.class) {
            final long wait = 1000 - (System.currentTimeMillis() - sLast);
            if (wait > 0) Thread.sleep(wait);
            sLast = System.currentTimeMillis();
        }
        return new JSONObject(new String(TileStore.get(url), "UTF-8"));
    }

    private static List<Place> parse(JSONObject o, String query) throws Exception {
        final JSONArray features = o.getJSONArray("features");
        final List<Place> out = new ArrayList<>();
        for (int i = 0; i < features.length(); i++) {
            final JSONObject f = features.getJSONObject(i);
            final JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
            final JSONObject p = f.getJSONObject("properties");
            final Place pl = new Place();
            pl.lon = c.getDouble(0);
            pl.lat = c.getDouble(1);
            String name = p.optString("name", "");
            final String street = p.optString("street", "");
            final String number = p.optString("housenumber", "");
            if (name.isEmpty()) name = street.isEmpty() ? query : (street + " " + number).trim();
            if (name.isEmpty()) name = p.optString("city", "");
            pl.name = name;
            final StringBuilder d = new StringBuilder();
            if (!street.isEmpty() && !name.startsWith(street)) {
                d.append((street + " " + number).trim()).append(", ");
            }
            for (String k : new String[] {"city", "state", "country"}) {
                final String v = p.optString(k, "");
                if (!v.isEmpty() && !v.equals(name)) d.append(v).append(", ");
            }
            pl.detail = d.length() > 2 ? d.substring(0, d.length() - 2).trim() : "";
            out.add(pl);
        }
        return out;
    }
}
