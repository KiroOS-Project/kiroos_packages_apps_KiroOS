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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Grid thumbnails off the main thread (MediaStore MINI_KIND, rotated upright), cached in a
 * 1/6-of-heap LRU. Work for rows that scrolled away is cancelled, so fast scrolling stays
 * at 60 fps.
 */
final class Thumbnails {

    private static Thumbnails sInstance;

    private final ContentResolver mResolver;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> mCache;
    private final ExecutorService mExecutor = Executors.newFixedThreadPool(3, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            final Thread t = new Thread(r, "KiroThumbs");
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        }
    });

    static synchronized Thumbnails get(Context context) {
        if (sInstance == null) {
            sInstance = new Thumbnails(context.getApplicationContext());
        }
        return sInstance;
    }

    private Thumbnails(Context context) {
        mResolver = context.getContentResolver();
        final int max = (int) (Runtime.getRuntime().maxMemory() / 6);
        mCache = new LruCache<Long, Bitmap>(max) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    private static long key(Media.Item item) {
        return item.video ? -item.id : item.id;
    }

    void load(final Media.Item item, final ImageView into) {
        final Future<?> old = (Future<?>) into.getTag(R.id.thumb_task);
        if (old != null) {
            old.cancel(true);
        }
        into.setTag(R.id.thumb_item, key(item));
        final Bitmap cached = mCache.get(key(item));
        if (cached != null) {
            into.setImageBitmap(cached);
            into.setTag(R.id.thumb_task, null);
            return;
        }
        into.setImageDrawable(null);
        final Future<?> task = mExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                final Bitmap b = decode(item);
                if (b == null) {
                    return;
                }
                mCache.put(key(item), b);
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        final Object tag = into.getTag(R.id.thumb_item);
                        if (tag instanceof Long && (Long) tag == key(item)) {
                            into.setImageBitmap(b);
                            into.setAlpha(0f);
                            into.animate().alpha(1f).setDuration(120).start();
                        }
                    }
                });
            }
        });
        into.setTag(R.id.thumb_task, task);
    }

    private Bitmap decode(Media.Item item) {
        try {
            Bitmap b = item.video
                    ? MediaStore.Video.Thumbnails.getThumbnail(mResolver, item.id,
                    MediaStore.Video.Thumbnails.MINI_KIND, null)
                    : MediaStore.Images.Thumbnails.getThumbnail(mResolver, item.id,
                    MediaStore.Images.Thumbnails.MINI_KIND, null);
            if (b != null && !item.video && item.orientation != 0) {
                final Matrix m = new Matrix();
                m.postRotate(item.orientation);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    void trim() {
        mCache.trimToSize(mCache.maxSize() / 2);
    }
}
