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

package com.kiroos.gallery;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.MediaStore.Files.FileColumns;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Photos and videos from the MediaStore, newest first, and their albums (folders). */
final class Media {

    static final class Item implements java.io.Serializable {
        long id;
        boolean video;
        String path;
        String name;
        String mime;
        long date;
        long size;
        long duration;
        int width;
        int height;
        int orientation;
        long bucketId;
        String bucket;

        Uri uri() {
            return ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    static final class Album {
        long id;
        String name;
        Item cover;
        int count;
    }

    static final int FILTER_ALL = 0;
    static final int FILTER_IMAGES = 1;
    static final int FILTER_VIDEOS = 2;

    private Media() {
    }

    /** All media (optionally of one album), newest first. */
    static List<Item> query(Context context, long bucketId, int filter) {
        final List<Item> out = new ArrayList<>();
        final Uri files = MediaStore.Files.getContentUri("external");
        final StringBuilder where = new StringBuilder();
        if (filter == FILTER_IMAGES) {
            where.append(FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_IMAGE);
        } else if (filter == FILTER_VIDEOS) {
            where.append(FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_VIDEO);
        } else {
            where.append("(" + FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_IMAGE
                    + " OR " + FileColumns.MEDIA_TYPE + "=" + FileColumns.MEDIA_TYPE_VIDEO + ")");
        }
        if (bucketId != 0) {
            where.append(" AND bucket_id=").append(bucketId);
        }
        try (Cursor c = context.getContentResolver().query(files, new String[] {
                FileColumns._ID, FileColumns.MEDIA_TYPE, FileColumns.DATA, FileColumns.DISPLAY_NAME,
                FileColumns.MIME_TYPE, "datetaken", FileColumns.DATE_MODIFIED, FileColumns.SIZE,
                "duration", FileColumns.WIDTH, FileColumns.HEIGHT, "orientation", "bucket_id",
                "bucket_display_name"}, where.toString(), null,
                "COALESCE(datetaken, date_modified * 1000) DESC")) {
            while (c != null && c.moveToNext()) {
                final Item i = new Item();
                i.id = c.getLong(0);
                i.video = c.getInt(1) == FileColumns.MEDIA_TYPE_VIDEO;
                i.path = c.getString(2);
                i.name = c.getString(3);
                i.mime = c.getString(4);
                i.date = c.isNull(5) ? c.getLong(6) * 1000 : c.getLong(5);
                i.size = c.getLong(7);
                i.duration = c.getLong(8);
                i.width = c.getInt(9);
                i.height = c.getInt(10);
                i.orientation = c.getInt(11);
                i.bucketId = c.getLong(12);
                i.bucket = c.getString(13);
                out.add(i);
            }
        } catch (RuntimeException e) {
            // no storage permission yet
        }
        return out;
    }

    /** Albums, the camera first, then by most recent content. */
    static List<Album> albums(List<Item> all) {
        final Map<Long, Album> map = new LinkedHashMap<>();
        for (Item i : all) {
            Album a = map.get(i.bucketId);
            if (a == null) {
                a = new Album();
                a.id = i.bucketId;
                a.name = i.bucket != null ? i.bucket : "";
                a.cover = i;
                map.put(i.bucketId, a);
            }
            a.count++;
        }
        final List<Album> out = new ArrayList<>(map.values());
        for (int k = 0; k < out.size(); k++) {
            if ("Camera".equalsIgnoreCase(out.get(k).name)) {
                out.add(0, out.remove(k));
                break;
            }
        }
        return out;
    }
}
