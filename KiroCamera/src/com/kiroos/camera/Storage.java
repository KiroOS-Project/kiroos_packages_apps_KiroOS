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

package com.kiroos.camera;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Where photos and videos go (DCIM/Camera) and how the Gallery learns about them. */
final class Storage {

    interface Scanned {
        void onScanned(Uri uri);
    }

    private Storage() {
    }

    static File dir() {
        final File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "Camera");
        d.mkdirs();
        return d;
    }

    /** IMG_20260921_204233.jpg (a suffix is added when two shots share the second) */
    static File newFile(String prefix, String ext) {
        final String base = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date());
        File f = new File(dir(), base + ext);
        for (int i = 1; f.exists(); i++) {
            f = new File(dir(), base + "_" + i + ext);
        }
        return f;
    }

    static void scan(Context context, File file, String mime, final Scanned done) {
        MediaScannerConnection.scanFile(context.getApplicationContext(),
                new String[] {file.getAbsolutePath()}, new String[] {mime},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        if (done != null) {
                            done.onScanned(uri);
                        }
                    }
                });
    }
}
