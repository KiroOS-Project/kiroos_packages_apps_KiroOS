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
 * Kiro Home 2.0: clock, widgets, pages of apps and a dock over the wallpaper.
 * 2.0: apps are dragged inside the home (no system drag and drop): the icon lifts, follows
 * the finger and flies into its cell; the drawer's search box can no longer catch a drop.
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
        /** Kiro Home 2.0: the lifted copy of the icon, where it came from, the finger's grip */
        ImageView floating;
        Rect home;
        float offX;
        float offY;
    }

    /** how much a lifted app grows under the finger */
    private static final float DRAG_LIFT = 1.15f;

    private Drag mDrag;
    /** the open folder and its key, or null */
    private FolderView mFolderView;
    private String mOpenFolder;
    /** the icon positions last published for the close-to-icon animation */
    private String mPublishedIcons;
    private final int[] mIconLoc = new int[2];

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
                if (Math.abs(position - Math.round(position)) < 0.001f) {
                    schedulePublishIcons(150);
                }
                // the clock and the widgets belong to the first page and move with it
                final float first = Math.max(0f, Math.min(1f, position)) * mRoot.getWidth();
                mHeader.setTranslationX(-first);
                // the wallpaper stays still while the pages glide over it, like on the phones
                // the user compares with: moved by the window manager in its own transaction,
                // up to 1440 px across the pages (its surface is 2160 px wide at 720p), it ran
                // as fast as the icons or faster and a frame apart from them
                centerWallpaper();
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
    protected void onResume() {
        super.onResume();
        catchClosingApp();
        schedulePublishIcons(700);
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
        if (mModel.takeFreshInstall()) {
            // a new phone: the home is set like out of the box, the kAI bar under the clock
            mWidgets.seedDefaults();
        }
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
        schedulePublishIcons(300);
    }

    private final Runnable mPublishIcons = new Runnable() {
        @Override
        public void run() {
            publishIcons();
        }
    };

    private android.os.IBinder mWallpaperCentered;

    /** the wallpaper's middle, set once per window (not on every frame of a page swipe) */
    private void centerWallpaper() {
        final android.os.IBinder token = mRoot.getWindowToken();
        if (token == null || token == mWallpaperCentered) {
            return;
        }
        mWallpaperCentered = token;
        mWallpaperManager.setWallpaperOffsetSteps(0f, 0f);
        mWallpaperManager.setWallpaperOffsets(token, 0.5f, 0.5f);
    }

    private void schedulePublishIcons(long delayMs) {
        mHandler.removeCallbacks(mPublishIcons);
        mHandler.postDelayed(mPublishIcons, delayMs);
    }

    /**
     * Tells the system where each app's icon is on screen (the current page and the dock; the
     * apps of a folder point at the folder), so a closing app shrinks back into its icon
     * wherever it was opened from (AppTransition, Settings.Global kiroos_home_icons).
     */
    private void publishIcons() {
        if (mRoot.getWindowToken() == null || mDrag != null || isDrawerOpen()
                || mWorkspace.scroll() != mWorkspace.currentPage() * mWorkspace.getWidth()) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        final int page = mWorkspace.currentPage();
        for (int i = 0; i < mWorkspace.getChildCount(); i++) {
            final View c = mWorkspace.getChildAt(i);
            if (((Workspace.LayoutParams) c.getLayoutParams()).page == page) {
                appendIcons(sb, c);
            }
        }
        for (int i = 0; i < mDock.getChildCount(); i++) {
            appendIcons(sb, mDock.getChildAt(i));
        }
        final String icons = sb.toString();
        if (!icons.equals(mPublishedIcons)) {
            mPublishedIcons = icons;
            try {
                Settings.Global.putString(getContentResolver(), "kiroos_home_icons", icons);
            } catch (SecurityException e) {
                // not the system build: apps close into the icon they were opened from
            }
        }
    }

    private void appendIcons(StringBuilder sb, View cell) {
        final Object tag = cell.getTag(R.id.kiro_icon_packages);
        final View icon = cell.findViewById(R.id.app_icon);
        if (!(tag instanceof String[]) || icon == null || icon.getWidth() == 0) {
            return;
        }
        icon.getLocationOnScreen(mIconLoc);
        final String rect = ":" + mIconLoc[0] + "," + mIconLoc[1] + ","
                + (mIconLoc[0] + icon.getWidth()) + "," + (mIconLoc[1] + icon.getHeight()) + ";";
        for (String pkg : (String[]) tag) {
            sb.append(pkg).append(rect);
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
        final List<AppInfo> inside = folderApps(f);
        icon.setImageDrawable(mIcons.folderIcon(inside));
        final String[] pkgs = new String[inside.size()];
        for (int i = 0; i < pkgs.length; i++) {
            pkgs[i] = inside.get(i).component.getPackageName();
        }
        v.setTag(R.id.kiro_icon_packages, pkgs);
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
        v.setTag(R.id.kiro_icon_packages, new String[] {app.component.getPackageName()});
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

    /**
     * Opens the app out of its icon: the framework grows the app window from the icon's square
     * (AppTransition, KiroOS launch zoom) while the home steps back. The icon's picture no
     * longer goes along as a thumbnail layer: over the window it showed as a large dark
     * square with dark icons (TikTok), split from the app by the home stepping back.
     */
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
            return;
        }
        // Home 2.0: the icon becomes the app. Its picture leaves as the window grows out of
        // it, so no second copy of the icon stays behind the opening app; it is back a moment
        // later (the app covers the home by then, or it is a small window over it)
        final View icon = from != null ? from.findViewById(R.id.app_icon) : null;
        if (icon != null) {
            icon.animate().cancel();
            icon.animate().alpha(0f).setStartDelay(40).setDuration(110).start();
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    icon.animate().cancel();
                    icon.setAlpha(1f);
                }
            }, 900);
        }
    }

    /** how far into the system's close (320 ms) the app melts into its icon */
    private static final long CATCH_DELAY = 210;

    /**
     * Home 2.0: an app that just went back to the home lands in its icon, and the icon catches
     * it: it appears as the app melts into it and settles with a small bounce. Only right
     * after an app left (its task was active in the last moment), not on screen on.
     */
    private void catchClosingApp() {
        String pkg = null;
        try {
            final android.app.ActivityManager am = getSystemService(
                    android.app.ActivityManager.class);
            for (android.app.ActivityManager.RecentTaskInfo t : am.getRecentTasks(3,
                    android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                if (t.baseIntent == null || t.baseIntent.getComponent() == null) continue;
                final String p = t.baseIntent.getComponent().getPackageName();
                if (p.equals(getPackageName())) continue;
                if (System.currentTimeMillis() - t.lastActiveTime < 1500) pkg = p;
                break;
            }
        } catch (RuntimeException e) {
            return;
        }
        if (pkg == null || isDrawerOpen()) return;
        final View icon = visibleIcon(pkg);
        if (icon == null) return;
        icon.animate().cancel();
        icon.setAlpha(0f);
        icon.setScaleX(0.8f);
        icon.setScaleY(0.8f);
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(CATCH_DELAY)
                .setDuration(360).setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();
    }

    /** the icon picture of an app on the page shown or in the dock (a folder counts) */
    private View visibleIcon(String pkg) {
        final int page = mWorkspace.currentPage();
        for (int i = 0; i < mWorkspace.getChildCount() + mDock.getChildCount(); i++) {
            final View c;
            if (i < mWorkspace.getChildCount()) {
                c = mWorkspace.getChildAt(i);
                if (((Workspace.LayoutParams) c.getLayoutParams()).page != page) continue;
            } else {
                c = mDock.getChildAt(i - mWorkspace.getChildCount());
            }
            final Object tag = c.getTag(R.id.kiro_icon_packages);
            if (!(tag instanceof String[])) continue;
            for (String p : (String[]) tag) {
                if (pkg.equals(p)) return c.findViewById(R.id.app_icon);
            }
        }
        return null;
    }

    private void showAppMenu(final AppInfo app, View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor, Gravity.CENTER);
        final Menu m = menu.getMenu();
        if (mModel.onHome(app.key())) {
            m.add(0, 1, 0, R.string.remove_from_home);
        } else {
            m.add(0, 2, 0, R.string.add_to_home);
        }
        final Intent lock = appLockIntent(app);
        if (lock != null) {
            m.add(0, 6, 0, APP_LOCK_UNLOCK.equals(lock.getAction()) ? R.string.unlock_app
                    : R.string.lock_app);
        }
        m.add(0, 4, 0, R.string.app_info);
        m.add(0, 5, 0, R.string.uninstall);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 6:
                        try {
                            startActivity(lock);
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Toast.makeText(HomeActivity.this, R.string.app_not_available,
                                    Toast.LENGTH_SHORT).show();
                        }
                        return true;
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

    /** KiroOS App Lock (com.kiroos.applock): the locked packages and the lock/unlock screen */
    private static final String APP_LOCK_PACKAGE = "com.kiroos.applock";
    private static final String APP_LOCK_LIST = "kiroos_applock_packages";
    private static final String APP_LOCK_LOCK = "com.kiroos.applock.action.LOCK_APP";
    private static final String APP_LOCK_UNLOCK = "com.kiroos.applock.action.UNLOCK_APP";

    /**
     * "Lock app" or "Unlock app" for this app; null when App Lock is not there or the app must
     * always open (the home screen, the phone, App Lock) or belongs to another profile.
     */
    private Intent appLockIntent(AppInfo app) {
        final String pkg = app.component.getPackageName();
        if (!android.os.Process.myUserHandle().equals(app.user)
                || pkg.equals(getPackageName()) || pkg.equals(APP_LOCK_PACKAGE)) {
            return null;
        }
        final android.telecom.TelecomManager tm = getSystemService(
                android.telecom.TelecomManager.class);
        if (tm != null && pkg.equals(tm.getDefaultDialerPackage())) {
            return null;
        }
        final String list = Settings.Secure.getString(getContentResolver(), APP_LOCK_LIST);
        final boolean locked = list != null
                && java.util.Arrays.asList(list.split(",")).contains(pkg);
        final Intent i = new Intent(locked ? APP_LOCK_UNLOCK : APP_LOCK_LOCK)
                .setComponent(new android.content.ComponentName(APP_LOCK_PACKAGE,
                        APP_LOCK_PACKAGE + ".LockAppActivity"))
                .putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                // a fresh screen for every request: an earlier one left open (say, at "Set
                // screen lock") would otherwise come back instead, and this app be ignored
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return getPackageManager().resolveActivity(i, 0) != null ? i : null;
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
        final View picture = icon != null && icon.getWidth() > 0 ? icon : cell;
        if (picture.getWidth() <= 0 || picture.getHeight() <= 0) {
            return;
        }
        final Drag d = new Drag();
        d.app = app;
        d.key = key;
        d.from = from;
        d.fromFolder = fromFolder;
        d.source = cell;
        // Kiro Home 2.0: the app is lifted in the home itself (no system drag and drop): a
        // copy of its icon floats over everything, under the finger, slightly larger
        final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                picture.getWidth(), picture.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        picture.draw(new android.graphics.Canvas(bmp));
        final ImageView floating = new ImageView(this);
        floating.setImageBitmap(bmp);
        final Rect at = boundsInRoot(picture);
        mRoot.addView(floating, new FrameLayout.LayoutParams(at.width(), at.height()));
        floating.setX(at.left);
        floating.setY(at.top);
        floating.setElevation(12 * getResources().getDisplayMetrics().density);
        floating.animate().scaleX(DRAG_LIFT).scaleY(DRAG_LIFT).setDuration(160)
                .setInterpolator(KiroTouch.EASE).start();
        d.floating = floating;
        d.home = at;
        d.offX = mRoot.lastX() - at.left;
        d.offY = mRoot.lastY() - at.top;
        d.startX = mRoot.lastX();
        d.startY = mRoot.lastY();
        mDrag = d;
        // an extra empty page to drop on while dragging
        mWorkspace.setPageCount(mModel.pageCount() + 1);
        mIndicator.setCount(mModel.pageCount() + 1);
        if (from != null) {
            cell.setAlpha(0.25f);
        }
    }

    @Override
    public boolean isDragging() {
        return mDrag != null && mDrag.floating != null;
    }

    @Override
    public void onDragMove(float x, float y) {
        final Drag d = mDrag;
        if (d == null) {
            return;
        }
        d.floating.setX(x - d.offX);
        d.floating.setY(y - d.offY);
        if (!d.moved && Math.hypot(x - d.startX, y - d.startY) > mTouchSlop * 2) {
            d.moved = true;
            if (d.from == null && d.fromFolder == null) {
                // from the drawer: it steps aside so the home is the drop area
                animateDrawer(false);
            }
        }
        if (d.moved) {
            final HomeModel.Loc at = locAt(x, y);
            showHint(at, mergeAt(d, at, x, y));
            trackEdge(d, x, y);
        }
    }

    @Override
    public void onDragEnd(float x, float y, boolean dropped) {
        final Drag d = mDrag;
        if (d == null) {
            return;
        }
        HomeModel.Loc to = null;
        boolean merged = false;
        if (dropped && d.moved) {
            to = locAt(x, y);
            if (to != null) {
                // onto another app: a folder; anywhere else: that cell
                merged = mergeAt(d, to, x, y)
                        && mModel.merge(d.key, to, getString(R.string.folder_default));
                if (!merged) {
                    if (d.fromFolder != null) {
                        mModel.removeFromFolder(d.fromFolder, d.key);
                    }
                    mModel.place(d.key, to);
                }
            }
        }
        endDrag(d, to, merged);
    }

    private void endDrag(Drag d, HomeModel.Loc to, boolean merged) {
        mHandler.removeCallbacks(mEdgePage);
        mDrag = null;
        mDropHint.setVisibility(View.GONE);
        d.source.setAlpha(1f);
        final int page = Math.min(mWorkspace.currentPage(), Math.max(0, mModel.pageCount() - 1));
        bindHome();
        mWorkspace.snapTo(page, true);
        settleFloating(d, to, merged);
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

    /**
     * Kiro Home 2.0: the lifted copy lands. Dropped on a cell it flies into the icon now
     * there (which shows once it has landed); into a folder it shrinks into the folder; not
     * moved or dropped nowhere it goes back home. Nothing to fly to: it fades where it is.
     */
    private void settleFloating(final Drag d, final HomeModel.Loc to, final boolean merged) {
        final ImageView floating = d.floating;
        if (floating == null) {
            return;
        }
        floating.animate().cancel();
        mRoot.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                final HomeModel.Loc land = to != null ? to : d.from;
                final View cell = land != null ? cellView(land) : null;
                final View icon = cell != null ? cell.findViewById(R.id.app_icon) : null;
                final Rect target;
                if (icon != null && icon.isAttachedToWindow() && icon.getWidth() > 0) {
                    target = boundsInRoot(icon);
                } else if (to == null && d.from == null && d.fromFolder == null) {
                    target = d.home; // back into the drawer
                } else {
                    target = null;
                }
                if (target == null) {
                    floating.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(150)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    mRoot.removeView(floating);
                                }
                            }).start();
                    return true;
                }
                final View hidden = merged ? null : icon;
                if (hidden != null) {
                    hidden.setAlpha(0f);
                }
                // the copy is sized like the icon it came from: scale onto the target's size
                final float scale = merged ? 0.5f : target.width() / (float) Math.max(1,
                        floating.getWidth());
                floating.animate()
                        .x(target.exactCenterX() - floating.getWidth() / 2f)
                        .y(target.exactCenterY() - floating.getHeight() / 2f)
                        .scaleX(scale).scaleY(scale)
                        .alpha(merged ? 0f : 1f)
                        .setDuration(merged ? 200 : 240)
                        .setInterpolator(KiroTouch.EASE)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (hidden != null) {
                                    hidden.setAlpha(1f);
                                }
                                mRoot.removeView(floating);
                                schedulePublishIcons(150);
                            }
                        }).start();
                return true;
            }
        });
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
    public void onDrawerRelease(boolean open, float speed) {
        animateDrawer(open, speed);
    }

    void animateDrawer(final boolean open) {
        animateDrawer(open, 0f);
    }

    private android.animation.ValueAnimator mDrawerAnim;

    /**
     * Kiro Home 2.0: released by a moving finger, the drawer glides on from the finger's own
     * speed (the gesture and the animation are one movement); otherwise it eases in place.
     */
    void animateDrawer(final boolean open, float speed) {
        final float from = mDrawerProgress;
        final float to = open ? 1f : 0f;
        if (mDrawerAnim != null) {
            mDrawerAnim.cancel();
        }
        final android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(from, to);
        mDrawerAnim = anim;
        // the drag maps 60% of the screen height to the whole way (HomeLayout)
        final float distance = Math.abs(to - from) * mRoot.getHeight() * 0.6f;
        final long fallback = (long) (160 + 180 * Math.abs(to - from));
        final long duration = Glide.duration(distance, speed, 140, 340, fallback);
        anim.setDuration(duration);
        if (speed > 0f && distance >= 1f) {
            anim.setInterpolator(new Glide(speed * duration / 1000f / distance));
        } else {
            anim.setInterpolator(KiroTouch.EASE);
        }
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
