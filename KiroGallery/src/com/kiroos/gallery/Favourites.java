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

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kiro Gallery 1.2: favourite photos and videos (the heart in the viewer). Kept in the app's
 * preferences by MediaStore id; they show as the Favourites album, first in Albums.
 */
final class Favourites {

    /** the album id of Favourites: MediaStore bucket ids are ints, this can't be one */
    static final long ALBUM_ID = Long.MIN_VALUE;

    private Favourites() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("favourites", Context.MODE_PRIVATE);
    }

    private static String key(Media.Item i) {
        return (i.video ? "v" : "i") + i.id;
    }

    static Set<String> all(Context context) {
        return new HashSet<>(prefs(context).getStringSet("items", Collections.<String>emptySet()));
    }

    static boolean is(Context context, Media.Item i) {
        return i.id >= 0 && all(context).contains(key(i));
    }

    static void set(Context context, Media.Item i, boolean favourite) {
        if (i.id < 0) {
            return;
        }
        final Set<String> s = all(context);
        if (favourite) {
            s.add(key(i));
        } else {
            s.remove(key(i));
        }
        prefs(context).edit().putStringSet("items", s).apply();
    }

    /** the favourites among items, in the same (newest first) order */
    static List<Media.Item> filter(List<Media.Item> items, Set<String> favourites) {
        final List<Media.Item> out = new ArrayList<>();
        if (favourites.isEmpty()) {
            return out;
        }
        for (Media.Item i : items) {
            if (favourites.contains(key(i))) {
                out.add(i);
            }
        }
        return out;
    }
}
