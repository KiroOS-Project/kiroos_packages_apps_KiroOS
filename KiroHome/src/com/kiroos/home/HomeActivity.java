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
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;

import java.util.ArrayList;
import java.util.List;

/**
 * Kiro Home v1.0: clock, widgets, a grid of favourite apps and a dock over the wallpaper.
 * Swipe up anywhere for the vertical app drawer, swipe down anywhere for notifications
 * (two fingers for quick settings). Apps open with a zoom from their icon's position.
 */
public class HomeActivity extends Activity implements HomeLayout.Callbacks,
        AppRepository.Listener {

    private static final int REQUEST_PICK_WIDGET = 1;
    private static final int REQUEST_CONFIGURE_WIDGET = 2;

    static final int GRID_COLUMNS = 4;
    static final int GRID_ROWS = 5;
    static final int DOCK_SIZE = 4;

    private IconFactory mIcons;
    private AppRepository mRepo;
    private Favorites mFavorites;
    private WidgetStack mWidgets;

    private HomeLayout mRoot;
    private View mHomeContent;
    private LinearLayout mGrid;
    private LinearLayout mDock;
    private View mDrawer;
    private GridView mDrawerGrid;
    private EditText mSearch;
    private DrawerAdapter mDrawerAdapter;
    private float mDrawerProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(R.layout.activity_home);

        mIcons = new IconFactory(this);
        mRepo = new AppRepository(this, mIcons);
        mFavorites = new Favorites(getSharedPreferences("home", MODE_PRIVATE));

        mRoot = (HomeLayout) findViewById(R.id.root);
        mRoot.setCallbacks(this);
        mHomeContent = findViewById(R.id.home_content);
        mGrid = (LinearLayout) findViewById(R.id.home_grid);
        mDock = (LinearLayout) findViewById(R.id.dock);
        mDrawer = findViewById(R.id.drawer);
        mDrawerGrid = (GridView) findViewById(R.id.drawer_grid);
        mSearch = (EditText) findViewById(R.id.drawer_search);

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
                showAppMenu(mDrawerAdapter.getItem(position), view, true);
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
        KiroTouch.enter(findViewById(R.id.clock_block), mGrid, mDock);
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
    }

    @Override
    protected void onDestroy() {
        mRepo.destroy();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: close the drawer
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && isDrawerOpen()) {
            animateDrawer(false);
        }
    }

    @Override
    public void onBackPressed() {
        if (isDrawerOpen()) {
            animateDrawer(false);
        }
    }

    // --- apps ---------------------------------------------------------------------------

    @Override
    public void onAppsChanged(List<AppInfo> apps) {
        mFavorites.seedDefaults(mRepo);
        mDrawerAdapter.setApps(apps);
        mDrawerAdapter.filter(mSearch.getText().toString());
        bindHome();
    }

    private void bindHome() {
        final LayoutInflater inflater = LayoutInflater.from(this);
        mGrid.removeAllViews();
        final List<AppInfo> grid = resolve(mFavorites.grid());
        int i = 0;
        for (int r = 0; r < GRID_ROWS && i < grid.size(); r++) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < GRID_COLUMNS; c++, i++) {
                if (i < grid.size()) {
                    row.addView(bindIcon(inflater, row, grid.get(i), true),
                            new LinearLayout.LayoutParams(0,
                                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                } else {
                    // empty cell: keeps the columns aligned (a wrap_content View would
                    // take all the height it is offered)
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
                }
            }
            mGrid.addView(row);
        }
        mDock.removeAllViews();
        for (AppInfo app : resolve(mFavorites.dock())) {
            final View cell = bindIcon(inflater, mDock, app, false);
            mDock.addView(cell, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private List<AppInfo> resolve(List<String> keys) {
        final List<AppInfo> out = new ArrayList<>();
        for (String k : keys) {
            final AppInfo a = mRepo.find(k);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    private View bindIcon(LayoutInflater inflater, ViewGroup parent, final AppInfo app,
            boolean withLabel) {
        final View v = inflater.inflate(R.layout.item_app, parent, false);
        final ImageView icon = (ImageView) v.findViewById(R.id.app_icon);
        icon.setImageDrawable(mIcons.icon(app));
        mIcons.lift(icon);
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
                showAppMenu(app, view, false);
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

    private void showAppMenu(final AppInfo app, View anchor, final boolean fromDrawer) {
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        final PopupMenu menu = new PopupMenu(this, anchor, Gravity.CENTER);
        final Menu m = menu.getMenu();
        final boolean onHome = mFavorites.contains(app.key());
        if (onHome) {
            m.add(0, 1, 0, R.string.remove_from_home);
        } else {
            m.add(0, 2, 0, R.string.add_to_home);
        }
        if (!mFavorites.dock().contains(app.key())) {
            m.add(0, 3, 0, R.string.add_to_dock);
        }
        m.add(0, 4, 0, R.string.app_info);
        m.add(0, 5, 0, R.string.uninstall);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        mFavorites.remove(app.key());
                        bindHome();
                        return true;
                    case 2:
                        if (!mFavorites.addToGrid(app.key(), GRID_COLUMNS * GRID_ROWS)) {
                            Toast.makeText(HomeActivity.this, R.string.home_full,
                                    Toast.LENGTH_SHORT).show();
                        }
                        bindHome();
                        return true;
                    case 3:
                        mFavorites.addToDock(app.key(), DOCK_SIZE);
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
        mDrawer.setVisibility(p > 0f ? View.VISIBLE : View.INVISIBLE);
        mDrawer.setTranslationY((1f - p) * h * 0.35f);
        mDrawer.setAlpha(p);
        mHomeContent.setAlpha(1f - p);
        mHomeContent.setScaleX(1f - 0.06f * p);
        mHomeContent.setScaleY(1f - 0.06f * p);
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

    @Override
    public void onEmptyLongPress(float x, float y) {
        mRoot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        final View anchor = findViewById(R.id.menu_anchor);
        anchor.setX(x);
        anchor.setY(y);
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.add_widget);
        menu.getMenu().add(0, 2, 0, R.string.wallpaper);
        menu.getMenu().add(0, 3, 0, R.string.settings);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        pickWidget();
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
