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
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

/**
 * The vector tiles of OpenFreeMap (OpenMapTiles schema, zoom 0-14): the TileJSON gives the
 * current, dated tile URLs (refreshed once a day); every tile fetched is kept on the phone, so
 * the places already seen open offline. The cache keeps the most recently used 256 MB.
 */
final class TileStore {

    static final int MAX_DATA_ZOOM = 14;
    static final String USER_AGENT = "Atlas/1.1 (KiroOS; Android 7.1; +https://github.com/nicholas2fma/kiroos-ota)";

    private static final String TAG = "Atlas";
    private static final String TILEJSON = "https://tiles.openfreemap.org/planet";
    private static final String FALLBACK = "https://tiles.openfreemap.org/planet/latest/{z}/{x}/{y}.pbf";
    private static final long TILEJSON_AGE = 24L * 3600 * 1000;
    private static final long REFRESH_AGE = 30L * 24 * 3600 * 1000;
    private static final long CACHE_LIMIT = 256L << 20;

    private final SharedPreferences mPrefs;
    private final File mDir;
    private volatile String mTemplate;
    private long mWritten;

    TileStore(Context context) {
        mPrefs = context.getSharedPreferences("atlas", Context.MODE_PRIVATE);
        mDir = new File(context.getFilesDir(), "tiles");
        mDir.mkdirs();
        mTemplate = mPrefs.getString("tile_template", FALLBACK);
    }

    /** Blocking: a fresh tile URL template, when the stored one is a day old; true if new. */
    boolean refreshTemplate() {
        if (System.currentTimeMillis() - mPrefs.getLong("tile_template_at", 0) < TILEJSON_AGE) {
            return false;
        }
        try {
            final JSONObject o = new JSONObject(new String(get(TILEJSON), "UTF-8"));
            final String t = o.getJSONArray("tiles").getString(0);
            if (t.contains("{z}")) {
                final boolean changed = !t.equals(mTemplate);
                mTemplate = t;
                mPrefs.edit().putString("tile_template", t)
                        .putLong("tile_template_at", System.currentTimeMillis()).apply();
                return changed;
            }
        } catch (Exception e) {
            Log.i(TAG, "TileJSON not refreshed: " + e);
        }
        return false;
    }

    private File file(int z, int x, int y) {
        return new File(mDir, z + "/" + x + "/" + y + ".pbf");
    }

    /** the cached tile, or null */
    byte[] cached(int z, int x, int y) {
        final File f = file(z, x, y);
        if (!f.isFile()) return null;
        try {
            final byte[] data = read(new FileInputStream(f));
            f.setLastModified(System.currentTimeMillis());
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    boolean stale(int z, int x, int y) {
        final File f = file(z, x, y);
        return !f.isFile() || System.currentTimeMillis() - f.lastModified() > REFRESH_AGE;
    }

    /** Blocking: the tile from the network, stored; null when offline */
    byte[] fetch(int z, int x, int y) {
        final String url = mTemplate.replace("{z}", String.valueOf(z))
                .replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
        try {
            final byte[] data = get(url);
            final File f = file(z, x, y);
            f.getParentFile().mkdirs();
            final File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            tmp.renameTo(f);
            synchronized (this) {
                mWritten += data.length;
                if (mWritten > 8L << 20) {
                    mWritten = 0;
                    trim();
                }
            }
            return data;
        } catch (Exception e) {
            // offline is normal: one line every 30 s at most
            final long now = System.currentTimeMillis();
            if (now - mLastError > 30000) {
                mLastError = now;
                Log.i(TAG, "tile " + z + "/" + x + "/" + y + " not fetched: " + e);
            }
            return null;
        }
    }

    private volatile long mLastError;

    /** the oldest tiles go once the cache is over its size */
    private void trim() {
        final java.util.List<File> all = new java.util.ArrayList<>();
        collect(mDir, all);
        long total = 0;
        for (File f : all) total += f.length();
        if (total <= CACHE_LIMIT) return;
        final File[] files = all.toArray(new File[0]);
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            if (total <= CACHE_LIMIT * 9 / 10) break;
            total -= f.length();
            f.delete();
        }
    }

    private static void collect(File dir, java.util.List<File> out) {
        final File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) collect(f, out);
            else out.add(f);
        }
    }

    static byte[] get(String url) throws Exception {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            if (c.getResponseCode() != 200) {
                // not an IOException: the server answered, the phone is not offline
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
