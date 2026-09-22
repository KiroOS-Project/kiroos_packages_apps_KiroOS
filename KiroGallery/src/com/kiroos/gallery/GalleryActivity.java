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

package com.kiroos.gallery;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Photos (everything, newest first) and Albums. Also the picker other apps open with
 * GET_CONTENT / PICK for an image or a video.
 */
public class GalleryActivity extends Activity {

    private final Handler mHandler = new Handler();
    private GridView mGrid;
    private TextView mTitle;
    private TextView mEmpty;
    private View mTabs;
    private TextView mTabPhotos;
    private TextView mTabAlbums;
    private final GridAdapter mGridAdapter = new GridAdapter();
    private final AlbumAdapter mAlbumAdapter = new AlbumAdapter();

    private boolean mPicker;
    private int mFilter = Media.FILTER_ALL;
    private boolean mShowAlbums;
    /** album opened from the Albums tab, 0 = all */
    private long mBucket;
    private String mBucketName;
    private List<Media.Item> mAll = Collections.emptyList();
    private boolean mLoading;
    private boolean mDirty;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);
        mGrid = (GridView) findViewById(R.id.grid);
        mTitle = (TextView) findViewById(R.id.title);
        mEmpty = (TextView) findViewById(R.id.empty);
        mTabs = findViewById(R.id.tabs);
        mTabPhotos = (TextView) findViewById(R.id.tab_photos);
        mTabAlbums = (TextView) findViewById(R.id.tab_albums);

        final String action = getIntent().getAction();
        mPicker = Intent.ACTION_GET_CONTENT.equals(action) || Intent.ACTION_PICK.equals(action);
        final String type = getIntent().getType();
        if (type != null && type.startsWith("image/")) {
            mFilter = Media.FILTER_IMAGES;
        } else if (type != null && type.startsWith("video/")) {
            mFilter = Media.FILTER_VIDEOS;
        }
        mTitle.setText(mPicker ? R.string.choose : R.string.app_name);

        mGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (mShowAlbums && mBucket == 0) {
                    final Media.Album a = mAlbumAdapter.getItem(position);
                    openAlbum(a.id, a.name);
                    return;
                }
                final Media.Item item = mGridAdapter.getItem(position);
                if (mPicker) {
                    setResult(RESULT_OK, new Intent().setData(item.uri())
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                    finish();
                    return;
                }
                startActivity(new Intent(GalleryActivity.this, ViewerActivity.class)
                        .putExtra(ViewerActivity.EXTRA_BUCKET, mBucket)
                        .putExtra(ViewerActivity.EXTRA_FILTER, mFilter)
                        .putExtra(ViewerActivity.EXTRA_POSITION, position));
            }
        });
        mGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int state) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                // subtle header while scrolling: the date of the first visible row
                if (!mShowAlbums && total > 0 && first < mGridAdapter.getCount() && first > 0) {
                    mTitle.setText(DateUtils.formatDateTime(GalleryActivity.this,
                            mGridAdapter.getItem(first).date, DateUtils.FORMAT_SHOW_DATE
                                    | DateUtils.FORMAT_NO_MONTH_DAY | DateUtils.FORMAT_SHOW_YEAR));
                } else if (!mShowAlbums && first == 0) {
                    mTitle.setText(mBucket != 0 ? mBucketName
                            : getString(mPicker ? R.string.choose : R.string.app_name));
                }
            }
        });
        mTabPhotos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAlbums(false);
            }
        });
        mTabAlbums.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAlbums(true);
            }
        });
        findViewById(R.id.btn_camera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                } catch (android.content.ActivityNotFoundException e) {
                    // no camera app (disabled)
                }
            }
        });
        findViewById(R.id.btn_camera).setVisibility(mPicker ? View.GONE : View.VISIBLE);
        showAlbums(false);
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        getContentResolver().registerContentObserver(MediaStore.Files.getContentUri("external"),
                true, mObserver);
        load();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    @Override
    public void onBackPressed() {
        if (mBucket != 0) {
            mBucket = 0;
            mBucketName = null;
            showAlbums(true);
            return;
        }
        super.onBackPressed();
    }

    private void openAlbum(long id, String name) {
        mBucket = id;
        mBucketName = name;
        mShowAlbums = false;
        mTitle.setText(name);
        mTabs.setVisibility(View.GONE);
        applyGrid();
    }

    private void showAlbums(boolean albums) {
        mShowAlbums = albums;
        mBucket = 0;
        mTabs.setVisibility(View.VISIBLE);
        mTabPhotos.setSelected(!albums);
        mTabAlbums.setSelected(albums);
        mTitle.setText(mPicker ? R.string.choose : R.string.app_name);
        applyGrid();
    }

    private void applyGrid() {
        final float d = getResources().getDisplayMetrics().density;
        if (mShowAlbums) {
            mGrid.setNumColumns(2);
            mGrid.setHorizontalSpacing(Math.round(12 * d));
            mGrid.setVerticalSpacing(Math.round(16 * d));
            mGrid.setPadding(Math.round(16 * d), 0, Math.round(16 * d), Math.round(16 * d));
            mGrid.setAdapter(mAlbumAdapter);
            mAlbumAdapter.set(Media.albums(mAll));
            mEmpty.setVisibility(mAlbumAdapter.getCount() == 0 ? View.VISIBLE : View.GONE);
        } else {
            mGrid.setNumColumns(getResources().getInteger(R.integer.grid_columns));
            mGrid.setHorizontalSpacing(Math.round(2 * d));
            mGrid.setVerticalSpacing(Math.round(2 * d));
            mGrid.setPadding(0, 0, 0, 0);
            mGrid.setAdapter(mGridAdapter);
            final List<Media.Item> shown;
            if (mBucket == 0) {
                shown = mAll;
            } else {
                shown = new ArrayList<>();
                for (Media.Item i : mAll) {
                    if (i.bucketId == mBucket) {
                        shown.add(i);
                    }
                }
            }
            mGridAdapter.set(shown);
            mEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private void load() {
        if (mLoading) {
            mDirty = true;
            return;
        }
        mLoading = true;
        final int filter = mFilter;
        new AsyncTask<Void, Void, List<Media.Item>>() {
            @Override
            protected List<Media.Item> doInBackground(Void... params) {
                return Media.query(GalleryActivity.this, 0, filter);
            }

            @Override
            protected void onPostExecute(List<Media.Item> items) {
                mLoading = false;
                mAll = items;
                applyGrid();
                if (mDirty) {
                    mDirty = false;
                    load();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Thumbnails.get(this).trim();
    }

    // --- adapters -------------------------------------------------------------------------------

    private final class GridAdapter extends BaseAdapter {
        private List<Media.Item> mItems = Collections.emptyList();

        void set(List<Media.Item> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Media.Item getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            final Media.Item i = mItems.get(position);
            return i.video ? -i.id : i.id;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    GalleryActivity.this).inflate(R.layout.item_thumb, parent, false);
            final Media.Item i = mItems.get(position);
            Thumbnails.get(GalleryActivity.this).load(i, (ImageView) v.findViewById(R.id.thumb));
            final TextView badge = (TextView) v.findViewById(R.id.badge);
            if (i.video) {
                badge.setText(DateUtils.formatElapsedTime(i.duration / 1000));
                badge.setVisibility(View.VISIBLE);
            } else {
                badge.setVisibility(View.GONE);
            }
            v.setContentDescription(i.name);
            return v;
        }
    }

    private final class AlbumAdapter extends BaseAdapter {
        private List<Media.Album> mAlbums = Collections.emptyList();

        void set(List<Media.Album> albums) {
            mAlbums = albums;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mAlbums.size();
        }

        @Override
        public Media.Album getItem(int position) {
            return mAlbums.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mAlbums.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    GalleryActivity.this).inflate(R.layout.item_album, parent, false);
            final Media.Album a = mAlbums.get(position);
            final ImageView cover = (ImageView) v.findViewById(R.id.cover);
            cover.setClipToOutline(true);
            Thumbnails.get(GalleryActivity.this).load(a.cover, cover);
            ((TextView) v.findViewById(R.id.name)).setText(a.name);
            ((TextView) v.findViewById(R.id.count)).setText(String.valueOf(a.count));
            return v;
        }
    }
}
