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

package com.kiroos.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;

/**
 * Contact avatars in the lists: the thumbnail photo when there is one (loaded off the main
 * thread, cached), otherwise the initial on a dark circle.
 */
final class Avatars {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Avatars() {
    }

    static void bind(Context context, final TextView letter, final ImageView photo,
            String name, final String photoUri) {
        letter.setText(initial(name));
        photo.setClipToOutline(true);
        photo.setTag(photoUri);
        if (TextUtils.isEmpty(photoUri)) {
            photo.setImageDrawable(null);
            photo.setVisibility(View.GONE);
            return;
        }
        final Bitmap cached = CACHE.get(photoUri);
        if (cached != null) {
            photo.setImageBitmap(cached);
            photo.setVisibility(View.VISIBLE);
            return;
        }
        photo.setImageDrawable(null);
        photo.setVisibility(View.GONE);
        final Context app = context.getApplicationContext();
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... params) {
                try (InputStream in = app.getContentResolver().openInputStream(Uri.parse(photoUri))) {
                    return in != null ? BitmapFactory.decodeStream(in) : null;
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                if (b == null) {
                    return;
                }
                CACHE.put(photoUri, b);
                if (photoUri.equals(photo.getTag())) {
                    photo.setImageBitmap(b);
                    photo.setVisibility(View.VISIBLE);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    static String initial(String name) {
        if (!TextUtils.isEmpty(name)) {
            final int cp = name.codePointAt(0);
            if (Character.isLetter(cp)) {
                return new String(Character.toChars(Character.toUpperCase(cp)));
            }
        }
        return "#";
    }
}
