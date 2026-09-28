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

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.kiroos.ui.KiroTouch;

/**
 * The home pages side by side, from the top of the screen to the page dots: every icon is
 * laid out in its cell of its page, and the pages scroll horizontally (the gesture comes
 * from HomeLayout, so it works anywhere on the home). One flat view group, no nested page
 * views: scrolling only moves scrollX.
 *
 * Rows are anchored at the bottom (row 0 right above the dots, see HomeModel) and every
 * page shows as many rows as fit; the first page stops below its header (the clock and the
 * widgets, which HomeActivity lays over it).
 *
 * Paging follows the finger 1:1 and ends in a glide that starts at the finger's own speed;
 * a touch during the glide catches the pages where they are.
 */
public class Workspace extends ViewGroup {

    interface Listener {
        /** position in pages, fractional while scrolling (0 = first page) */
        void onPageScrolled(float position);

        /** the rows that fit changed (first page, other pages) */
        void onRowsChanged(int firstRows, int rows);
    }

    /** a child's cell */
    static final class LayoutParams extends ViewGroup.LayoutParams {
        int page;
        int index;

        LayoutParams(int page, int index) {
            super(0, 0);
            this.page = page;
            this.index = index;
        }
    }

    private final int mCellHeight;
    /** where the first page's grid may start (below the clock and the widgets) */
    private int mFirstTop;
    private int mFirstRows = 4;
    private int mRows = 5;
    private int mPageCount = 1;
    private int mCurrentPage;
    private float mDragStartScroll;
    private ValueAnimator mSettle;
    /**
     * Where the pages are, to the fraction of a pixel. The view scrolls by whole pixels only:
     * the rest is a translation, so a page gliding to a stop moves smoothly instead of in
     * steps of 2 real pixels (the UI runs at 720p on the 1440p panel).
     */
    private float mScroll;
    private Listener mListener;

    public Workspace(Context context, AttributeSet attrs) {
        super(context, attrs);
        mCellHeight = context.getResources().getDimensionPixelSize(R.dimen.cell_height);
        setClipChildren(false);
        // the padding only places the cells: a sliding page must stay whole up to the
        // screen edge, not be cut off a margin inside it
        setClipToPadding(false);
    }

    void setListener(Listener l) {
        mListener = l;
    }

    /** the view spans the full screen width (pages slide edge to edge); cells keep a margin */
    int cellWidth() {
        return (getWidth() - getPaddingLeft() - getPaddingRight()) / HomeModel.COLUMNS;
    }

    int cellHeight() {
        return mCellHeight;
    }

    int pageCount() {
        return mPageCount;
    }

    int currentPage() {
        return mCurrentPage;
    }

    int rows(int page) {
        return page == 0 ? mFirstRows : mRows;
    }

    /** the first page's grid stays below this line (this view's coordinates) */
    void setFirstPageTop(int top) {
        if (top != mFirstTop) {
            mFirstTop = top;
            updateRows(getHeight());
            requestLayout();
        }
    }

    void setPageCount(int count) {
        mPageCount = Math.max(1, count);
        if (mCurrentPage >= mPageCount) {
            snapTo(mPageCount - 1, false);
        }
        notifyScroll();
    }

    /** top of a cell of the given page, in this view's coordinates */
    int cellTop(int page, int index) {
        return getHeight() - (index / HomeModel.COLUMNS + 1) * mCellHeight;
    }

    /** left of a cell, in this view's coordinates (without the scroll) */
    int cellLeft(int index) {
        final int inner = getWidth() - getPaddingLeft() - getPaddingRight();
        final int cw = inner / HomeModel.COLUMNS;
        return getPaddingLeft() + (inner - cw * HomeModel.COLUMNS) / 2
                + (index % HomeModel.COLUMNS) * cw;
    }

    /**
     * The cell of the current page under a point in this view's coordinates; above the
     * page's top row it is the top row (dropping on the clock area lands right below it).
     * -1 outside the view.
     */
    int cellAt(float x, float y) {
        if (getWidth() == 0 || x < 0 || x >= getWidth() || y >= getHeight()) {
            return -1;
        }
        final int col = Math.max(0, Math.min(HomeModel.COLUMNS - 1,
                (int) ((x - getPaddingLeft()) / cellWidth())));
        final int row = Math.max(0, Math.min(rows(mCurrentPage) - 1,
                (int) ((getHeight() - Math.max(0, y)) / mCellHeight)));
        return row * HomeModel.COLUMNS + col;
    }

    // --- paging -----------------------------------------------------------------------------

    /**
     * A finger touches down: a glide in progress stops right there, under the finger.
     * True if the pages were moving (the touch then drives them at once, no slop).
     */
    /** the pages' position, to the fraction of a pixel */
    float scroll() {
        return mScroll;
    }

    private void setScroll(float x) {
        mScroll = x;
        final int whole = (int) Math.floor(x);
        scrollTo(whole, 0);
        setTranslationX(whole - x);
    }

    boolean catchScroll() {
        final boolean moving = mSettle != null && mSettle.isRunning();
        if (moving) {
            mSettle.cancel();
            mCurrentPage = Math.round(mScroll / Math.max(1, getWidth()));
        }
        return moving || mScroll != mCurrentPage * getWidth();
    }

    void beginDrag() {
        if (mSettle != null) {
            mSettle.cancel();
        }
        mDragStartScroll = mScroll;
    }

    /** dx: finger travel since beginDrag (right = positive) */
    void drag(float dx) {
        float target = mDragStartScroll - dx;
        final float max = (mPageCount - 1) * getWidth();
        // rubber band past the first and the last page
        if (target < 0) {
            target = target / 3f;
        } else if (target > max) {
            target = max + (target - max) / 3f;
        }
        setScroll(target);
        notifyScroll();
    }

    /** finger up: settle on the page the fling or the position points to */
    void release(float velocityX, float minFling) {
        final int w = Math.max(1, getWidth());
        final float pos = mScroll / w;
        int page = Math.round(pos);
        if (velocityX < -minFling) {
            page = (int) Math.floor(pos) + 1;
        } else if (velocityX > minFling) {
            page = (int) Math.ceil(pos) - 1;
        }
        page = Math.max(0, Math.min(mPageCount - 1, page));
        final float distance = Math.abs(page * w - mScroll);
        if (distance < 1) {
            snapTo(page, false);
            return;
        }
        // the pages carry on at the finger's own speed and ease into place
        final float toward = Math.signum(page * w - mScroll) == Math.signum(-velocityX)
                ? Math.abs(velocityX) : 0; // px/s in the direction of the page
        final float seconds = toward > minFling
                ? Math.max(0.16f, Math.min(0.36f, 2 * distance / toward))
                : Math.min(0.36f, 0.22f + distance / 4000f);
        animateTo(page, (long) (seconds * 1000),
                new Glide(Math.min(2.5f, toward * seconds / distance)));
    }

    /**
     * Ease-out that starts at a given speed (slope0, relative to the average speed) and
     * lands with none: a cubic Hermite curve, monotonic for slope0 <= 3.
     */
    private static final class Glide implements TimeInterpolator {
        private final float mSlope0;

        Glide(float slope0) {
            mSlope0 = slope0;
        }

        @Override
        public float getInterpolation(float t) {
            final float t2 = t * t;
            final float t3 = t2 * t;
            return mSlope0 * (t3 - 2 * t2 + t) + (-2 * t3 + 3 * t2);
        }
    }

    /** not resting on a page and not on its way to one: go to the nearest page */
    void settle() {
        if ((mSettle != null && mSettle.isRunning()) || getWidth() == 0) {
            return;
        }
        if (mScroll != mCurrentPage * getWidth()) {
            snapTo(Math.round(mScroll / getWidth()), true);
        }
    }

    void snapTo(int page, boolean animate) {
        page = Math.max(0, Math.min(mPageCount - 1, page));
        mCurrentPage = page;
        final int target = page * getWidth();
        if (mSettle != null) {
            mSettle.cancel();
        }
        if (!animate || getWidth() == 0) {
            setScroll(target);
            notifyScroll();
            return;
        }
        animateTo(page, Math.min(400, 200 + (long) Math.abs(target - mScroll) / 4),
                KiroTouch.EASE);
    }

    private void animateTo(int page, long duration, TimeInterpolator curve) {
        mCurrentPage = page;
        final int target = page * getWidth();
        if (mSettle != null) {
            mSettle.cancel();
        }
        mSettle = ValueAnimator.ofFloat(mScroll, target);
        mSettle.setDuration(duration);
        mSettle.setInterpolator(curve);
        mSettle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                setScroll((float) a.getAnimatedValue());
                notifyScroll();
            }
        });
        mSettle.start();
    }

    /** tells the listener where the pages are (the wallpaper needs it once the window is up) */
    void notifyScroll() {
        if (mListener != null && getWidth() > 0) {
            mListener.onPageScrolled(mScroll / getWidth());
        }
    }

    // --- layout -----------------------------------------------------------------------------

    private void updateRows(int h) {
        if (h <= 0) {
            return;
        }
        final int rows = Math.max(1, Math.min(HomeModel.MAX_ROWS, h / mCellHeight));
        final int first = Math.max(1, Math.min(rows, (h - mFirstTop) / mCellHeight));
        if (rows != mRows || first != mFirstRows) {
            mRows = rows;
            mFirstRows = first;
            if (mListener != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onRowsChanged(mFirstRows, mRows);
                    }
                });
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? mCellHeight * HomeModel.MAX_ROWS : MeasureSpec.getSize(heightMeasureSpec);
        final int cw = (w - getPaddingLeft() - getPaddingRight()) / HomeModel.COLUMNS;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(mCellHeight, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = r - l;
        final int h = b - t;
        updateRows(h);
        final int cw = (w - getPaddingLeft() - getPaddingRight()) / HomeModel.COLUMNS;
        for (int i = 0; i < getChildCount(); i++) {
            final View c = getChildAt(i);
            final LayoutParams lp = (LayoutParams) c.getLayoutParams();
            final int x = lp.page * w + cellLeft(lp.index);
            final int y = h - (lp.index / HomeModel.COLUMNS + 1) * mCellHeight;
            c.layout(x, y, x + cw, y + mCellHeight);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setScroll(mCurrentPage * w);
        notifyScroll();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }
}
