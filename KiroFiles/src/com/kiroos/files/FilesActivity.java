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

package com.kiroos.files;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Kiro Files: storage overview and categories (Downloads, Images, Videos, Audio, Documents,
 * APK), folder browsing with copy / move / rename / delete / share / install, optional root.
 */
public class FilesActivity extends Activity {

    private static final int CAT_IMAGES = 1;
    private static final int CAT_VIDEOS = 2;
    private static final int CAT_AUDIO = 3;
    private static final int CAT_DOCUMENTS = 4;
    private static final int CAT_APK = 5;
    /** 1.2: the results of a search by name (mSearchBase and below) */
    private static final int CAT_SEARCH = 6;
    private static final int SEARCH_MAX_RESULTS = 300;
    private static final int SEARCH_MAX_VISITS = 80000;

    private SharedPreferences mPrefs;
    private EditText mSearchField;
    private File mSearchBase;
    private File mBeforeSearchDir;
    private int mBeforeSearchCategory;
    private CharSequence mBeforeSearchTitle;
    private final android.os.Handler mHandler = new android.os.Handler();
    private final Runnable mSearchNow = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };
    private View mHome;
    private ListView mList;
    private TextView mEmpty;
    private TextView mTitle;
    private TextView mSubtitle;
    private View mBack;
    private View mSelectionBar;
    private View mPasteBar;
    private TextView mPasteText;
    private ImageButton mNewFolder;
    private final Adapter mAdapter = new Adapter();

    /** current folder, or null on the home / a category */
    private File mDir;
    private int mCategory;
    private final List<File> mHistory = new ArrayList<>();
    private final Set<FileItem> mSelected = new HashSet<>();
    private final List<File> mClipboard = new ArrayList<>();
    private boolean mClipboardMove;
    private boolean mClipboardRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);
        mPrefs = getSharedPreferences("files", MODE_PRIVATE);
        mHome = findViewById(R.id.home);
        mList = (ListView) findViewById(R.id.list);
        mEmpty = (TextView) findViewById(R.id.empty);
        mTitle = (TextView) findViewById(R.id.title);
        mSubtitle = (TextView) findViewById(R.id.subtitle);
        mBack = findViewById(R.id.btn_back);
        mSelectionBar = findViewById(R.id.selection_bar);
        mPasteBar = findViewById(R.id.paste_bar);
        mPasteText = (TextView) findViewById(R.id.paste_text);
        mNewFolder = (ImageButton) findViewById(R.id.btn_new_folder);
        mList.setAdapter(mAdapter);

        mBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        mSearchField = (EditText) findViewById(R.id.search_field);
        findViewById(R.id.btn_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCategory == CAT_SEARCH) {
                    endSearch();
                } else {
                    startSearch();
                }
            }
        });
        mSearchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                // a short pause after typing: the walk starts once
                mHandler.removeCallbacks(mSearchNow);
                if (mCategory == CAT_SEARCH) {
                    mHandler.postDelayed(mSearchNow, 300);
                }
            }
        });
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final FileItem f = mAdapter.getItem(position);
                if (!mSelected.isEmpty()) {
                    toggle(f);
                } else if (f.directory) {
                    openDir(f.file, true);
                } else {
                    openFile(f);
                }
            }
        });
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                toggle(mAdapter.getItem(position));
                return true;
            }
        });
        setupHome();
        setupBars();
        mNewFolder.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        mNewFolder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newFolder();
            }
        });
        KiroTouch.apply(mNewFolder);

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        }
        showHome();
        openShortcut(getIntent());
    }

    /** Beta 4: the Files widget's shortcuts (Downloads, Images, Documents) */
    static final String EXTRA_OPEN = "com.kiroos.files.extra.OPEN";

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openShortcut(intent);
    }

    private void openShortcut(Intent intent) {
        final String what = intent != null ? intent.getStringExtra(EXTRA_OPEN) : null;
        if (what == null) return;
        intent.removeExtra(EXTRA_OPEN);
        switch (what) {
            case "downloads":
                openDir(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), true);
                break;
            case "images":
                openCategory(CAT_IMAGES, getString(R.string.images));
                break;
            case "documents":
                openCategory(CAT_DOCUMENTS, getString(R.string.documents));
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        FilesWidget.refresh(this);   // the storage shown on the home after changes here
    }

    private boolean rootEnabled() {
        return mPrefs.getBoolean("root", false);
    }

    // --- home -------------------------------------------------------------------------------

    private void setupHome() {
        final int[][] cats = {
                {R.id.cat_downloads, R.drawable.ic_download, R.string.downloads, 0},
                {R.id.cat_images, R.drawable.ic_image, R.string.images, CAT_IMAGES},
                {R.id.cat_videos, R.drawable.ic_movie, R.string.videos, CAT_VIDEOS},
                {R.id.cat_audio, R.drawable.ic_music, R.string.audio, CAT_AUDIO},
                {R.id.cat_documents, R.drawable.ic_file, R.string.documents, CAT_DOCUMENTS},
                {R.id.cat_apk, R.drawable.ic_android, R.string.apk, CAT_APK},
        };
        for (final int[] c : cats) {
            final View tile = findViewById(c[0]);
            final ImageView icon = (ImageView) tile.findViewById(R.id.tile_icon);
            icon.setImageResource(c[1]);
            icon.setBackground(SquircleDrawable.button(getColor(R.color.kiro_surface_raised),
                    getColor(R.color.kiro_ripple)));
            ((TextView) tile.findViewById(R.id.tile_label)).setText(c[2]);
            tile.setContentDescription(getText(c[2]));
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (c[3] == 0) {
                        openDir(Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS), true);
                    } else {
                        openCategory(c[3], getString(c[2]));
                    }
                }
            });
            KiroTouch.apply(tile);
        }
        findViewById(R.id.loc_internal).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDir(Environment.getExternalStorageDirectory(), true);
            }
        });
        findViewById(R.id.loc_sd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final File sd = sdCard();
                if (sd != null) {
                    openDir(sd, true);
                }
            }
        });
        findViewById(R.id.loc_root).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDir(new File("/"), true);
            }
        });
    }

    /** removable storage root, from the app's own folder on it */
    private File sdCard() {
        final File[] dirs = getExternalFilesDirs(null);
        if (dirs.length < 2 || dirs[1] == null) {
            return null;
        }
        final String p = dirs[1].getAbsolutePath();
        final int i = p.indexOf("/Android/data/");
        return i > 0 ? new File(p.substring(0, i)) : null;
    }

    // --- search (1.2) ------------------------------------------------------------------------

    /** search the open folder (or the internal storage from the home) by name */
    private void startSearch() {
        mBeforeSearchDir = mDir;
        mBeforeSearchCategory = mCategory;
        mBeforeSearchTitle = mTitle.getText();
        mSearchBase = mDir != null && mDir.canRead() ? mDir : Environment.getExternalStorageDirectory();
        mDir = null;
        mCategory = CAT_SEARCH;
        clearSelection();
        mHome.setVisibility(View.GONE);
        mList.setVisibility(View.VISIBLE);
        mBack.setVisibility(View.VISIBLE);
        mNewFolder.setVisibility(View.GONE);
        mTitle.setText(R.string.search);
        mSubtitle.setText(getString(R.string.search_in, mSearchBase.getAbsolutePath()));
        mSubtitle.setVisibility(View.VISIBLE);
        mSearchField.setText("");
        mSearchField.setVisibility(View.VISIBLE);
        mSearchField.requestFocus();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(mSearchField, 0);
        updatePasteBar();
        refresh();
    }

    private void hideSearchField() {
        mHandler.removeCallbacks(mSearchNow);
        if (mSearchField.getVisibility() == View.VISIBLE) {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(mSearchField.getWindowToken(), 0);
            mSearchField.setVisibility(View.GONE);
        }
    }

    /** back to what was open before the search */
    private void endSearch() {
        hideSearchField();
        if (mBeforeSearchDir != null) {
            mCategory = 0;
            openDir(mBeforeSearchDir, false);
        } else if (mBeforeSearchCategory != 0 && mBeforeSearchCategory != CAT_SEARCH) {
            openCategory(mBeforeSearchCategory, String.valueOf(mBeforeSearchTitle));
        } else {
            showHome();
        }
    }

    /** files and folders whose name contains q, under base (depth first, capped) */
    private static List<FileItem> search(File base, String q, boolean hidden) {
        final List<FileItem> out = new ArrayList<>();
        final String needle = q.toLowerCase(java.util.Locale.getDefault());
        final java.util.ArrayDeque<File> stack = new java.util.ArrayDeque<>();
        stack.push(base);
        int visits = 0;
        while (!stack.isEmpty() && out.size() < SEARCH_MAX_RESULTS && visits < SEARCH_MAX_VISITS) {
            final File[] files = stack.pop().listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                visits++;
                final String name = f.getName();
                if (!hidden && name.startsWith(".")) {
                    continue;
                }
                if (name.toLowerCase(java.util.Locale.getDefault()).contains(needle)) {
                    out.add(new FileItem(f));
                    if (out.size() >= SEARCH_MAX_RESULTS) {
                        break;
                    }
                }
                if (f.isDirectory()) {
                    stack.push(f);
                }
            }
        }
        return out;
    }

    private void showHome() {
        hideSearchField();
        mDir = null;
        mCategory = 0;
        mHistory.clear();
        clearSelection();
        mHome.setVisibility(View.VISIBLE);
        mList.setVisibility(View.GONE);
        mEmpty.setVisibility(View.GONE);
        mBack.setVisibility(View.GONE);
        mNewFolder.setVisibility(View.GONE);
        mTitle.setText(R.string.app_name);
        mSubtitle.setVisibility(View.GONE);
        updatePasteBar();
        final StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
        final long total = stat.getTotalBytes();
        final long free = stat.getAvailableBytes();
        ((TextView) findViewById(R.id.storage_text)).setText(getString(R.string.storage_used,
                Formatter.formatShortFileSize(this, total - free),
                Formatter.formatShortFileSize(this, total)));
        final ProgressBar bar = (ProgressBar) findViewById(R.id.storage_bar);
        bar.setMax(1000);
        bar.setProgress(total > 0 ? (int) ((total - free) * 1000 / total) : 0);
        findViewById(R.id.loc_sd).setVisibility(sdCard() != null ? View.VISIBLE : View.GONE);
        findViewById(R.id.loc_root).setVisibility(rootEnabled() ? View.VISIBLE : View.GONE);
    }

    // --- folders & categories ---------------------------------------------------------------

    private void openDir(File dir, boolean push) {
        hideSearchField();
        if (push && mDir != null) {
            mHistory.add(mDir);
        }
        mDir = dir;
        mCategory = 0;
        clearSelection();
        mHome.setVisibility(View.GONE);
        mList.setVisibility(View.VISIBLE);
        mBack.setVisibility(View.VISIBLE);
        mNewFolder.setVisibility(View.VISIBLE);
        mTitle.setText(dir.getParent() == null ? "/" : dir.getName());
        mSubtitle.setText(dir.getAbsolutePath());
        mSubtitle.setVisibility(View.VISIBLE);
        updatePasteBar();
        refresh();
    }

    private void openCategory(int category, String title) {
        hideSearchField();
        mDir = null;
        mCategory = category;
        clearSelection();
        mHome.setVisibility(View.GONE);
        mList.setVisibility(View.VISIBLE);
        mBack.setVisibility(View.VISIBLE);
        mNewFolder.setVisibility(View.GONE);
        mTitle.setText(title);
        mSubtitle.setVisibility(View.GONE);
        updatePasteBar();
        refresh();
    }

    private void refresh() {
        if (mDir == null && mCategory == 0) {
            if (mHome.getVisibility() == View.VISIBLE) {
                showHome();
            }
            return;
        }
        final File dir = mDir;
        final int category = mCategory;
        final boolean hidden = mPrefs.getBoolean("hidden", false);
        final int sort = mPrefs.getInt("sort", FileItem.SORT_NAME);
        final boolean root = rootEnabled();
        final String query = mSearchField.getText().toString().trim();
        final File searchBase = mSearchBase;
        if (category == CAT_SEARCH && query.isEmpty()) {
            mAdapter.set(new ArrayList<FileItem>());
            mEmpty.setText(R.string.search_start);
            mEmpty.setVisibility(View.VISIBLE);
            return;
        }
        if (category == CAT_SEARCH) {
            mEmpty.setText(R.string.searching);
            mEmpty.setVisibility(mAdapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        }
        new AsyncTask<Void, Void, List<FileItem>>() {
            @Override
            protected List<FileItem> doInBackground(Void... params) {
                final List<FileItem> out;
                if (category == CAT_SEARCH) {
                    out = search(searchBase, query, hidden);
                } else {
                    out = dir != null ? listDir(dir, hidden, root) : listCategory(category);
                }
                Collections.sort(out, FileItem.comparator(category == CAT_SEARCH ? FileItem.SORT_NAME
                        : category != 0 && sort == FileItem.SORT_NAME ? FileItem.SORT_DATE : sort));
                return out;
            }

            @Override
            protected void onPostExecute(List<FileItem> items) {
                if (dir != mDir || category != mCategory) {
                    return;
                }
                if (category == CAT_SEARCH
                        && !query.equals(mSearchField.getText().toString().trim())) {
                    return; // the user typed on: a newer search is coming
                }
                mAdapter.set(items);
                if (category == CAT_SEARCH) {
                    mEmpty.setText(R.string.search_none);
                } else {
                    mEmpty.setText(dir != null && !dir.canRead() && !root ? R.string.no_access
                            : R.string.empty_folder);
                }
                mEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static List<FileItem> listDir(File dir, boolean hidden, boolean root) {
        final List<FileItem> out = new ArrayList<>();
        final File[] files = dir.listFiles();
        if (files == null) {
            if (root) {
                final List<FileItem> r = Root.list(dir.getAbsolutePath());
                if (r != null) {
                    for (FileItem f : r) {
                        if (hidden || !f.name().startsWith(".")) {
                            out.add(f);
                        }
                    }
                }
            }
            return out;
        }
        for (File f : files) {
            if (hidden || !f.getName().startsWith(".")) {
                out.add(new FileItem(f));
            }
        }
        return out;
    }

    private List<FileItem> listCategory(int category) {
        final List<FileItem> out = new ArrayList<>();
        String where;
        switch (category) {
            case CAT_IMAGES:
                where = MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
                break;
            case CAT_VIDEOS:
                where = MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                break;
            case CAT_AUDIO:
                where = MediaStore.Files.FileColumns.MEDIA_TYPE + "="
                        + MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO;
                break;
            case CAT_APK:
                where = MediaStore.Files.FileColumns.DATA + " LIKE '%.apk'";
                break;
            default:
                where = MediaStore.Files.FileColumns.MIME_TYPE + " IN ('application/pdf',"
                        + "'text/plain','application/msword','application/rtf',"
                        + "'application/vnd.openxmlformats-officedocument.wordprocessingml.document',"
                        + "'application/vnd.ms-excel',"
                        + "'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',"
                        + "'application/vnd.ms-powerpoint',"
                        + "'application/vnd.openxmlformats-officedocument.presentationml.presentation',"
                        + "'application/vnd.oasis.opendocument.text','text/csv')";
                break;
        }
        try (Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[] {MediaStore.Files.FileColumns.DATA}, where, null,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC")) {
            while (c != null && c.moveToNext()) {
                final String path = c.getString(0);
                if (path != null) {
                    final File f = new File(path);
                    if (f.isFile()) {
                        out.add(new FileItem(f));
                    }
                }
            }
        } catch (RuntimeException e) {
            // no permission
        }
        return out;
    }

    @Override
    public void onBackPressed() {
        if (!mSelected.isEmpty()) {
            clearSelection();
            return;
        }
        if (mCategory == CAT_SEARCH) {
            endSearch();
            return;
        }
        if (mDir != null && !mHistory.isEmpty()) {
            openDir(mHistory.remove(mHistory.size() - 1), false);
            return;
        }
        if (mDir != null && mDir.getParentFile() != null
                && !mDir.equals(Environment.getExternalStorageDirectory())
                && mDir.getAbsolutePath().startsWith(
                Environment.getExternalStorageDirectory().getAbsolutePath())) {
            openDir(mDir.getParentFile(), false);
            return;
        }
        if (mDir != null || mCategory != 0) {
            showHome();
            return;
        }
        super.onBackPressed();
    }

    // --- open -------------------------------------------------------------------------------

    private void openFile(FileItem f) {
        File file = f.file;
        if (f.viaRoot || !file.canRead()) {
            // copy it out as root first so another app can read it
            final File out = new File(getExternalCacheDir(), file.getName());
            if (!rootEnabled() || !Root.export(file.getAbsolutePath(), out, Process.myUid())) {
                Toast.makeText(this, R.string.no_access, Toast.LENGTH_SHORT).show();
                return;
            }
            file = out;
        }
        final Uri uri = FilesProvider.uriFor(file);
        final String mime = FilesProvider.mimeOf(file.getName());
        final Intent i;
        if ("apk".equals(f.extension())) {
            i = new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri);
        } else {
            i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            // no app for this type: offer them all
            try {
                startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.open_with)));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- selection & actions ----------------------------------------------------------------

    private void toggle(FileItem f) {
        if (!mSelected.remove(f)) {
            mSelected.add(f);
        }
        mSelectionBar.setVisibility(mSelected.isEmpty() ? View.GONE : View.VISIBLE);
        mNewFolder.setVisibility(mSelected.isEmpty() && mDir != null ? View.VISIBLE : View.GONE);
        if (!mSelected.isEmpty()) {
            mTitle.setText(getString(R.string.selected, mSelected.size()));
        } else if (mDir != null) {
            mTitle.setText(mDir.getParent() == null ? "/" : mDir.getName());
        }
        mAdapter.notifyDataSetChanged();
    }

    private void clearSelection() {
        mSelected.clear();
        mSelectionBar.setVisibility(View.GONE);
        if (mDir != null) {
            mTitle.setText(mDir.getParent() == null ? "/" : mDir.getName());
            mNewFolder.setVisibility(View.VISIBLE);
        }
        mAdapter.notifyDataSetChanged();
    }

    private List<File> selectedFiles() {
        final List<File> out = new ArrayList<>();
        for (FileItem f : mSelected) {
            out.add(f.file);
        }
        return out;
    }

    private boolean selectionViaRoot() {
        for (FileItem f : mSelected) {
            if (f.viaRoot) {
                return true;
            }
        }
        return false;
    }

    private void setupBars() {
        findViewById(R.id.act_copy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toClipboard(false);
            }
        });
        findViewById(R.id.act_cut).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toClipboard(true);
            }
        });
        findViewById(R.id.act_share).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                share();
            }
        });
        findViewById(R.id.act_delete).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
        findViewById(R.id.act_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final PopupMenu menu = new PopupMenu(FilesActivity.this, v);
                if (mSelected.size() == 1) {
                    menu.getMenu().add(0, 1, 0, R.string.rename);
                }
                menu.getMenu().add(0, 2, 0, R.string.details);
                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        if (item.getItemId() == 1) {
                            rename(mSelected.iterator().next());
                        } else {
                            details();
                        }
                        return true;
                    }
                });
                menu.show();
            }
        });
        findViewById(R.id.paste_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mClipboard.clear();
                updatePasteBar();
            }
        });
        findViewById(R.id.paste_go).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                paste();
            }
        });
    }

    private void toClipboard(boolean move) {
        mClipboard.clear();
        mClipboard.addAll(selectedFiles());
        mClipboardMove = move;
        mClipboardRoot = selectionViaRoot();
        clearSelection();
        updatePasteBar();
        Toast.makeText(this, getString(move ? R.string.cut_n : R.string.copied_n,
                mClipboard.size()), Toast.LENGTH_SHORT).show();
    }

    private void updatePasteBar() {
        final boolean show = !mClipboard.isEmpty() && mDir != null;
        mPasteBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            mPasteText.setText(getString(mClipboardMove ? R.string.move_here : R.string.copy_here,
                    mClipboard.size()));
        }
    }

    private void paste() {
        if (mDir == null || mClipboard.isEmpty()) {
            return;
        }
        final boolean root = rootEnabled() && (mClipboardRoot || !mDir.canWrite());
        FileOps.run(this, mClipboardMove ? FileOps.MOVE : FileOps.COPY,
                new ArrayList<>(mClipboard), mDir, root, new FileOps.Done() {
                    @Override
                    public void onDone() {
                        if (mClipboardMove) {
                            mClipboard.clear();
                        }
                        updatePasteBar();
                        refresh();
                    }
                });
        if (mClipboardMove) {
            mPasteBar.setVisibility(View.GONE);
        }
    }

    private void share() {
        final ArrayList<Uri> uris = new ArrayList<>();
        for (FileItem f : mSelected) {
            if (!f.directory && !f.viaRoot) {
                uris.add(FilesProvider.uriFor(f.file));
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_SHORT).show();
            return;
        }
        final Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0))
                    .setType(FilesProvider.mimeOf(uris.get(0).getPath()));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).setType("*/*");
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, getString(R.string.share)));
        clearSelection();
    }

    private void confirmDelete() {
        final List<File> files = selectedFiles();
        final boolean root = rootEnabled() && selectionViaRoot();
        new AlertDialog.Builder(this)
                .setMessage(files.size() == 1
                        ? getString(R.string.delete_one, files.get(0).getName())
                        : getString(R.string.delete_many, files.size()))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        clearSelection();
                        FileOps.run(FilesActivity.this, FileOps.DELETE, files, null,
                                root || rootEnabled(), new FileOps.Done() {
                                    @Override
                                    public void onDone() {
                                        refresh();
                                    }
                                });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void rename(final FileItem f) {
        final EditText field = new EditText(this);
        field.setText(f.name());
        field.setSingleLine(true);
        final int dot = f.directory ? -1 : f.name().lastIndexOf('.');
        field.setSelection(0, dot > 0 ? dot : f.name().length());
        final FrameLayout box = new FrameLayout(this);
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String name = field.getText().toString().trim();
                        if (name.isEmpty() || name.contains("/")) {
                            return;
                        }
                        final File to = new File(f.file.getParentFile(), name);
                        boolean ok = !to.exists() && f.file.renameTo(to);
                        if (!ok && rootEnabled() && !to.exists()) {
                            ok = Root.move(f.file.getAbsolutePath(), to.getAbsolutePath());
                        }
                        if (!ok) {
                            Toast.makeText(FilesActivity.this, R.string.rename_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                        android.media.MediaScannerConnection.scanFile(FilesActivity.this,
                                new String[] {f.file.getAbsolutePath(), to.getAbsolutePath()},
                                null, null);
                        clearSelection();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void details() {
        final List<File> files = selectedFiles();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                long size = 0;
                for (File f : files) {
                    size += FileOps.sizeOf(f);
                }
                final StringBuilder b = new StringBuilder();
                if (files.size() == 1) {
                    final File f = files.get(0);
                    b.append(getString(R.string.info_path, f.getAbsolutePath())).append('\n');
                    b.append(getString(R.string.info_modified, DateUtils.formatDateTime(
                            FilesActivity.this, f.lastModified(), DateUtils.FORMAT_SHOW_DATE
                                    | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR)))
                            .append('\n');
                    if (!f.isDirectory()) {
                        b.append(getString(R.string.info_type, FilesProvider.mimeOf(f.getName())))
                                .append('\n');
                    }
                } else {
                    b.append(getString(R.string.info_items, files.size())).append('\n');
                }
                b.append(getString(R.string.info_size, Formatter.formatFileSize(
                        FilesActivity.this, size)));
                return b.toString();
            }

            @Override
            protected void onPostExecute(String text) {
                new AlertDialog.Builder(FilesActivity.this)
                        .setTitle(R.string.details)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void newFolder() {
        if (mDir == null) {
            return;
        }
        final EditText field = new EditText(this);
        field.setHint(R.string.folder_name);
        field.setSingleLine(true);
        final FrameLayout box = new FrameLayout(this);
        final int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(R.string.new_folder)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String name = field.getText().toString().trim();
                        if (name.isEmpty() || name.contains("/")) {
                            return;
                        }
                        final File dir = new File(mDir, name);
                        boolean ok = dir.mkdir();
                        if (!ok && rootEnabled()) {
                            ok = Root.mkdir(dir.getAbsolutePath());
                        }
                        if (!ok) {
                            Toast.makeText(FilesActivity.this, R.string.mkdir_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- menu -------------------------------------------------------------------------------

    private void showMenu(View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.sort_name);
        menu.getMenu().add(0, 2, 0, R.string.sort_date);
        menu.getMenu().add(0, 3, 0, R.string.sort_size);
        menu.getMenu().add(0, 4, 0, R.string.show_hidden).setCheckable(true)
                .setChecked(mPrefs.getBoolean("hidden", false));
        menu.getMenu().add(0, 5, 0, R.string.root_access).setCheckable(true)
                .setChecked(rootEnabled());
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                    case 2:
                    case 3:
                        mPrefs.edit().putInt("sort", item.getItemId() - 1).apply();
                        refresh();
                        return true;
                    case 4:
                        mPrefs.edit().putBoolean("hidden", !mPrefs.getBoolean("hidden", false))
                                .apply();
                        refresh();
                        return true;
                    case 5:
                        toggleRoot();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    private void toggleRoot() {
        if (rootEnabled()) {
            mPrefs.edit().putBoolean("root", false).apply();
            if (mDir == null) {
                showHome();
            }
            return;
        }
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {
                return Root.available();
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (ok) {
                    mPrefs.edit().putBoolean("root", true).apply();
                    Toast.makeText(FilesActivity.this, R.string.root_on, Toast.LENGTH_LONG).show();
                    if (mDir == null) {
                        showHome();
                    } else {
                        refresh();
                    }
                } else {
                    Toast.makeText(FilesActivity.this, R.string.root_missing,
                            Toast.LENGTH_LONG).show();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // --- list -------------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        private List<FileItem> mItems = Collections.emptyList();

        void set(List<FileItem> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public FileItem getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    FilesActivity.this).inflate(R.layout.item_file, parent, false);
            final FileItem f = mItems.get(position);
            final ImageView icon = (ImageView) v.findViewById(R.id.icon);
            icon.setImageResource(f.icon());
            icon.setColorFilter(getColor(f.directory ? R.color.kiro_accent : R.color.kiro_text_secondary));
            ((TextView) v.findViewById(R.id.name)).setText(f.name());
            String when = f.modified > 0 ? DateUtils.getRelativeTimeSpanString(f.modified)
                    .toString() : "";
            if (mCategory == CAT_SEARCH && mSearchBase != null && f.file.getParentFile() != null) {
                // a search result: where it is, under the folder searched
                final String base = mSearchBase.getAbsolutePath();
                String where = f.file.getParentFile().getAbsolutePath();
                where = where.equals(base) ? "" : where.startsWith(base + "/")
                        ? where.substring(base.length() + 1) : where;
                if (!where.isEmpty()) {
                    when = where + (when.isEmpty() ? "" : " · " + when);
                }
            }
            ((TextView) v.findViewById(R.id.info)).setText(f.directory ? when
                    : Formatter.formatShortFileSize(FilesActivity.this, f.size)
                    + (when.isEmpty() ? "" : " · " + when));
            v.setActivated(mSelected.contains(f));
            v.findViewById(R.id.check).setVisibility(mSelected.contains(f) ? View.VISIBLE
                    : View.GONE);
            return v;
        }
    }
}
