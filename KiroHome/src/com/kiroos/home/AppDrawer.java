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

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.AsyncTask;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Kiro Home Beta 5: the app drawer. A sheet that rises from the bottom under the finger (one
 * to one, then a spring carries it on with the finger's speed), over a dimmed home that steps
 * back. On it: a search pill, the apps used most recently, then all apps in rows of four, the
 * rows rising into place one after the other; an A-Z rail on the right jumps through the list.
 * Pulled down from the top of the list, the sheet follows the finger back down.
 */
public class AppDrawer extends FrameLayout {

    interface Host {
        void launch(AppInfo app, View icon);

        /** a long press on an app of the drawer: it lifts and can be dropped on the home */
        void startDragFromDrawer(AppInfo app, View cell);

        /** the finger pulls the open sheet down: progress 1 = open, 0 = closed */
        void onDrawerDrag(float progress);

        /** the finger let go of the sheet: settle open or closed with its speed (px/s) */
        void onDrawerRelease(boolean open, float speed);
    }

    private static final int COLUMNS = 4;

    private final float mDp;
    private final View mScrim;
    private final Glass mGlass;
    private final GradientDrawable mSheetBg;
    private final LinearLayout mSheet;
    private final SearchField mSearch;
    private final ListView mList;
    private final LetterRail mRail;
    private final View mHeader;
    private final LinearLayout mRecentRow;
    private final TextView mEmpty;
    private final RowsAdapter mAdapter = new RowsAdapter();
    private final int mSlop;

    private Host mHost;
    private IconFactory mIcons;
    private List<AppInfo> mAll = Collections.emptyList();
    private List<AppInfo> mShown = Collections.emptyList();
    private String mQuery = "";
    private float mProgress;
    private int mTopInset;

    public AppDrawer(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mScrim = new View(context);
        mScrim.setBackgroundColor(0xFF000000);
        mScrim.setAlpha(0f);
        addView(mScrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // frosted glass under the sheet (the blurred wallpaper), fixed to the screen
        mGlass = new Glass(context);
        addView(mGlass, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mSheet = new LinearLayout(context);
        mSheet.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable bg = new GradientDrawable();
        mSheetBg = bg;
        bg.setColor(0xF7101115);
        final float r = 28 * mDp;
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        mSheet.setBackground(bg);
        mSheet.setClickable(true); // touches never fall through to the home under it
        addView(mSheet, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // the handle
        final View handle = new View(context);
        final GradientDrawable hb = new GradientDrawable();
        hb.setColor(0x47FFFFFF);
        hb.setCornerRadius(2 * mDp);
        handle.setBackground(hb);
        final LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(10);
        mSheet.addView(handle, hlp);

        // the search pill
        mSearch = new SearchField(context, null);
        final GradientDrawable sb = new GradientDrawable();
        sb.setColor(0x13FFFFFF);
        sb.setCornerRadius(21 * mDp);
        mSearch.setBackground(sb);
        mSearch.setHint(R.string.search_apps);
        mSearch.setHintTextColor(0x8CFFFFFF);
        mSearch.setTextColor(0xFFFFFFFF);
        mSearch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mSearch.setSingleLine(true);
        mSearch.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mSearch.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mSearch.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        mSearch.setCompoundDrawablePadding(dp(10));
        mSearch.setPadding(dp(16), 0, dp(16), 0);
        mSearch.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        slp.setMargins(dp(16), dp(14), dp(16), dp(6));
        mSheet.addView(mSearch, slp);

        // the list, with the A-Z rail over its right edge
        final FrameLayout listArea = new FrameLayout(context);
        mSheet.addView(listArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        mList = new ListView(context);
        mList.setDivider(null);
        mList.setDividerHeight(0);
        mList.setSelector(android.R.color.transparent);
        mList.setCacheColorHint(0);
        mList.setVerticalScrollBarEnabled(false);
        mList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mList.setClipToPadding(false);
        mList.setPadding(dp(6), 0, dp(14), dp(24));
        listArea.addView(mList, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mRail = new LetterRail(context);
        final FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(18),
                LayoutParams.MATCH_PARENT, Gravity.END);
        rlp.topMargin = dp(150);
        rlp.bottomMargin = dp(40);
        listArea.addView(mRail, rlp);
        mEmpty = new TextView(context);
        mEmpty.setText(R.string.drawer_no_results);
        mEmpty.setTextColor(0x8CFFFFFF);
        mEmpty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mEmpty.setGravity(Gravity.CENTER_HORIZONTAL);
        mEmpty.setPadding(0, dp(48), 0, 0);
        mEmpty.setVisibility(GONE);
        listArea.addView(mEmpty, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // the list's header: the recent apps, then "All apps"
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.addView(label(R.string.drawer_recent, dp(16)));
        mRecentRow = new LinearLayout(context);
        mRecentRow.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(mRecentRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        final View line = new View(context);
        line.setBackgroundColor(0x14FFFFFF);
        final LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(mDp)));
        llp.setMargins(dp(10), dp(16), dp(4), 0);
        header.addView(line, llp);
        header.addView(label(R.string.drawer_all, dp(16)));
        mHeader = header;
        mList.addHeaderView(header, null, false);
        mList.setAdapter(mAdapter);

        mList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                // rows recycled while scrolling must not keep the opening's offsets
                if (mProgress >= 1f) settleRows();
            }
        });

        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        mSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (!mShown.isEmpty() && mHost != null) {
                    mHost.launch(mShown.get(0), null);
                    return true;
                }
                return false;
            }
        });
        setProgress(0f);
    }

    private TextView label(int text, int top) {
        final TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(0x80FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.05f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        t.setPadding(dp(14), top, dp(14), dp(10));
        return t;
    }

    private int dp(float v) {
        return Math.round(v * mDp);
    }

    void setup(Host host, IconFactory icons) {
        mHost = host;
        mIcons = icons;
    }

    void setInsets(int top, int bottom) {
        mTopInset = top;
        final LayoutParams lp = (LayoutParams) mSheet.getLayoutParams();
        if (lp.topMargin != top) {
            lp.topMargin = top;
            mSheet.setLayoutParams(lp);
        }
        mList.setPadding(mList.getPaddingLeft(), 0, mList.getPaddingRight(), dp(24) + bottom);
    }

    // --- apps --------------------------------------------------------------------------

    void setApps(List<AppInfo> apps) {
        mAll = apps;
        mRail.setLetters(apps);
        apply();
    }

    private void filter(String query) {
        final String q = DrawerAdapter.normalize(query == null ? "" : query.trim());
        if (q.equals(mQuery)) return;
        mQuery = q;
        apply();
        mList.setSelection(0);
    }

    private void apply() {
        if (mQuery.isEmpty()) {
            mShown = mAll;
        } else {
            final List<AppInfo> prefix = new ArrayList<>();
            final List<AppInfo> inner = new ArrayList<>();
            for (AppInfo a : mAll) {
                final int at = a.searchKey.indexOf(mQuery);
                if (at == 0 || (at > 0 && !Character.isLetterOrDigit(a.searchKey.charAt(at - 1)))) {
                    prefix.add(a);
                } else if (at > 0) {
                    inner.add(a);
                }
            }
            prefix.addAll(inner);
            mShown = prefix;
        }
        // searching: the results only, no recent apps, no rail
        final boolean searching = !mQuery.isEmpty();
        mHeader.setVisibility(searching ? GONE : VISIBLE);
        setHeaderHeight(searching);
        mRail.setVisibility(searching ? GONE : VISIBLE);
        mEmpty.setVisibility(searching && mShown.isEmpty() ? VISIBLE : GONE);
        mAdapter.notifyDataSetChanged();
    }

    /** a GONE header of a ListView still takes its room: shrink it to nothing while searching */
    private void setHeaderHeight(boolean collapsed) {
        AbsListView.LayoutParams lp = (AbsListView.LayoutParams) mHeader.getLayoutParams();
        if (lp == null) {
            lp = new AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }
        lp.height = collapsed ? 1 : LayoutParams.WRAP_CONTENT;
        mHeader.setLayoutParams(lp);
    }

    /** the apps used most recently (the last three days), at most four, off the main thread */
    private void loadRecents() {
        final Context c = getContext();
        final List<AppInfo> all = mAll;
        new AsyncTask<Void, Void, List<AppInfo>>() {
            @Override
            protected List<AppInfo> doInBackground(Void... v) {
                final List<AppInfo> out = new ArrayList<>();
                try {
                    final UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
                    final long now = System.currentTimeMillis();
                    final List<UsageStats> stats = usm.queryUsageStats(
                            UsageStatsManager.INTERVAL_BEST, now - 3 * 24 * 3600 * 1000L, now);
                    if (stats == null) return out;
                    Collections.sort(stats, new Comparator<UsageStats>() {
                        @Override
                        public int compare(UsageStats a, UsageStats b) {
                            return Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed());
                        }
                    });
                    final java.util.Set<String> seen = new java.util.HashSet<>();
                    for (UsageStats s : stats) {
                        final String pkg = s.getPackageName();
                        if (s.getTotalTimeInForeground() <= 0 || !seen.add(pkg)) continue;
                        for (AppInfo a : all) {
                            if (a.component.getPackageName().equals(pkg)) {
                                out.add(a);
                                break;
                            }
                        }
                        if (out.size() == COLUMNS) break;
                    }
                } catch (RuntimeException e) {
                    // no usage access: no recent row
                }
                return out;
            }

            @Override
            protected void onPostExecute(List<AppInfo> recent) {
                bindRecents(recent);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void bindRecents(List<AppInfo> recent) {
        mRecentRow.removeAllViews();
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < COLUMNS; i++) {
            final View cell = i < recent.size() ? bindCell(inflater, mRecentRow, recent.get(i))
                    : new View(getContext());
            mRecentRow.addView(cell, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        final int vis = recent.isEmpty() ? GONE : VISIBLE;
        for (int i = 0; i < 3; i++) {
            ((ViewGroup) mHeader).getChildAt(i).setVisibility(vis);
        }
    }

    private View bindCell(LayoutInflater inflater, ViewGroup parent, final AppInfo app) {
        final View v = inflater.inflate(R.layout.item_app, parent, false);
        final ImageView icon = (ImageView) v.findViewById(R.id.app_icon);
        final TextView label = (TextView) v.findViewById(R.id.app_label);
        icon.setImageDrawable(mIcons.icon(app));
        label.setText(app.label);
        label.setShadowLayer(0, 0, 0, 0);
        label.setTextColor(0xDBFFFFFF);
        v.setContentDescription(app.label);
        v.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mHost != null) mHost.launch(app, icon);
            }
        });
        v.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (mHost != null) mHost.startDragFromDrawer(app, view);
                return true;
            }
        });
        com.kiroos.ui.KiroTouch.apply(v);
        return v;
    }

    // --- opening and closing -------------------------------------------------------------

    /**
     * 0 closed .. 1 open (a little more while the spring overshoots). The sheet sits under the
     * finger; the dimming follows; the rows of apps rise into place one after the other.
     */
    void setProgress(float p) {
        if (mProgress <= 0f && p > 0f) {
            loadRecents();
        }
        mProgress = p;
        final float clamped = Math.max(0f, Math.min(1f, p));
        final float h = mSheet.getHeight() > 0 ? mSheet.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        mSheet.setTranslationY((1f - p) * h);
        mScrim.setAlpha(0.55f * clamped);
        // the glass shows where the sheet is; without it (no wallpaper bitmap) the sheet is solid
        mGlass.setSheetTop(mSheet.getTop() + mSheet.getTranslationY());
        final int fill = mGlass.ready() ? 0x00000000 : 0xF7101115;
        if (fill != mSheetColor) {
            mSheetColor = fill;
            mSheetBg.setColor(fill);
        }
        setVisibility(p > 0f ? VISIBLE : INVISIBLE);
        final boolean moving = p > 0f && p < 1f;
        final int layer = moving ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        if (mSheet.getLayerType() != layer) mSheet.setLayerType(layer, null);
        if (p >= 1f) {
            settleRows();
            return;
        }
        // row i starts a little after row i-1 and every row is home by the end
        for (int i = 0; i < mList.getChildCount(); i++) {
            final float delay = Math.min(0.35f, i * 0.045f);
            final float local = Math.max(0f, Math.min(1f, (clamped - 0.2f - delay) / 0.45f));
            final View row = mList.getChildAt(i);
            row.setAlpha(local);
            row.setTranslationY((1f - local) * 26 * mDp);
        }
    }

    private void settleRows() {
        for (int i = 0; i < mList.getChildCount(); i++) {
            final View row = mList.getChildAt(i);
            if (row.getAlpha() != 1f) row.setAlpha(1f);
            if (row.getTranslationY() != 0f) row.setTranslationY(0f);
        }
    }

    float progress() {
        return mProgress;
    }

    private int mSheetColor = 0xF7101115;

    private final android.content.BroadcastReceiver mWallpaperChanged =
            new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, android.content.Intent intent) {
            Glass.refresh(getContext());
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().registerReceiver(mWallpaperChanged,
                new android.content.IntentFilter(android.content.Intent.ACTION_WALLPAPER_CHANGED));
        Glass.refresh(getContext());
    }

    @Override
    protected void onDetachedFromWindow() {
        getContext().unregisterReceiver(mWallpaperChanged);
        super.onDetachedFromWindow();
    }

    /** closed: the search cleared, the keyboard away, the list back at the top */
    void reset() {
        hideKeyboard();
        if (mSearch.getText().length() > 0) mSearch.setText("");
        mList.setSelection(0);
    }

    void hideKeyboard() {
        final InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(mSearch.getWindowToken(), 0);
    }

    // --- pulled down from the top --------------------------------------------------------

    private float mDownX;
    private float mDownY;
    private boolean mAtTopOnDown;
    private boolean mPulling;
    private VelocityTracker mVelocity;

    private boolean listAtTop() {
        if (mList.getChildCount() == 0) return true;
        return mList.getFirstVisiblePosition() == 0
                && mList.getChildAt(0).getTop() >= mList.getPaddingTop();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mProgress < 1f) return false;
        track(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                // above the list (the handle, the search) or the list at its top
                mAtTopOnDown = listAtTop() || ev.getY() < mList.getTop() + mSheet.getTop()
                        + mTopInset;
                mPulling = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                final float dy = ev.getY() - mDownY;
                if (mAtTopOnDown && dy > mSlop * 2 && dy > Math.abs(ev.getX() - mDownX) * 1.4f) {
                    mPulling = true;
                    mDownY = ev.getY();
                    hideKeyboard();
                    return true;
                }
                break;
            }
            default:
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mPulling) return super.onTouchEvent(ev);
        track(ev);
        final float h = mSheet.getHeight() > 0 ? mSheet.getHeight() : getHeight();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                if (mHost != null) {
                    mHost.onDrawerDrag(Math.max(0f, Math.min(1f, 1f - (ev.getY() - mDownY) / h)));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float vy = 0f;
                if (mVelocity != null && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    mVelocity.computeCurrentVelocity(1000);
                    vy = mVelocity.getYVelocity();
                }
                final float fling = ViewConfiguration.get(getContext())
                        .getScaledMinimumFlingVelocity() * 4;
                final boolean open = vy < -fling || (vy < fling && mProgress > 0.7f);
                mPulling = false;
                if (mVelocity != null) {
                    mVelocity.recycle();
                    mVelocity = null;
                }
                if (mHost != null) mHost.onDrawerRelease(open, Math.abs(vy));
                return true;
            }
            default:
                return true;
        }
    }

    private void track(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && mVelocity != null) {
            mVelocity.clear();
        }
        if (mVelocity == null) mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(ev);
    }

    // --- the rows ------------------------------------------------------------------------

    private final class RowsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return (mShown.size() + COLUMNS - 1) / COLUMNS;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                row.removeAllViews();
            } else {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 0, 0, dp(14));
            }
            final LayoutInflater inflater = LayoutInflater.from(getContext());
            for (int i = 0; i < COLUMNS; i++) {
                final int index = position * COLUMNS + i;
                final View cell = index < mShown.size()
                        ? bindCell(inflater, row, mShown.get(index)) : new View(getContext());
                row.addView(cell, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            }
            if (mProgress >= 1f) {
                row.setAlpha(1f);
                row.setTranslationY(0f);
            }
            return row;
        }
    }

    /** the first app of each letter: tapping or sliding along the rail jumps to its row */
    private final class LetterRail extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<String> mLetters = new ArrayList<>();
        private final List<Integer> mFirst = new ArrayList<>();
        private int mActive = -1;

        LetterRail(Context c) {
            super(c);
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
        }

        void setLetters(List<AppInfo> apps) {
            mLetters.clear();
            mFirst.clear();
            for (int i = 0; i < apps.size(); i++) {
                final String k = apps.get(i).searchKey;
                String l = k.isEmpty() ? "#" : k.substring(0, 1).toUpperCase(Locale.ROOT);
                if (!Character.isLetter(l.charAt(0))) l = "#";
                if (mLetters.isEmpty() || !mLetters.get(mLetters.size() - 1).equals(l)) {
                    if (!mLetters.contains(l)) {
                        mLetters.add(l);
                        mFirst.add(i);
                    }
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int n = mLetters.size();
            if (n == 0) return;
            final float step = Math.min(getHeight() / (float) n, 18 * mDp);
            mPaint.setTextSize(Math.min(10.5f * mDp, step * 0.8f));
            final float top = (getHeight() - step * n) / 2f;
            for (int i = 0; i < n; i++) {
                mPaint.setColor(i == mActive ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawText(mLetters.get(i), getWidth() / 2f,
                        top + step * i + step * 0.7f, mPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            final int n = mLetters.size();
            if (n == 0) return false;
            final float step = Math.min(getHeight() / (float) n, 18 * mDp);
            final float top = (getHeight() - step * n) / 2f;
            final int i = Math.max(0, Math.min(n - 1, (int) ((e.getY() - top) / step)));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    if (i != mActive) {
                        mActive = i;
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        // header + the row of that app
                        mList.setSelectionFromTop(1 + mFirst.get(i) / COLUMNS, 0);
                        invalidate();
                    }
                    return true;
                default:
                    mActive = -1;
                    invalidate();
                    return true;
            }
        }
    }
}
