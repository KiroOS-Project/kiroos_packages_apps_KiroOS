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

package com.kiroos.pulse;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.LruCache;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Songs, albums and artists from the MediaStore; album art; stream playlists. */
final class Library {

    static final class Track {
        long id;
        Uri uri;
        String title;
        String artist;
        String album;
        long albumId;
        long duration;
        String mime;
        boolean stream;

        static Track stream(String url, String name) {
            final Track t = new Track();
            t.id = -1;
            t.uri = Uri.parse(url);
            t.title = name;
            t.artist = Uri.parse(url).getHost();
            t.album = "";
            t.stream = true;
            return t;
        }
    }

    static final class Group {
        long id;
        String name;
        String detail;
        long artAlbumId;
        int count;
    }

    private static final Uri ART = Uri.parse("content://media/external/audio/albumart");
    private static final LruCache<Long, Bitmap> ART_CACHE = new LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Library() {
    }

    static List<Track> songs(Context context, String where, String[] args) {
        final List<Track> out = new ArrayList<>();
        String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        if (where != null) {
            sel += " AND " + where;
        }
        try (Cursor c = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, new String[] {
                        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.TRACK},
                sel, args, where != null && where.contains("album_id")
                        ? MediaStore.Audio.Media.TRACK + ", " + MediaStore.Audio.Media.TITLE
                        : MediaStore.Audio.Media.TITLE + " COLLATE LOCALIZED")) {
            while (c != null && c.moveToNext()) {
                final Track t = new Track();
                t.id = c.getLong(0);
                t.uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, t.id);
                t.title = c.getString(1);
                t.artist = unknown(c.getString(2));
                t.album = unknown(c.getString(3));
                t.albumId = c.getLong(4);
                t.duration = c.getLong(5);
                t.mime = c.getString(6);
                out.add(t);
            }
        } catch (RuntimeException e) {
            // no storage permission
        }
        return out;
    }

    private static String unknown(String s) {
        return s == null || "<unknown>".equals(s) ? "" : s;
    }

    static List<Group> albums(List<Track> songs) {
        final Map<Long, Group> map = new LinkedHashMap<>();
        for (Track t : songs) {
            Group g = map.get(t.albumId);
            if (g == null) {
                g = new Group();
                g.id = t.albumId;
                g.name = t.album;
                g.detail = t.artist;
                g.artAlbumId = t.albumId;
                map.put(t.albumId, g);
            }
            g.count++;
        }
        final List<Group> out = new ArrayList<>(map.values());
        java.util.Collections.sort(out, byName());
        return out;
    }

    static List<Group> artists(List<Track> songs) {
        final Map<String, Group> map = new LinkedHashMap<>();
        for (Track t : songs) {
            final String key = t.artist.toLowerCase();
            Group g = map.get(key);
            if (g == null) {
                g = new Group();
                g.name = t.artist;
                g.artAlbumId = t.albumId;
                map.put(key, g);
            }
            g.count++;
        }
        final List<Group> out = new ArrayList<>(map.values());
        java.util.Collections.sort(out, byName());
        return out;
    }

    private static java.util.Comparator<Group> byName() {
        return new java.util.Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        };
    }

    /** Album cover, scaled to about {@code side} pixels; null when there is none. */
    static Bitmap art(Context context, long albumId, int side) {
        if (albumId <= 0) {
            return null;
        }
        final Bitmap cached = ART_CACHE.get(albumId * 10000 + side);
        if (cached != null) {
            return cached;
        }
        final Uri uri = ContentUris.withAppendedId(ART, albumId);
        try {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= side) {
                sample *= 2;
            }
            final BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            final Bitmap b;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, d);
            }
            if (b != null) {
                ART_CACHE.put(albumId * 10000 + side, b);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * .m3u / .pls playlists of web radios point to the real stream: returns the first
     * stream url, or the url itself when it is already a stream.
     */
    static String resolveStream(String url) {
        final String lower = url.toLowerCase();
        if (!lower.contains(".m3u") && !lower.contains(".pls")) {
            return url;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "PulseMusic/1.0 (KiroOS)");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.toLowerCase().startsWith("file") && line.contains("=")) {
                        line = line.substring(line.indexOf('=') + 1).trim(); // .pls
                    }
                    if (line.startsWith("http://") || line.startsWith("https://")) {
                        return line;
                    }
                }
            }
        } catch (Exception e) {
            return url;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return url;
    }
}
