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

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.StatusBarManager;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

import java.util.List;

/**
 * Kiro Home v1.0: clock, widgets, pages of apps and a dock over the wallpaper.
 * Swipe sideways between the pages, up anywhere for the vertical app drawer, down for
 * notifications (two fingers: quick settings). Long-press an app and drag it to any cell,
 * page or dock slot (also straight from the drawer); let it go where it was for its menu.
 * Apps open with a zoom from their icon.
 */
public class HomeActivity extends Activity implements HomeLayout.Callbacks,
        AppRepository.Listener {

    /** always dark, whatever the system theme */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.kiroos.ui.KiroTheme.dark(base));
    }

    private static final int REQUEST_PICK_WIDGET = 1;
    private static final int REQUEST_CONFIGURE_WIDGET = 2;
    /** how long an app dragged to a side edge waits before the page turns */
    private static final long EDGE_PAGE_DELAY = 550;
    private static final String KEY_PAGE = "page";
    /** the clock at the top of the first page (removable) */
    private static final String KEY_CLOCK = "clock";
    /** page to show once the layout is bound (restored after the launcher was killed) */
    private int mStartPage;

    private final Handler mHandler = new Handler();
    private IconFactory mIcons;
    private AppRepository mRepo;
    private HomeModel mModel;
    private WidgetStack mWidgets;

    private HomeLayout mRoot;
    private View mHomeContent;
    private Workspace mWorkspace;
    private PageIndicator mIndicator;
    private WallpaperManager mWallpaperManager;
    /** the first page's header: the clock and the widgets */
    private View mHeader;
    private View mClock;
    private LinearLayout mDock;
    private View mDropHint;
    private View mDrawer;
    private GridView mDrawerGrid;
    private EditText mSearch;
    private DrawerAdapter mDrawerAdapter;
    private float mDrawerProgress;
    private int mTouchSlop;

    /** the app being dragged, where it came from, and whether it moved */
    private static final class Drag {
        /** the app dragged, null for a folder */
        AppInfo app;
        /** app key or folder key */
        String key;
        HomeModel.Loc from;
        /** the folder the app is dragged out of, or null */
        String fromFolder;
        View source;
        float startX = Float.NaN;
        float startY;
        boolean moved;
        int edge; // -1 left, +1 right, 0 none
    }

    private Drag mDrag;
    /** the open folder and its key, or null */
    private FolderView mFolderView;
    private String mOpenFolder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.activity_home);
        mTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

        mIcons = new IconFactory(this);
        mRepo = new AppRepository(this, mIcons);
        mModel = new HomeModel(getSharedPreferences("home", MODE_PRIVATE));

        mRoot = (HomeLayout) findViewById(R.id.root);
        mRoot.setCallbacks(this);
        mHomeContent = findViewById(R.id.home_content);
        mWorkspace = (Workspace) findViewById(R.id.workspace);
        mIndicator = (PageIndicator) findViewById(R.id.page_indicator);
        mDock = (LinearLayout) findViewById(R.id.dock);
        mDropHint = findViewById(R.id.drop_hint);
        mDrawer = findViewById(R.id.drawer);
        mDrawerGrid = (GridView) findViewById(R.id.drawer_grid);
        mSearch = (EditText) findViewById(R.id.drawer_search);

        mWallpaperManager = WallpaperManager.getInstance(this);
        mHeader = findViewById(R.id.header);
        mClock = findViewById(R.id.clock_block);
        mWorkspace.setListener(new Workspace.Listener() {
            @Override
            public void onPageScrolled(float position) {
                mIndicator.setPosition(position);
                // the whole screen moves with the pages: the clock and the widgets belong to
                // the first page, and the wallpaper follows at a slower pace
                final float first = Math.max(0f, Math.min(1f, position)) * mRoot.getWidth();
                mHeader.setTranslationX(-first);
                // the wallpaper spans the real pages: the empty page a drag adds at the end
                // must not move it
                final int pages = mDrag != null ? mModel.pageCount() : mWorkspace.pageCount();
                if (pages > 1 && mRoot.getWindowToken() != null) {
                    final float x = Math.max(0f, Math.min(1f, position / (pages - 1)));
                    mWallpaperManager.setWallpaperOffsetSteps(1f / (pages - 1), 1f);
                    mWallpaperManager.setWallpaperOffsets(mRoot.getWindowToken(), x, 0.5f);
                }
            }

            @Override
            public void onRowsChanged(int firstRows, int rows) {
                mModel.setRows(firstRows, rows);
                if (mDrag == null && mModel.fitRows()) {
                    bindHome();
                }
            }
        });
        // the first page's grid starts below the clock and the widgets
        mHeader.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot,
                    int or, int ob) {
                mWorkspace.setFirstPageTop(b - t);
            }
        });
        showClock(getSharedPreferences("home", MODE_PRIVATE).getBoolean(KEY_CLOCK, true));
        // the page survives the launcher being killed while an app is open
        mStartPage = getSharedPreferences("home", MODE_PRIVATE).getInt(KEY_PAGE, 0);

        // both layers draw behind the transparent bars but keep their content clear of them
        final View drawerInner = findViewById(R.id.drawer_inner);
        mRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                final int top = insets.getSystemWindowInsetTop();
                final int bottom = insets.getSystemWindowInsetBottom();
                mHomeContent.setPadding(mHomeContent.getPaddingLeft(), top,
                        mHomeContent.getPaddingRight(), bottom);
                drawerInner.setPadding(0, top, 0, bottom);
                // the pages slide exactly edge to edge (a rounded dp margin left 1 px strips)
                final ViewGroup.MarginLayoutParams lp =
                        (ViewGroup.MarginLayoutParams) mWorkspace.getLayoutParams();
                lp.setMarginStart(-mHomeContent.getPaddingStart());
                lp.setMarginEnd(-mHomeContent.getPaddingEnd());
                mWorkspace.setLayoutParams(lp);
                return insets.consumeSystemWindowInsets();
            }
        });
        mRoot.setOnDragListener(mDragListener);

        findViewById(R.id.clock_block).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException | SecurityException e) {
                    // no clock app
                }
            }
        });
        mClock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                final PopupMenu menu = new PopupMenu(HomeActivity.this, v, Gravity.CENTER);
                menu.getMenu().add(0, 1, 0, R.string.remove_clock);
                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        showClock(false);
                        return true;
                    }
                });
                menu.show();
                return true;
            }
        });

        mWidgets = new WidgetStack(this, (LinearLayout) findViewById(R.id.widget_stack),
                getSharedPreferences("home", MODE_PRIVATE));

        mDrawerAdapter = new DrawerAdapter(this, mIcons);
        mDrawerGrid.setAdapter(mDrawerAdapter);
        mDrawerGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                launch(mDrawerAdapter.getItem(position), view.findViewById(R.id.app_icon));
            }
        });
        mDrawerGrid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                // drag it onto the home (the drawer steps aside); dropped back in place: menu
                final AppInfo app = mDrawerAdapter.getItem(position);
                startDrag(app, app.key(), null, null, view);
                return true;
            }
        });
        mDrawerGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
            }
        });
        new DrawerDismisser(mDrawerGrid, this);
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mDrawerAdapter.filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        mSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (mDrawerAdapter.getCount() > 0) {
                    launch(mDrawerAdapter.getItem(0), null);
                    return true;
                }
                return false;
            }
        });

        setDrawerProgress(0f);
        mRepo.addListener(this);
        mRepo.reload();
        KiroTouch.enter(findViewById(R.id.clock_block), mWorkspace, mDock);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mWidgets.startListening();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mWidgets.stopListening();
        getSharedPreferences("home", MODE_PRIVATE).edit()
                .putInt(KEY_PAGE, mWorkspace.currentPage()).apply();
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        mRepo.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the home: close the drawer, else back to the first
        // page. Coming back from an app (Home pressed there) keeps the page the user was on.
        final boolean alreadyOnHome = hasWindowFocus() && (intent.getFlags()
                & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT;
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && alreadyOnHome) {
            if (isDrawerOpen()) {
                animateDrawer(false);
            } else {
                mWorkspace.snapTo(0, true);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mFolderView != null) {
            closeFolder(true);
        } else if (isDrawerOpen()) {
            animateDrawer(false);
        } else if (mWorkspace.currentPage() > 0) {
            mWorkspace.snapTo(0, true);
        }
    }

    // --- apps ---------------------------------------------------------------------------

    @Override
    public void onAppsChanged(List<AppInfo> apps) {
        mModel.load(mRepo);
        mModel.prune(mRepo);
        mModel.fitRows();
        mDrawerAdapter.setApps(apps);
        mDrawerAdapter.filter(mSearch.getText().toString());
        if (mDrag == null) {
            bindHome();
        }
    }

    private void bindHome() {
        final LayoutInflater inflater = LayoutInflater.from(this);
        mWorkspace.removeAllViews();
        for (int p = 0; p < mModel.pageCount(); p++) {
            for (int i = 0; i < HomeModel.PAGE_SIZE; i++) {
                final HomeModel.Loc loc = new HomeModel.Loc(p, i);
                final View cell = bindCell(inflater, mWorkspace, loc, true);
                if (cell != null) {
                    mWorkspace.addView(cell, new Workspace.LayoutParams(p, i));
                }
            }
        }
        mWorkspace.setPageCount(mModel.pageCount());
        mIndicator.setCount(mModel.pageCount());
        if (mStartPage > 0) {
            mWorkspace.snapTo(mStartPage, false);
            mStartPage = 0;
        }
        mDock.removeAllViews();
        for (int i = 0; i < HomeModel.DOCK_SIZE; i++) {
            final HomeModel.Loc loc = new HomeModel.Loc(HomeModel.DOCK, i);
            final View bound = bindCell(inflater, mDock, loc, false);
            // empty slots keep their place, so an app can be dropped there
            final View cell = bound != null ? bound : new View(this);
            mDock.addView(cell, new LinearLayout.LayoutParams(0,
                    bound != null ? ViewGroup.LayoutParams.WRAP_CONTENT : 1, 1f));
        }
    }

    /** the view of a cell: an app, a folder, or null when the cell is empty */
    private View bindCell(LayoutInflater inflater, ViewGroup parent, HomeModel.Loc loc,
            boolean withLabel) {
        final String key = mModel.at(loc);
        if (HomeModel.isFolder(key)) {
            return bindFolder(inflater, parent, key, withLabel, loc);
        }
        final AppInfo app = mRepo.find(key);
        return app != null ? bindIcon(inflater, parent, app, withLabel, loc) : null;
    }

    /** the apps of a folder that are still installed */
    private List<AppInfo> folderApps(HomeModel.Folder f) {
        final List<AppInfo> apps = new java.util.ArrayList<>();
        for (String k : f.items) {
            final AppInfo app = mRepo.find(k);
            if (app != null) {
                apps.add(app);
            }
        }
        return apps;
    }

    private View bindFolder(LayoutInflater inflater, ViewGroup parent, final String key,
            boolean withLabel, final HomeModel.Loc loc) {
        final HomeModel.Folder f = mModel.folder(key);
        if (f == null) {
            return null;
        }
        final View v = inflater.inflate(R.layout.item_app, parent, false);
        final ImageView icon = (ImageView) v.findViewById(R.id.app_icon);
        icon.setImageDrawable(mIcons.folderIcon(folderApps(f)));
        final TextView label = (TextView) v.findViewById(R.id.app_label);
        final String name = TextUtils.isEmpty(f.name) ? getString(R.string.folder_default)
                : f.name;
        label.setText(name);
        label.setVisibility(withLabel ? View.VISIBLE : View.GONE);
        v.setContentDescription(name);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openFolder(key);
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                startDrag(null, key, loc, null, view);
                return true;
            }
        });
        KiroTouch.apply(v);
        return v;
    }

    // --- folders ----------------------------------------------------------------------------

    @Override
    public boolean isFolderOpen() {
        return mFolderView != null;
    }

    private void openFolder(final String key) {
        final HomeModel.Folder f = mModel.folder(key);
        if (f == null || mFolderView != null) {
            return;
        }
        mOpenFolder = key;
        mFolderView = new FolderView(this, mIcons, f.name, folderApps(f),
                new FolderView.Host() {
            @Override
            public void onFolderAppClick(AppInfo app, View icon) {
                launch(app, icon);
                closeFolder(false);
            }

            @Override
            public void onFolderAppLongClick(AppInfo app, View cell) {
                // the app leaves the folder only where it is dropped
                startDrag(app, app.key(), null, key, cell);
                closeFolder(false);
            }

            @Override
            public void onFolderRenamed(String name) {
                mModel.rename(key, name);
            }

            @Override
            public void onFolderClosed() {
                mFolderView = null;
                mOpenFolder = null;
                if (mDrag == null) {
                    bindHome();
                }
            }
        });
        mRoot.addView(mFolderView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeFolder(boolean animate) {
        if (mFolderView != null) {
            mFolderView.close(animate);
        }
    }

    private View bindIcon(LayoutInflater inflater, ViewGroup parent, final AppInfo app,
            boolean withLabel, final HomeModel.Loc loc) {
        final View v = inflater.inflate(R.layout.item_app, parent, false);
        final ImageView icon = (ImageView) v.findViewById(R.id.app_icon);
        icon.setImageDrawable(mIcons.icon(app));
        final TextView label = (TextView) v.findViewById(R.id.app_label);
        label.setText(app.label);
        label.setVisibility(withLabel ? View.VISIBLE : View.GONE);
        v.setContentDescription(app.label);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                launch(app, icon);
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                startDrag(app, app.key(), loc, null, view);
                return true;
            }
        });
        KiroTouch.apply(v);
        return v;
    }

    /** Opens the app with the zoom-from-icon transition (AppTransition scale-up). */
    void launch(AppInfo app, View from) {
        Bundle opts = null;
        Rect bounds = null;
        if (from != null) {
            final int[] loc = new int[2];
            from.getLocationOnScreen(loc);
            bounds = new Rect(loc[0], loc[1], loc[0] + from.getWidth(), loc[1] + from.getHeight());
            opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0,
                    from.getWidth(), from.getHeight()).toBundle();
        }
        try {
            mRepo.launcherApps().startMainActivity(app.component, app.user, bounds, opts);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.app_not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void showAppMenu(final AppInfo app, View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor, Gravity.CENTER);
        final Menu m = menu.getMenu();
        if (mModel.onHome(app.key())) {
            m.add(0, 1, 0, R.string.remove_from_home);
        } else {
            m.add(0, 2, 0, R.string.add_to_home);
        }
        m.add(0, 4, 0, R.string.app_info);
        m.add(0, 5, 0, R.string.uninstall);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        mModel.remove(app.key());
                        bindHome();
                        return true;
                    case 2:
                        if (!mModel.add(app.key())) {
                            Toast.makeText(HomeActivity.this, R.string.home_full,
                                    Toast.LENGTH_SHORT).show();
                        }
                        bindHome();
                        return true;
                    case 4:
                        mRepo.launcherApps().startAppDetailsActivity(app.component, app.user,
                                null, null);
                        return true;
                    case 5:
                        startActivity(new Intent(Intent.ACTION_DELETE,
                                Uri.fromParts("package", app.component.getPackageName(), null)));
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    // --- drag and drop ----------------------------------------------------------------------

    /**
     * Long-press on an app or a folder (key). On the home from is its cell; from the drawer
     * or a folder (fromFolder) from is null.
     */
    private void startDrag(AppInfo app, String key, HomeModel.Loc from, String fromFolder,
            View cell) {
        if (mDrag != null) {
            return;
        }
        cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        cell.animate().cancel();
        cell.setScaleX(1f);
        cell.setScaleY(1f);
        final View icon = cell.findViewById(R.id.app_icon);
        final Drag d = new Drag();
        d.app = app;
        d.key = key;
        d.from = from;
        d.fromFolder = fromFolder;
        d.source = cell;
        mDrag = d;
        // an extra empty page to drop on while dragging
        mWorkspace.setPageCount(mModel.pageCount() + 1);
        mIndicator.setCount(mModel.pageCount() + 1);
        if (!cell.startDrag(ClipData.newPlainText("app", key),
                new View.DragShadowBuilder(icon != null ? icon : cell), d, 0)) {
            mDrag = null;
            bindHome();
            if (from == null && fromFolder == null && app != null) {
                showAppMenu(app, cell);
            }
            return;
        }
        if (from != null) {
            cell.setAlpha(0.25f);
        }
    }

    private final View.OnDragListener mDragListener = new View.OnDragListener() {
        @Override
        public boolean onDrag(View v, DragEvent e) {
            final Drag d = mDrag;
            if (d == null) {
                return false;
            }
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    if (Float.isNaN(d.startX)) {
                        d.startX = e.getX();
                        d.startY = e.getY();
                    } else if (!d.moved && Math.hypot(e.getX() - d.startX, e.getY() - d.startY)
                            > mTouchSlop * 2) {
                        d.moved = true;
                        if (d.from == null && d.fromFolder == null) {
                            // from the drawer: it steps aside so the home is the drop area
                            animateDrawer(false);
                        }
                    }
                    if (d.moved) {
                        final HomeModel.Loc at = locAt(e.getX(), e.getY());
                        showHint(at, mergeAt(d, at, e.getX(), e.getY()));
                        trackEdge(d, e.getX(), e.getY());
                    }
                    return true;
                case DragEvent.ACTION_DROP:
                    final HomeModel.Loc to = d.moved ? locAt(e.getX(), e.getY()) : null;
                    if (to != null) {
                        // onto another app: a folder; anywhere else: that cell
                        if (!(mergeAt(d, to, e.getX(), e.getY())
                                && mModel.merge(d.key, to, getString(R.string.folder_default)))) {
                            if (d.fromFolder != null) {
                                mModel.removeFromFolder(d.fromFolder, d.key);
                            }
                            mModel.place(d.key, to);
                        }
                    }
                    return to != null;
                case DragEvent.ACTION_DRAG_ENDED:
                    endDrag(d);
                    return true;
                default:
                    return true;
            }
        }
    };

    private void endDrag(Drag d) {
        mHandler.removeCallbacks(mEdgePage);
        mDrag = null;
        mDropHint.setVisibility(View.GONE);
        d.source.setAlpha(1f);
        final int page = Math.min(mWorkspace.currentPage(), Math.max(0, mModel.pageCount() - 1));
        bindHome();
        mWorkspace.snapTo(page, true);
        if (!d.moved) {
            if (d.app == null) {
                openFolder(d.key); // a folder held without moving opens
            } else if (d.fromFolder == null) {
                // a long-press without a move: the app menu, anchored to the app
                final View anchor = d.from != null ? cellView(d.from) : d.source;
                showAppMenu(d.app, anchor != null && anchor.isAttachedToWindow()
                        ? anchor : mRoot);
            }
        }
    }

    /** the view that shows the app at loc after a rebind */
    private View cellView(HomeModel.Loc loc) {
        if (loc.container == HomeModel.DOCK) {
            return loc.index < mDock.getChildCount() ? mDock.getChildAt(loc.index) : null;
        }
        for (int i = 0; i < mWorkspace.getChildCount(); i++) {
            final View c = mWorkspace.getChildAt(i);
            final Workspace.LayoutParams lp = (Workspace.LayoutParams) c.getLayoutParams();
            if (lp.page == loc.container && lp.index == loc.index) {
                return c;
            }
        }
        return null;
    }

    /** the cell (dock slot or cell of the current page) under a point of the root */
    private HomeModel.Loc locAt(float x, float y) {
        final Rect r = boundsInRoot(mDock);
        if (r.contains((int) x, (int) y) || y > r.bottom) {
            final int slot = (int) ((x - r.left) / (r.width() / (float) HomeModel.DOCK_SIZE));
            return new HomeModel.Loc(HomeModel.DOCK,
                    Math.max(0, Math.min(HomeModel.DOCK_SIZE - 1, slot)));
        }
        final Rect w = boundsInRoot(mWorkspace);
        // anywhere above the page's top row (the clock area too) is its top row
        final int index = mWorkspace.cellAt(x - w.left, Math.min(w.height() - 1, y - w.top));
        return index < 0 ? null : new HomeModel.Loc(mWorkspace.currentPage(), index);
    }

    private Rect boundsInRoot(View v) {
        final int[] root = new int[2];
        final int[] at = new int[2];
        mRoot.getLocationInWindow(root);
        v.getLocationInWindow(at);
        final int l = at[0] - root[0];
        final int t = at[1] - root[1];
        return new Rect(l, t, l + v.getWidth(), t + v.getHeight());
    }

    /** a soft squircle outline where the app will land */
    /**
     * True if the dragged app is over the icon of another app or a folder on a page (not at
     * the cell's edge): dropping makes or fills a folder.
     */
    private boolean mergeAt(Drag d, HomeModel.Loc loc, float x, float y) {
        if (loc == null || loc.container == HomeModel.DOCK || d.app == null) {
            return false;
        }
        final String there = mModel.at(loc);
        if (there.isEmpty() || there.equals(d.key)) {
            return false;
        }
        final Rect w = boundsInRoot(mWorkspace);
        final float cx = w.left + mWorkspace.cellLeft(loc.index) + mWorkspace.cellWidth() / 2f;
        final float cy = w.top + mWorkspace.cellTop(loc.container, loc.index)
                + mIcons.size() / 2f + 6 * getResources().getDisplayMetrics().density;
        return Math.hypot(x - cx, y - cy) < mIcons.size() * 0.42f;
    }

    /** a soft squircle outline where the app will land (larger: into a folder) */
    private void showHint(HomeModel.Loc loc, boolean merge) {
        if (loc == null) {
            mDropHint.setVisibility(View.GONE);
            return;
        }
        final float d = getResources().getDisplayMetrics().density;
        final int size = mIcons.size() + Math.round((merge ? 24 : 12) * d);
        float cx;
        float cy;
        if (loc.container == HomeModel.DOCK) {
            final Rect r = boundsInRoot(mDock);
            final float slot = r.width() / (float) HomeModel.DOCK_SIZE;
            cx = r.left + slot * (loc.index + 0.5f);
            cy = r.exactCenterY();
        } else {
            final Rect w = boundsInRoot(mWorkspace);
            cx = w.left + mWorkspace.cellLeft(loc.index) + mWorkspace.cellWidth() / 2f;
            // the icon sits at the top of its cell (label below)
            cy = w.top + mWorkspace.cellTop(loc.container, loc.index) + mIcons.size() / 2f
                    + 6 * getResources().getDisplayMetrics().density;
        }
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mDropHint.getLayoutParams();
        if (lp.width != size) {
            lp.width = size;
            lp.height = size;
            mDropHint.setLayoutParams(lp);
        }
        final boolean wasVisible = mDropHint.getVisibility() == View.VISIBLE;
        mDropHint.setVisibility(View.VISIBLE);
        final float tx = cx - size / 2f;
        final float ty = cy - size / 2f;
        if (wasVisible) {
            mDropHint.animate().x(tx).y(ty).setDuration(120).setInterpolator(KiroTouch.EASE)
                    .start();
        } else {
            mDropHint.setX(tx);
            mDropHint.setY(ty);
        }
    }

    /** holding an app at a side edge turns the page after a moment */
    private void trackEdge(Drag d, float x, float y) {
        final float edge = 28 * getResources().getDisplayMetrics().density;
        final Rect w = boundsInRoot(mWorkspace);
        int side = 0;
        if (y >= w.top && y <= w.bottom) {
            if (x < edge) {
                side = -1;
            } else if (x > mRoot.getWidth() - edge) {
                side = 1;
            }
        }
        if (side != d.edge) {
            d.edge = side;
            mHandler.removeCallbacks(mEdgePage);
            if (side != 0) {
                mHandler.postDelayed(mEdgePage, EDGE_PAGE_DELAY);
            }
        }
    }

    private final Runnable mEdgePage = new Runnable() {
        @Override
        public void run() {
            final Drag d = mDrag;
            if (d == null || d.edge == 0) {
                return;
            }
            final int page = mWorkspace.currentPage() + d.edge;
            if (page >= 0 && page < mWorkspace.pageCount()) {
                mWorkspace.snapTo(page, true);
                mDropHint.setVisibility(View.GONE);
                // still at the edge: keep turning
                mHandler.postDelayed(this, EDGE_PAGE_DELAY + 250);
            }
        }
    };

    // --- pages ---------------------------------------------------------------------------

    @Override
    public void onPageDragStart() {
        mWorkspace.beginDrag();
    }

    @Override
    public void onPageDrag(float dx) {
        mWorkspace.drag(dx);
    }

    @Override
    public void onPageRelease(float velocityX, float minFling) {
        mWorkspace.release(velocityX, minFling);
    }

    @Override
    public boolean onTouchDown() {
        return mDrag == null && !isDrawerOpen() && mWorkspace.catchScroll();
    }

    @Override
    public void onTouchDone() {
        if (mDrag == null) {
            mWorkspace.settle();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mDrag == null) {
            mWorkspace.settle();
            mWorkspace.notifyScroll();
        }
    }

    // --- drawer -------------------------------------------------------------------------

    @Override
    public boolean isDrawerOpen() {
        return mDrawerProgress > 0.5f;
    }

    @Override
    public void onDrawerDrag(float progress) {
        setDrawerProgress(progress);
    }

    @Override
    public void onDrawerRelease(boolean open) {
        animateDrawer(open);
    }

    void animateDrawer(final boolean open) {
        final float from = mDrawerProgress;
        final float to = open ? 1f : 0f;
        final android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
        anim.setDuration((long) (160 + 180 * Math.abs(to - from)));
        anim.setInterpolator(KiroTouch.EASE);
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                setDrawerProgress((float) a.getAnimatedValue());
            }
        });
        anim.start();
        if (!open) {
            hideKeyboard();
            mSearch.setText("");
            mDrawerGrid.setSelection(0);
        }
    }

    private void setDrawerProgress(float p) {
        if (mDrawerProgress == 0f && p > 0f) {
            // opening: the icons cascade in again (Kiro.GridView layout animation)
            mDrawerGrid.scheduleLayoutAnimation();
        }
        mDrawerProgress = p;
        final float h = mRoot.getHeight() > 0 ? mRoot.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        // While it moves, the drawer and the home fade and scale: as GPU textures (hardware
        // layers) that costs one blend per frame instead of redrawing both view trees into
        // an offscreen buffer every frame. At rest they are plain views again, and the home
        // under an open drawer is not drawn at all.
        final boolean moving = p > 0f && p < 1f;
        setLayer(mDrawer, moving);
        setLayer(mHomeContent, moving);
        mHomeContent.setVisibility(p < 1f ? View.VISIBLE : View.INVISIBLE);
        mDrawer.setVisibility(p > 0f ? View.VISIBLE : View.INVISIBLE);
        mDrawer.setTranslationY((1f - p) * h * 0.35f);
        mDrawer.setAlpha(p);
        mHomeContent.setAlpha(1f - p);
        mHomeContent.setScaleX(1f - 0.06f * p);
        mHomeContent.setScaleY(1f - 0.06f * p);
    }

    private static void setLayer(View v, boolean hardware) {
        final int type = hardware ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
        if (v.getLayerType() != type) {
            v.setLayerType(type, null);
        }
    }

    private void hideKeyboard() {
        final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(mSearch.getWindowToken(), 0);
    }

    // --- gestures on the home -----------------------------------------------------------

    @Override
    public void onSwipeDown(boolean twoFingers) {
        final StatusBarManager sbm = (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
        if (sbm == null) {
            return;
        }
        if (twoFingers) {
            sbm.expandSettingsPanel();
        } else {
            sbm.expandNotificationsPanel();
        }
    }

    /**
     * Shows or removes the clock of the first page. Its room goes to the apps: the first
     * page gains a row at the top (the grid is anchored at the bottom, the apps stay put);
     * when it comes back, apps in that row move on (Workspace.Listener.onRowsChanged).
     */
    private void showClock(boolean show) {
        mClock.setVisibility(show ? View.VISIBLE : View.GONE);
        getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean(KEY_CLOCK, show).apply();
    }

    @Override
    public void onEmptyLongPress(float x, float y) {
        mRoot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        final View anchor = findViewById(R.id.menu_anchor);
        anchor.setX(x);
        anchor.setY(y);
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.add_widget);
        menu.getMenu().add(0, 2, 0, R.string.wallpaper);
        if (mClock.getVisibility() != View.VISIBLE) {
            menu.getMenu().add(0, 4, 0, R.string.show_clock);
        }
        menu.getMenu().add(0, 3, 0, R.string.settings);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        pickWidget();
                        return true;
                    case 4:
                        showClock(true);
                        return true;
                    case 2:
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER),
                                getText(R.string.wallpaper)));
                        return true;
                    case 3:
                        startActivity(new Intent(Settings.ACTION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    // --- widgets ------------------------------------------------------------------------

    private void pickWidget() {
        final int id = mWidgets.allocateId();
        final Intent pick = new Intent(AppWidgetManager.ACTION_APPWIDGET_PICK);
        pick.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        try {
            startActivityForResult(pick, REQUEST_PICK_WIDGET);
        } catch (ActivityNotFoundException e) {
            mWidgets.deleteId(id);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        final int id = data != null
                ? data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) : -1;
        if (id == -1) {
            return;
        }
        if (resultCode != RESULT_OK) {
            mWidgets.deleteId(id);
            return;
        }
        if (requestCode == REQUEST_PICK_WIDGET) {
            final AppWidgetProviderInfo info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id);
            if (info != null && info.configure != null) {
                try {
                    mWidgets.host().startAppWidgetConfigureActivityForResult(this, id, 0,
                            REQUEST_CONFIGURE_WIDGET, null);
                    return;
                } catch (ActivityNotFoundException e) {
                    // add it unconfigured
                }
            }
            mWidgets.add(id);
        } else if (requestCode == REQUEST_CONFIGURE_WIDGET) {
            mWidgets.add(id);
        }
    }

    /** Called by the widget stack when the user long-presses a widget. */
    void onWidgetLongPress(final AppWidgetHostView view, final int id) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        final PopupMenu menu = new PopupMenu(this, view);
        menu.getMenu().add(0, 1, 0, R.string.remove_widget);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                mWidgets.remove(id);
                return true;
            }
        });
        menu.show();
    }
}
