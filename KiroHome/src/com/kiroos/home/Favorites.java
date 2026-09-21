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

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The apps pinned on the home grid and in the dock, as ordered "package/.Class" keys.
 * On first run the KiroOS suite is placed there.
 */
final class Favorites {

    private static final String KEY_GRID = "grid";
    private static final String KEY_DOCK = "dock";
    private static final String KEY_SEEDED = "seeded";

    private static final String[] DEFAULT_DOCK = {
            "com.kiroos.phone", "com.kiroos.messages", "com.kiroos.surge", "com.kiroos.camera",
    };
    private static final String[] DEFAULT_GRID = {
            "com.kiroos.gallery", "com.kiroos.pulse", "com.kiroos.files", "com.kiroos.calendar",
            "com.kiroos.clock", "com.kiroos.calculator", "com.kiroos.contacts", "com.kiroos.recorder",
            "com.kiroos.kai", "com.android.settings", "com.android.vending", "org.telegram.messenger",
    };

    private final SharedPreferences mPrefs;

    Favorites(SharedPreferences prefs) {
        mPrefs = prefs;
    }

    List<String> grid() {
        return read(KEY_GRID);
    }

    List<String> dock() {
        return read(KEY_DOCK);
    }

    boolean contains(String key) {
        return grid().contains(key) || dock().contains(key);
    }

    void remove(String key) {
        final List<String> grid = grid();
        final List<String> dock = dock();
        grid.remove(key);
        dock.remove(key);
        write(KEY_GRID, grid);
        write(KEY_DOCK, dock);
    }

    boolean addToGrid(String key, int max) {
        final List<String> grid = grid();
        if (grid.contains(key)) {
            return true;
        }
        if (grid.size() >= max) {
            return false;
        }
        grid.add(key);
        write(KEY_GRID, grid);
        return true;
    }

    /** Puts the app in the dock; when full it replaces the last one, which goes to the grid. */
    void addToDock(String key, int max) {
        final List<String> dock = dock();
        final List<String> grid = grid();
        if (dock.contains(key)) {
            return;
        }
        grid.remove(key);
        if (dock.size() >= max) {
            final String out = dock.remove(dock.size() - 1);
            if (!grid.contains(out)) {
                grid.add(0, out);
            }
        }
        dock.add(key);
        write(KEY_DOCK, dock);
        write(KEY_GRID, grid);
    }

    /** First run: the KiroOS suite on the home, as far as those apps are installed. */
    void seedDefaults(AppRepository repo) {
        if (mPrefs.getBoolean(KEY_SEEDED, false) || repo.apps().isEmpty()) {
            return;
        }
        write(KEY_DOCK, keysFor(repo, DEFAULT_DOCK));
        write(KEY_GRID, keysFor(repo, DEFAULT_GRID));
        mPrefs.edit().putBoolean(KEY_SEEDED, true).apply();
    }

    private static List<String> keysFor(AppRepository repo, String[] packages) {
        final List<String> keys = new ArrayList<>();
        for (String pkg : packages) {
            for (AppInfo app : repo.apps()) {
                if (app.component.getPackageName().equals(pkg)) {
                    keys.add(app.key());
                    break;
                }
            }
        }
        return keys;
    }

    private List<String> read(String key) {
        final String s = mPrefs.getString(key, "");
        if (TextUtils.isEmpty(s)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(s.split("\\|")));
    }

    private void write(String key, List<String> values) {
        mPrefs.edit().putString(key, TextUtils.join("|", values)).apply();
    }
}
