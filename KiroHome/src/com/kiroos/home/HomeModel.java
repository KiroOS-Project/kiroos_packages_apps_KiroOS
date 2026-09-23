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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Where every app sits on the home: pages of COLUMNS columns and up to MAX_ROWS rows, and a
 * dock of DOCK_SIZE slots, each cell holding an app key ("package/.Class") or nothing, so
 * apps keep the exact cell the user dropped them in. Saved as JSON.
 *
 * Rows count from the bottom (index = row * COLUMNS + column, row 0 right above the dock):
 * the grid is anchored to the dock and a page shows as many rows as fit on it. The first
 * page has fewer rows while the clock and the widgets sit at its top; the other pages reach
 * the top of the screen. How many rows each page shows is told by the view (setRows); apps
 * in rows that no longer fit move to the next free cell (fitRows).
 *
 * The first page stays calm: the dock holds the everyday apps, the first page a few, the
 * rest of the KiroOS suite waits on the second page and in the drawer.
 */
final class HomeModel {

    static final int COLUMNS = 4;
    static final int MAX_ROWS = 6;
    static final int PAGE_SIZE = COLUMNS * MAX_ROWS;
    static final int DOCK_SIZE = 4;
    /** container of a location: the dock, or a page index (0, 1, ...) */
    static final int DOCK = -1;

    private static final String KEY_LAYOUT = "layout";
    /** 2: rows from the bottom, 24 cells a page (1: 4 x 4 from the top) */
    private static final int VERSION = 2;

    private static final String[] DEFAULT_DOCK = {
            "com.kiroos.phone", "com.kiroos.messages", "com.kiroos.surge", "com.kiroos.camera",
    };
    /** first page, bottom row */
    private static final String[] DEFAULT_PAGE1 = {
            "com.kiroos.gallery", "com.kiroos.pulse", "com.kiroos.calendar", "com.kiroos.kai",
    };
    /** second page, from its top row: the tools */
    private static final String[] DEFAULT_PAGE2 = {
            "com.kiroos.files", "com.kiroos.clock", "com.kiroos.calculator", "com.kiroos.contacts",
            "com.kiroos.recorder", "com.android.settings",
    };

    /** A cell: container (DOCK or page) and index inside it. */
    static final class Loc {
        final int container;
        final int index;

        Loc(int container, int index) {
            this.container = container;
            this.index = index;
        }

        int row() {
            return index / COLUMNS;
        }

        int column() {
            return index % COLUMNS;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Loc && ((Loc) o).container == container && ((Loc) o).index == index;
        }

        @Override
        public int hashCode() {
            return container * 31 + index;
        }
    }

    private final SharedPreferences mPrefs;
    private final String[] mDock = new String[DOCK_SIZE];
    private final List<String[]> mPages = new ArrayList<>();
    private boolean mLoaded;
    /** rows shown on the first page and on the others (until the view says, the classic 4) */
    private int mFirstRows = 4;
    private int mRows = 5;

    HomeModel(SharedPreferences prefs) {
        mPrefs = prefs;
        Arrays.fill(mDock, "");
    }

    int pageCount() {
        return mPages.size();
    }

    /** rows the given page shows */
    int rows(int page) {
        return page == 0 ? mFirstRows : mRows;
    }

    /** the view's grid: rows on the first page and on the others */
    void setRows(int firstRows, int rows) {
        mFirstRows = Math.max(1, Math.min(MAX_ROWS, firstRows));
        mRows = Math.max(mFirstRows, Math.min(MAX_ROWS, rows));
    }

    String at(Loc loc) {
        if (loc.container == DOCK) {
            return mDock[loc.index];
        }
        return loc.container < mPages.size() ? mPages.get(loc.container)[loc.index] : "";
    }

    /** where an app is on the home, or null */
    Loc find(String key) {
        for (int i = 0; i < DOCK_SIZE; i++) {
            if (key.equals(mDock[i])) {
                return new Loc(DOCK, i);
            }
        }
        for (int p = 0; p < mPages.size(); p++) {
            final String[] page = mPages.get(p);
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (key.equals(page[i])) {
                    return new Loc(p, i);
                }
            }
        }
        return null;
    }

    /**
     * Puts an app at a cell. If the app is already on the home it moves there; an app that
     * was in the target cell takes the place the moved app left (or, for a new app, the next
     * free cell).
     */
    void place(String key, Loc to) {
        ensurePage(to.container);
        final Loc from = find(key);
        if (to.equals(from)) {
            return;
        }
        final String displaced = at(to);
        set(to, key);
        if (from != null) {
            set(from, displaced);
        } else if (!displaced.isEmpty()) {
            final Loc free = firstFree(to.container == DOCK ? 0 : to.container, true);
            if (free != null) {
                set(free, displaced);
            }
        }
        trimPages();
        save();
    }

    /** adds an app to the first free cell from the first page on */
    boolean add(String key) {
        if (find(key) != null) {
            return true;
        }
        final Loc free = firstFree(0, true);
        if (free == null) {
            return false;
        }
        ensurePage(free.container);
        set(free, key);
        save();
        return true;
    }

    void remove(String key) {
        final Loc at = find(key);
        if (at != null) {
            set(at, "");
            trimPages();
            save();
        }
    }

    /**
     * Apps in rows a page no longer shows (the clock came back, a widget was added, a smaller
     * screen) move to the next free cell; true if anything moved.
     */
    boolean fitRows() {
        boolean moved = false;
        for (int p = 0; p < mPages.size(); p++) {
            final String[] page = mPages.get(p);
            for (int i = rows(p) * COLUMNS; i < PAGE_SIZE; i++) {
                if (page[i].isEmpty()) {
                    continue;
                }
                final String key = page[i];
                page[i] = "";
                final Loc free = firstFree(p, true);
                ensurePage(free.container);
                set(free, key);
                moved = true;
            }
        }
        if (moved) {
            trimPages();
            save();
        }
        return moved;
    }

    /** empties cells whose app is gone (uninstalled or disabled) */
    void prune(AppRepository repo) {
        boolean changed = false;
        for (int i = 0; i < DOCK_SIZE; i++) {
            if (!mDock[i].isEmpty() && repo.find(mDock[i]) == null) {
                mDock[i] = "";
                changed = true;
            }
        }
        for (String[] page : mPages) {
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (!page[i].isEmpty() && repo.find(page[i]) == null) {
                    page[i] = "";
                    changed = true;
                }
            }
        }
        if (changed) {
            trimPages();
            save();
        }
    }

    /** makes sure page p exists (dropping on the page after the last one creates it) */
    void ensurePage(int p) {
        while (p >= 0 && mPages.size() <= p) {
            final String[] page = new String[PAGE_SIZE];
            Arrays.fill(page, "");
            mPages.add(page);
        }
    }

    /**
     * The first free visible cell from page p on, reading order (top row first, left to
     * right); with orNewPage the first cell of a new page when all are full.
     */
    private Loc firstFree(int p, boolean orNewPage) {
        for (int page = Math.max(0, p); page < mPages.size(); page++) {
            final String[] cells = mPages.get(page);
            for (int row = rows(page) - 1; row >= 0; row--) {
                for (int col = 0; col < COLUMNS; col++) {
                    final int i = row * COLUMNS + col;
                    if (cells[i].isEmpty()) {
                        return new Loc(page, i);
                    }
                }
            }
        }
        if (!orNewPage) {
            return null;
        }
        final int page = Math.max(mPages.size(), p);
        return new Loc(page, (rows(page) - 1) * COLUMNS);
    }

    private void set(Loc loc, String key) {
        if (loc.container == DOCK) {
            mDock[loc.index] = key;
        } else {
            mPages.get(loc.container)[loc.index] = key;
        }
    }

    /** empty pages go, except the first one */
    private void trimPages() {
        for (int p = mPages.size() - 1; p >= 1; p--) {
            boolean empty = true;
            for (String k : mPages.get(p)) {
                if (!k.isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                mPages.remove(p);
            }
        }
        if (mPages.isEmpty()) {
            ensurePage(0);
        }
    }

    // --- load / first layout -----------------------------------------------------------------

    /** Loads the saved layout; on first run (or from Kiro Home 1.0) builds the default one. */
    void load(AppRepository repo) {
        if (mLoaded || repo.apps().isEmpty()) {
            return;
        }
        mLoaded = true;
        final String json = mPrefs.getString(KEY_LAYOUT, null);
        if (json != null) {
            try {
                final JSONObject o = new JSONObject(json);
                final int version = o.optInt("v", 1);
                final JSONArray dock = o.getJSONArray("dock");
                for (int i = 0; i < DOCK_SIZE && i < dock.length(); i++) {
                    mDock[i] = dock.getString(i);
                }
                final JSONArray pages = o.getJSONArray("pages");
                for (int p = 0; p < pages.length(); p++) {
                    final JSONArray cells = pages.getJSONArray(p);
                    final String[] page = new String[PAGE_SIZE];
                    Arrays.fill(page, "");
                    for (int i = 0; i < cells.length(); i++) {
                        final String key = cells.getString(i);
                        if (version >= 2) {
                            if (i < PAGE_SIZE) {
                                page[i] = key;
                            }
                        } else if (i < 16) {
                            // 1: 4 x 4 from the top, bottom-anchored: same place on screen
                            page[(3 - i / COLUMNS) * COLUMNS + i % COLUMNS] = key;
                        }
                    }
                    mPages.add(page);
                }
                trimPages();
                if (version < VERSION) {
                    save();
                }
                return;
            } catch (JSONException e) {
                Arrays.fill(mDock, "");
                mPages.clear();
            }
        }
        buildDefault(repo);
        save();
    }

    private void buildDefault(AppRepository repo) {
        ensurePage(1);
        for (int i = 0; i < DEFAULT_DOCK.length; i++) {
            mDock[i] = keyFor(repo, DEFAULT_DOCK[i]);
        }
        // first page: the bottom row, right above the dock
        for (int i = 0; i < DEFAULT_PAGE1.length; i++) {
            mPages.get(0)[i] = keyFor(repo, DEFAULT_PAGE1[i]);
        }
        // second page: from its top row (fitRows moves them if the screen has fewer)
        final int top = (mRows - 1) * COLUMNS;
        for (int i = 0; i < DEFAULT_PAGE2.length; i++) {
            final int row = i / COLUMNS;
            mPages.get(1)[top - row * COLUMNS + i % COLUMNS] = keyFor(repo, DEFAULT_PAGE2[i]);
        }
        // Kiro Home 1.0 put everything on one page: keep the user's own apps (not the
        // suite, which now has its place) on the first page, from the row above
        final String old = mPrefs.getString("grid", "");
        if (!TextUtils.isEmpty(old)) {
            for (String k : old.split("\\|")) {
                if (k.isEmpty() || k.startsWith("com.kiroos.") || find(k) != null
                        || repo.find(k) == null) {
                    continue;
                }
                add(k);
            }
            mPrefs.edit().remove("grid").remove("dock").remove("seeded").remove("placed").apply();
        }
        trimPages();
    }

    private static String keyFor(AppRepository repo, String pkg) {
        for (AppInfo app : repo.apps()) {
            if (app.component.getPackageName().equals(pkg)) {
                return app.key();
            }
        }
        return "";
    }

    private void save() {
        try {
            final JSONArray pages = new JSONArray();
            for (String[] page : mPages) {
                pages.put(new JSONArray(Arrays.asList(page)));
            }
            mPrefs.edit().putString(KEY_LAYOUT, new JSONObject()
                    .put("v", VERSION)
                    .put("dock", new JSONArray(Arrays.asList(mDock)))
                    .put("pages", pages).toString()).apply();
        } catch (JSONException e) {
            // cannot happen with strings and arrays
        }
    }
}
