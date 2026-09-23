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

import android.animation.ValueAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.kiroos.ui.KiroTouch;

/**
 * The home pages side by side: every icon is laid out in its cell of its page, and the
 * pages scroll horizontally (the gesture comes from HomeLayout, so it works anywhere on the
 * home). One flat view group, no nested page views: scrolling only moves scrollX.
 */
public class Workspace extends ViewGroup {

    interface Listener {
        /** position in pages, fractional while scrolling (0 = first page) */
        void onPageScrolled(float position);
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

    /** the tallest a cell gets; it shrinks when widgets take room */
    private final int mMaxCellHeight;
    private int mCellHeight;
    /** the rows sit at the bottom, right above the dock */
    private int mGridTop;
    private int mPageCount = 1;
    private int mCurrentPage;
    private float mDragStartScroll;
    private ValueAnimator mSettle;
    private Listener mListener;

    public Workspace(Context context, AttributeSet attrs) {
        super(context, attrs);
        mMaxCellHeight = context.getResources().getDimensionPixelSize(R.dimen.cell_height);
        mCellHeight = mMaxCellHeight;
        setClipChildren(false);
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

    /** top of the first row, in this view's coordinates */
    int gridTop() {
        return mGridTop;
    }

    int pageCount() {
        return mPageCount;
    }

    int currentPage() {
        return mCurrentPage;
    }

    void setPageCount(int count) {
        mPageCount = Math.max(1, count);
        if (mCurrentPage >= mPageCount) {
            snapTo(mPageCount - 1, false);
        }
        notifyScroll();
    }

    /** the cell index under a point in this view's coordinates (current page), or -1 */
    int cellAt(float x, float y) {
        if (getWidth() == 0 || x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
            return -1;
        }
        final int col = Math.max(0, Math.min(HomeModel.COLUMNS - 1,
                (int) ((x - getPaddingLeft()) / cellWidth())));
        final int row = Math.max(0, Math.min(HomeModel.ROWS - 1,
                (int) ((y - mGridTop) / mCellHeight)));
        return row * HomeModel.COLUMNS + col;
    }

    // --- paging -----------------------------------------------------------------------------

    void beginDrag() {
        if (mSettle != null) {
            mSettle.cancel();
        }
        mDragStartScroll = getScrollX();
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
        scrollTo((int) target, 0);
        notifyScroll();
    }

    /** finger up: settle on the page the fling or the position points to */
    void release(float velocityX, float minFling) {
        final int w = Math.max(1, getWidth());
        final float pos = getScrollX() / (float) w;
        int page = Math.round(pos);
        if (velocityX < -minFling) {
            page = (int) Math.floor(pos) + 1;
        } else if (velocityX > minFling) {
            page = (int) Math.ceil(pos) - 1;
        }
        page = Math.max(0, Math.min(mPageCount - 1, page));
        final int target = page * w;
        final float distance = Math.abs(target - getScrollX());
        if (distance < 1) {
            snapTo(page, false);
            return;
        }
        // the pages carry on at the finger's own speed and ease into place: a fixed curve
        // started 3-4x faster than the finger, a visible jump on every fast swipe
        final float toward = Math.signum(target - getScrollX()) == Math.signum(-velocityX)
                ? Math.abs(velocityX) : 0; // px/s in the direction of the page
        final float seconds = toward > minFling
                ? Math.max(0.14f, Math.min(0.38f, 2 * distance / toward))
                : Math.min(0.38f, 0.22f + distance / 4000f);
        animateTo(page, (long) (seconds * 1000),
                new Glide(Math.min(3f, toward * seconds / distance)));
    }

    /**
     * Ease-out that starts at a given speed (slope0, relative to the average speed) and
     * lands with none: a cubic Hermite curve, monotonic for slope0 <= 3.
     */
    private static final class Glide implements android.animation.TimeInterpolator {
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
        if (getScrollX() != mCurrentPage * getWidth()) {
            snapTo(Math.round(getScrollX() / (float) getWidth()), true);
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
            scrollTo(target, 0);
            notifyScroll();
            return;
        }
        animateTo(page, Math.min(420, 180 + Math.abs(target - getScrollX()) / 3),
                KiroTouch.EASE);
    }

    private void animateTo(int page, long duration, android.animation.TimeInterpolator curve) {
        mCurrentPage = page;
        final int target = page * getWidth();
        if (mSettle != null) {
            mSettle.cancel();
        }
        mSettle = ValueAnimator.ofInt(getScrollX(), target);
        mSettle.setDuration(duration);
        mSettle.setInterpolator(curve);
        mSettle.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                scrollTo((int) a.getAnimatedValue(), 0);
                notifyScroll();
            }
        });
        mSettle.start();
    }

    /** tells the listener where the pages are (the wallpaper needs it once the window is up) */
    void notifyScroll() {
        if (mListener != null && getWidth() > 0) {
            mListener.onPageScrolled(getScrollX() / (float) getWidth());
        }
    }

    // --- layout -----------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? mMaxCellHeight * HomeModel.ROWS : MeasureSpec.getSize(heightMeasureSpec);
        mCellHeight = Math.min(mMaxCellHeight, h / HomeModel.ROWS);
        mGridTop = h - mCellHeight * HomeModel.ROWS;
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
        final int inner = w - getPaddingLeft() - getPaddingRight();
        final int cw = inner / HomeModel.COLUMNS;
        final int side = getPaddingLeft() + (inner - cw * HomeModel.COLUMNS) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            final View c = getChildAt(i);
            final LayoutParams lp = (LayoutParams) c.getLayoutParams();
            final int col = lp.index % HomeModel.COLUMNS;
            final int row = lp.index / HomeModel.COLUMNS;
            final int x = lp.page * w + side + col * cw;
            final int y = mGridTop + row * mCellHeight;
            c.layout(x, y, x + cw, y + mCellHeight);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scrollTo(mCurrentPage * w, 0);
        notifyScroll();
    }

    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof LayoutParams;
    }
}
