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

package com.kiroos.capture;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;

/**
 * Joins screenshots of a page taken between scrolls into one long image.
 *
 * Every row of a screenshot gets a hash. Between two pages the rows that did not move at the top
 * (status bar, app bar) and at the bottom (bottom bars) are the fixed parts; in the part between
 * them a block of distinctive rows of the new page is searched in the old one, which gives how
 * far the content moved, and every candidate is checked over the whole overlap. Only the rows the
 * scroll revealed are kept, so the result is the page as one picture: the app bar once at the top,
 * the bottom bar once at the end.
 */
final class Stitcher {

    private static final String TAG = "KiroCapture";
    /** rows of the probe block searched in the previous page */
    private static final int PROBE = 48;
    /** rows of the probe block that must be found identical */
    private static final int PROBE_MATCH = 42;
    /** an overlap is accepted when this share of its rows is identical */
    private static final float MIN_MATCH = 0.85f;
    /** a scroll shorter than this is the end of the page */
    private static final int MIN_SHIFT = 8;

    private final int mWidth;
    private final int mHeight;
    /** the status bar never scrolls; its clock may change while capturing */
    private final int mTopMin;
    private final int mMaxHeight;
    private final int[] mRow;

    private final ArrayList<Bitmap> mStrips = new ArrayList<>();
    private int mTotal;
    private Bitmap mLast;
    private long[] mLastHash;
    /** fixed rows at the bottom of the last page joined */
    private int mFooter;
    private boolean mFull;

    Stitcher(int width, int height, int statusBar, int maxHeight) {
        mWidth = width;
        mHeight = height;
        mTopMin = statusBar;
        mMaxHeight = maxHeight;
        mRow = new int[width];
    }

    int pages() {
        return mStrips.isEmpty() ? (mLast != null ? 1 : 0) : mStrips.size();
    }

    boolean full() {
        return mFull;
    }

    /**
     * Adds the next screenshot.
     * @return true if it continued the page; false if nothing moved (or the move could not be
     *     matched), which ends the capture
     */
    boolean add(Bitmap page) {
        final long[] hash = hash(page);
        if (mLast == null) {
            mLast = page;
            mLastHash = hash;
            return true;
        }
        final long[] a = mLastHash;
        int top = mTopMin;
        while (top < mHeight && a[top] == hash[top]) top++;
        if (top >= mHeight) {
            page.recycle();
            return false;
        }
        int bottom = 0;
        while (bottom < mHeight - top && a[mHeight - 1 - bottom] == hash[mHeight - 1 - bottom]) {
            bottom++;
        }
        final int end = mHeight - bottom;
        final int shift = findShift(a, hash, top, end);
        Log.d(TAG, "page " + (pages() + 1) + ": fixed top " + top + ", bottom " + bottom
                + ", shift " + shift);
        if (shift < MIN_SHIFT) {
            page.recycle();
            return false;
        }
        if (mStrips.isEmpty()) {
            // the first page down to its fixed bottom
            addStrip(crop(mLast, 0, end));
        }
        int rows = shift;
        if (mTotal + rows + bottom > mMaxHeight) {
            rows = Math.max(0, mMaxHeight - mTotal - bottom);
            mFull = true;
        }
        if (rows > 0) addStrip(crop(page, end - shift, rows));
        mLast.recycle();
        mLast = page;
        mLastHash = hash;
        mFooter = bottom;
        return !mFull;
    }

    private void addStrip(Bitmap strip) {
        mStrips.add(strip);
        mTotal += strip.getHeight();
    }

    /** the long screenshot, or null if the page never scrolled */
    Bitmap result() {
        if (mStrips.isEmpty()) return null;
        final int footer = mLast != null ? mFooter : 0;
        final Bitmap out = Bitmap.createBitmap(mWidth, mTotal + footer, Bitmap.Config.ARGB_8888);
        final Canvas c = new Canvas(out);
        int y = 0;
        for (Bitmap s : mStrips) {
            c.drawBitmap(s, 0, y, null);
            y += s.getHeight();
            s.recycle();
        }
        mStrips.clear();
        if (footer > 0) {
            c.drawBitmap(mLast, new Rect(0, mHeight - footer, mWidth, mHeight),
                    new Rect(0, y, mWidth, y + footer), null);
        }
        release();
        return out;
    }

    /**
     * Rows [y, y + rows) of a page as a new bitmap. Not Bitmap.createBitmap(src, ...), which
     * hands back the page itself for its whole area, and the page is recycled afterwards.
     */
    private Bitmap crop(Bitmap src, int y, int rows) {
        final Bitmap b = Bitmap.createBitmap(mWidth, rows, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawBitmap(src, new Rect(0, y, mWidth, y + rows),
                new Rect(0, 0, mWidth, rows), null);
        return b;
    }

    void release() {
        for (Bitmap s : mStrips) s.recycle();
        mStrips.clear();
        if (mLast != null) {
            mLast.recycle();
            mLast = null;
        }
    }

    private long[] hash(Bitmap page) {
        final long[] h = new long[mHeight];
        // the right edge is left out: a list draws its scroll bar there while it moves
        final int end = mWidth - mWidth / 16;
        for (int y = 0; y < mHeight; y++) {
            page.getPixels(mRow, 0, mWidth, 0, y, mWidth, 1);
            long v = 1125899906842597L;
            for (int x = 0; x < end; x += 2) v = 31 * v + mRow[x];
            h[y] = v;
        }
        return h;
    }

    /**
     * How many rows the content moved up between a (before) and b (after) inside [top, end),
     * or 0 if it cannot be told.
     */
    private static int findShift(long[] a, long[] b, int top, int end) {
        final int region = end - top;
        if (region < PROBE * 2) return 0;
        // the most varied block of rows in the upper half of the new page: blank areas would
        // match anywhere
        int probe = top;
        int best = -1;
        for (int q = top; q + PROBE <= top + region / 2; q += 4) {
            final int d = distinct(b, q, PROBE);
            if (d > best) {
                best = d;
                probe = q;
            }
        }
        if (best < 4) return 0;
        int shift = 0;
        float bestMatch = 0;
        for (int p = probe + 1; p + PROBE <= end; p++) {
            // nearly all of the block: a fading ripple or a blinking cursor changes a few rows
            int same = 0;
            for (int i = 0; i < PROBE; i++) {
                if (a[p + i] == b[probe + i]) same++;
            }
            if (same < PROBE_MATCH) continue;
            final int s = p - probe;
            final int overlap = end - s - top;
            if (overlap < PROBE) continue;
            int match = 0;
            for (int y = top; y < end - s; y++) {
                if (b[y] == a[y + s]) match++;
            }
            final float m = match / (float) overlap;
            if (m > bestMatch) {
                bestMatch = m;
                shift = s;
            }
        }
        return bestMatch >= MIN_MATCH ? shift : 0;
    }

    private static int distinct(long[] h, int from, int n) {
        int d = 1;
        for (int i = from + 1; i < from + n; i++) {
            if (h[i] != h[i - 1]) d++;
        }
        return d;
    }
}
