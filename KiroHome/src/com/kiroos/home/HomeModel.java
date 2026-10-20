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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * A cell can also hold a folder ("folder:<id>"): a named list of apps, made by dropping an
 * app onto another one. A folder left with one app turns back into that app.
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

    /**
     * The first layout, the one the KiroOS team uses: cells as {page, row from the bottom,
     * column, package}. Apps that are not installed leave their cell empty.
     */
    private static final Object[][] DEFAULT_CELLS = {
            // KiroOS 3.0: only system apps, in full rows. First page, the everyday apps in the
            // two rows above the dock
            {0, 1, 0, "com.kiroos.gallery"},
            {0, 1, 1, "com.google.android.apps.maps"},
            {0, 1, 2, "com.kiroos.calendar"},
            {0, 1, 3, "com.kiroos.clock"},
            {0, 0, 0, "com.kiroos.kai"},
            {0, 0, 1, "com.kiroos.store"},
            {0, 0, 2, "com.android.settings"},
            {0, 0, 3, "com.kiroos.files"},
            // second page, the tools, from the same rows
            {1, 1, 0, "com.kiroos.contacts"},
            {1, 1, 1, "com.kiroos.calculator"},
            {1, 1, 2, "com.kiroos.recorder"},
            {1, 1, 3, "com.kiroos.pulse"},
            // Beta 4.1: Canvas has no icon any more (hold the home > Wallpaper)
    };

    static final String FOLDER_PREFIX = "folder:";

    /*
     * Beta 4: widgets live in the grid like apps, anywhere on any page. Its bottom-left cell
     * holds "widget:<id>:<rows>:<cols>", every other cell it covers holds "+widget:<id>" (not
     * empty, so no app is placed there). Beta 4.1: a widget can be half the width (2 columns)
     * as well as full width; Beta 4 keys ("widget:<id>:<rows>") are full width.
     */
    static final String WIDGET_PREFIX = "widget:";
    static final String WIDGET_PART_PREFIX = "+widget:";
    /** Beta 4.1: the home's own clock is a widget of the grid too (movable, three sizes) */
    static final int CLOCK_ID = -2;

    static boolean isWidget(String key) {
        return key != null && key.startsWith(WIDGET_PREFIX);
    }

    static boolean isWidgetPart(String key) {
        return key != null && key.startsWith(WIDGET_PART_PREFIX);
    }

    /** a widget or a cell a widget covers */
    static boolean isWidgetCell(String key) {
        return isWidget(key) || isWidgetPart(key);
    }

    static int widgetId(String key) {
        try {
            final String s = key.substring(isWidgetPart(key) ? WIDGET_PART_PREFIX.length()
                    : WIDGET_PREFIX.length());
            final int colon = s.indexOf(':');
            return Integer.parseInt(colon >= 0 ? s.substring(0, colon) : s);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static int widgetRows(String key) {
        final String[] p = widgetParts(key);
        try {
            return p.length > 1 ? Math.max(1, Integer.parseInt(p[1])) : 1;
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** how many columns a widget takes (Beta 4 widgets: all of them) */
    static int widgetCols(String key) {
        final String[] p = widgetParts(key);
        try {
            return p.length > 2
                    ? Math.max(1, Math.min(COLUMNS, Integer.parseInt(p[2]))) : COLUMNS;
        } catch (RuntimeException e) {
            return COLUMNS;
        }
    }

    /** "widget:<id>:<rows>[:<cols>]" -> {id, rows, cols} as text */
    private static String[] widgetParts(String key) {
        if (!isWidget(key)) return new String[0];
        return key.substring(WIDGET_PREFIX.length()).split(":");
    }

    private static String widgetKey(int id, int rows, int cols) {
        return WIDGET_PREFIX + id + ":" + rows + ":" + cols;
    }

    /** where a widget is (its bottom-left cell), or null */
    Loc findWidget(int id) {
        for (int p = 0; p < mPages.size(); p++) {
            final String[] page = mPages.get(p);
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (isWidget(page[i]) && widgetId(page[i]) == id) {
                    return new Loc(p, i);
                }
            }
        }
        return null;
    }

    /** every widget id on the home */
    List<Integer> widgetIds() {
        final List<Integer> ids = new ArrayList<>();
        for (String[] page : mPages) {
            for (String k : page) {
                if (isWidget(k)) ids.add(widgetId(k));
            }
        }
        return ids;
    }

    private void clearWidget(int id) {
        for (String[] page : mPages) {
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (isWidgetCell(page[i]) && widgetId(page[i]) == id) {
                    page[i] = "";
                }
            }
        }
    }

    void removeWidget(int id) {
        clearWidget(id);
        trimPages();
        save();
    }

    /**
     * Puts a widget of rows x cols cells with its bottom-left cell at (bottomRow, col) on a
     * page (clamped to the rows the page shows). Apps in those cells move to the next free
     * cells; another widget in the way refuses the drop (false). The widget leaves wherever it
     * was.
     */
    boolean placeWidget(int id, int rows, int cols, int page, int bottomRow, int col) {
        if (page < 0) return false;
        ensurePage(page);
        final int shown = rows(page);
        rows = Math.max(1, Math.min(shown, rows));
        cols = Math.max(1, Math.min(COLUMNS, cols));
        bottomRow = Math.max(0, Math.min(shown - rows, bottomRow));
        col = Math.max(0, Math.min(COLUMNS - cols, col));
        final String[] cells = mPages.get(page);
        for (int r = bottomRow; r < bottomRow + rows; r++) {
            for (int c = col; c < col + cols; c++) {
                final String k = cells[r * COLUMNS + c];
                if (isWidgetCell(k) && widgetId(k) != id) {
                    return false;
                }
            }
        }
        clearWidget(id);
        final List<String> displaced = new ArrayList<>();
        for (int r = bottomRow; r < bottomRow + rows; r++) {
            for (int c = col; c < col + cols; c++) {
                final int i = r * COLUMNS + c;
                if (!cells[i].isEmpty()) displaced.add(cells[i]);
                cells[i] = (r == bottomRow && c == col)
                        ? widgetKey(id, rows, cols) : WIDGET_PART_PREFIX + id;
            }
        }
        for (String k : displaced) {
            final Loc free = firstFree(page, true);
            ensurePage(free.container);
            set(free, k);
        }
        trimPages();
        save();
        return true;
    }

    /**
     * A new widget: the first free rows x cols place, from the given page on, top rows first,
     * left first; with none, the top of the given page (its apps move on).
     */
    void addWidget(int id, int rows, int cols, int fromPage) {
        cols = Math.max(1, Math.min(COLUMNS, cols));
        for (int p = Math.max(0, fromPage); p < mPages.size(); p++) {
            final int shown = rows(p);
            for (int bottom = shown - rows; bottom >= 0; bottom--) {
                for (int col = 0; col + cols <= COLUMNS; col += cols) {
                    if (isFree(p, bottom, rows, col, cols)) {
                        placeWidget(id, rows, cols, p, bottom, col);
                        return;
                    }
                }
            }
        }
        final int page = Math.max(0, Math.min(fromPage, mPages.size() - 1));
        if (!placeWidget(id, rows, cols, page, rows(page) - rows, 0)) {
            // a widget there too: a page of its own
            placeWidget(id, rows, cols, mPages.size(), rows(mPages.size()) - rows, 0);
        }
    }

    /** true when the cells of a rows x cols box are all empty */
    private boolean isFree(int page, int bottom, int rows, int col, int cols) {
        if (bottom < 0 || bottom + rows > rows(page) || col < 0 || col + cols > COLUMNS) {
            return false;
        }
        final String[] cells = mPages.get(page);
        for (int r = bottom; r < bottom + rows; r++) {
            for (int c = col; c < col + cols; c++) {
                if (!cells[r * COLUMNS + c].isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * Beta 4.1: a widget changes size where it is. It keeps its bottom-left corner when it
     * can (a half-width widget on the right that grows to full width moves to the left
     * edge); apps in the way move on. False if another widget is in the way.
     */
    boolean resizeWidget(int id, int rows, int cols) {
        final Loc at = findWidget(id);
        if (at == null) return false;
        final int col = Math.min(at.column(), COLUMNS - Math.max(1, Math.min(COLUMNS, cols)));
        return placeWidget(id, rows, cols, at.container, at.row(), col);
    }

    /** A named group of apps in one cell. */
    static final class Folder {
        String name;
        final List<String> items = new ArrayList<>();
    }

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
    private final Map<String, Folder> mFolders = new LinkedHashMap<>();
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

    /** the saved layout (or the first one) is in */
    boolean isLoaded() {
        return mLoaded;
    }

    static boolean isFolder(String key) {
        return key != null && key.startsWith(FOLDER_PREFIX);
    }

    Folder folder(String key) {
        return mFolders.get(key);
    }

    /** the folder an app is in, or null */
    String folderOf(String appKey) {
        for (Map.Entry<String, Folder> e : mFolders.entrySet()) {
            if (e.getValue().items.contains(appKey)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** true if the app is on the home, in a cell or in a folder */
    boolean onHome(String appKey) {
        return find(appKey) != null || folderOf(appKey) != null;
    }

    /**
     * Drops an app onto the cell "to": into the folder there, or together with the app there
     * into a new folder (named name). The app leaves wherever it was. False if there is
     * nothing to merge with (empty cell, the app itself, a folder dragged onto something).
     */
    boolean merge(String appKey, Loc to, String name) {
        final String target = at(to);
        if (target.isEmpty() || target.equals(appKey) || isFolder(appKey)
                || to.container == DOCK || isWidgetCell(target) || isWidgetCell(appKey)) {
            return false;
        }
        if (target.equals(folderOf(appKey))) {
            return true; // dropped back onto its own folder
        }
        // leave the old place first (a folder it leaves may turn into its last app, which
        // cannot be the target: the target is another cell)
        final Loc from = find(appKey);
        if (from != null) {
            set(from, "");
        } else {
            final String old = folderOf(appKey);
            if (old != null) {
                removeFromFolder(old, appKey);
            }
        }
        if (isFolder(target)) {
            mFolders.get(target).items.add(appKey);
        } else {
            final Folder f = new Folder();
            f.name = name;
            f.items.add(target);
            f.items.add(appKey);
            String key;
            int n = mFolders.size();
            do {
                key = FOLDER_PREFIX + (++n);
            } while (mFolders.containsKey(key));
            mFolders.put(key, f);
            set(to, key);
        }
        trimPages();
        save();
        return true;
    }

    /** takes an app out of a folder; a folder left with one app becomes that app */
    void removeFromFolder(String folderKey, String appKey) {
        final Folder f = mFolders.get(folderKey);
        if (f == null) {
            return;
        }
        f.items.remove(appKey);
        dissolveIfSmall(folderKey);
        trimPages();
        save();
    }

    void rename(String folderKey, String name) {
        final Folder f = mFolders.get(folderKey);
        if (f != null && !TextUtils.equals(f.name, name)) {
            f.name = name;
            save();
        }
    }

    private void dissolveIfSmall(String folderKey) {
        final Folder f = mFolders.get(folderKey);
        if (f == null || f.items.size() > 1) {
            return;
        }
        final Loc at = find(folderKey);
        mFolders.remove(folderKey);
        if (at != null) {
            set(at, f.items.isEmpty() ? "" : f.items.get(0));
        }
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
        // a widget's cells are taken: an app dropped there goes back where it was
        if (isWidgetCell(at(to)) || isWidgetCell(key)) {
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
        final String folder = folderOf(key);
        if (folder != null) {
            removeFromFolder(folder, key);
            return;
        }
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
        // widgets reaching into rows a page no longer shows come down to fit (apps move on)
        for (int p = 0; p < mPages.size(); p++) {
            final String[] page = mPages.get(p);
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (!isWidget(page[i])) continue;
                final int rows = widgetRows(page[i]);
                if (i / COLUMNS + rows > rows(p)) {
                    placeWidget(widgetId(page[i]), rows, widgetCols(page[i]), p,
                            rows(p) - rows, i % COLUMNS);
                    moved = true;
                }
            }
        }
        for (int p = 0; p < mPages.size(); p++) {
            final String[] page = mPages.get(p);
            for (int i = rows(p) * COLUMNS; i < PAGE_SIZE; i++) {
                if (page[i].isEmpty() || isWidgetCell(page[i])) {
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
            if (!mDock[i].isEmpty() && !isFolder(mDock[i]) && repo.find(mDock[i]) == null) {
                mDock[i] = "";
                changed = true;
            }
        }
        for (Iterator<Map.Entry<String, Folder>> it = mFolders.entrySet().iterator();
                it.hasNext(); ) {
            final Map.Entry<String, Folder> e = it.next();
            if (find(e.getKey()) == null) {
                it.remove(); // a folder no cell holds
                changed = true;
                continue;
            }
            for (Iterator<String> items = e.getValue().items.iterator(); items.hasNext(); ) {
                if (repo.find(items.next()) == null) {
                    items.remove();
                    changed = true;
                }
            }
        }
        for (String key : new ArrayList<>(mFolders.keySet())) {
            dissolveIfSmall(key);
        }
        for (String[] page : mPages) {
            for (int i = 0; i < PAGE_SIZE; i++) {
                if (!page[i].isEmpty() && !isFolder(page[i]) && !isWidgetCell(page[i])
                        && repo.find(page[i]) == null) {
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
                final JSONObject folders = o.optJSONObject("folders");
                if (folders != null) {
                    final Iterator<String> keys = folders.keys();
                    while (keys.hasNext()) {
                        final String key = keys.next();
                        final JSONObject fo = folders.getJSONObject(key);
                        final Folder f = new Folder();
                        f.name = fo.optString("name", "");
                        final JSONArray items = fo.getJSONArray("items");
                        for (int i = 0; i < items.length(); i++) {
                            f.items.add(items.getString(i));
                        }
                        mFolders.put(key, f);
                    }
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

    /** true once, right after the first layout of a new phone was made (no earlier home) */
    private boolean mFreshInstall;

    /** a new phone's first home: the default widgets go in too (read once) */
    boolean takeFreshInstall() {
        final boolean f = mFreshInstall;
        mFreshInstall = false;
        return f;
    }

    private void buildDefault(AppRepository repo) {
        mFreshInstall = TextUtils.isEmpty(mPrefs.getString("grid", ""));
        ensurePage(1);
        for (int i = 0; i < DEFAULT_DOCK.length; i++) {
            mDock[i] = keyFor(repo, DEFAULT_DOCK[i]);
        }
        for (Object[] c : DEFAULT_CELLS) {
            final int page = (Integer) c[0];
            final int index = (Integer) c[1] * COLUMNS + (Integer) c[2];
            mPages.get(page)[index] = keyFor(repo, (String) c[3]);
        }
        // Kiro Home 1.0 put everything on one page: keep the user's own apps (not the
        // suite, which now has its place) in the first free cells
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
            final JSONObject folders = new JSONObject();
            for (Map.Entry<String, Folder> e : mFolders.entrySet()) {
                folders.put(e.getKey(), new JSONObject().put("name", e.getValue().name)
                        .put("items", new JSONArray(e.getValue().items)));
            }
            mPrefs.edit().putString(KEY_LAYOUT, new JSONObject()
                    .put("v", VERSION)
                    .put("dock", new JSONArray(Arrays.asList(mDock)))
                    .put("pages", pages)
                    .put("folders", folders).toString()).apply();
        } catch (JSONException e) {
            // cannot happen with strings and arrays
        }
    }
}
