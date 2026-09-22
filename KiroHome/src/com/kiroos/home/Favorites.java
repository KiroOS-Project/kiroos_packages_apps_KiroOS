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
    /** default packages already placed once */
    private static final String KEY_PLACED = "placed";

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

    /**
     * Places the KiroOS suite on the home: everything on first run, and later each default
     * app the first time it shows up installed (a suite app added by an update, or a layout
     * seeded while some apps were missing). An app is placed only once, so one the user
     * removed from the home stays removed.
     */
    void seedDefaults(AppRepository repo) {
        if (repo.apps().isEmpty()) {
            return;
        }
        final boolean firstRun = !mPrefs.getBoolean(KEY_SEEDED, false);
        final List<String> placed = firstRun ? new ArrayList<String>() : read(KEY_PLACED);
        if (firstRun) {
            write(KEY_DOCK, new ArrayList<String>());
            write(KEY_GRID, new ArrayList<String>());
        }
        final List<String> dock = dock();
        final List<String> grid = grid();
        boolean changed = firstRun;
        for (String[] defaults : new String[][] {DEFAULT_DOCK, DEFAULT_GRID}) {
            for (String pkg : defaults) {
                final String key = keyFor(repo, pkg);
                if (key == null || placed.contains(pkg)) {
                    continue;
                }
                placed.add(pkg);
                changed = true;
                if (dock.contains(key) || grid.contains(key)) {
                    continue;
                }
                if (defaults == DEFAULT_DOCK && dock.size() < HomeActivity.DOCK_SIZE) {
                    dock.add(key);
                } else if (grid.size() < HomeActivity.GRID_COLUMNS * HomeActivity.GRID_ROWS) {
                    grid.add(key);
                }
            }
        }
        if (changed) {
            write(KEY_DOCK, dock);
            write(KEY_GRID, grid);
            write(KEY_PLACED, placed);
            mPrefs.edit().putBoolean(KEY_SEEDED, true).apply();
        }
    }

    private static String keyFor(AppRepository repo, String pkg) {
        for (AppInfo app : repo.apps()) {
            if (app.component.getPackageName().equals(pkg)) {
                return app.key();
            }
        }
        return null;
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
