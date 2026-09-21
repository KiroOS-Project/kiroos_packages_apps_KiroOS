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

package com.kiroos.files;

import android.app.ProgressDialog;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.AsyncTask;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Copy, move and delete in the background with a progress dialog. Falls back to root
 * (when enabled) for places the app can't write.
 */
final class FileOps {

    interface Done {
        void onDone();
    }

    static final int COPY = 0;
    static final int MOVE = 1;
    static final int DELETE = 2;

    private FileOps() {
    }

    static void run(final Context context, final int op, final List<File> sources,
            final File target, final boolean root, final Done done) {
        final ProgressDialog dialog = new ProgressDialog(context);
        dialog.setMessage(context.getString(op == DELETE ? R.string.deleting
                : op == MOVE ? R.string.moving : R.string.copying));
        dialog.setCancelable(false);
        dialog.setIndeterminate(true);
        dialog.show();
        final List<String> scan = new ArrayList<>();
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... params) {
                int failed = 0;
                for (File src : sources) {
                    boolean ok;
                    try {
                        switch (op) {
                            case DELETE:
                                ok = deleteTree(src);
                                if (!ok && root) {
                                    ok = Root.delete(src.getAbsolutePath());
                                }
                                scan.add(src.getAbsolutePath());
                                break;
                            case MOVE: {
                                final File dest = unique(new File(target, src.getName()));
                                ok = src.renameTo(dest);
                                if (!ok) {
                                    // another volume: copy then delete
                                    ok = copyTree(src, dest) && deleteTree(src);
                                }
                                if (!ok && root) {
                                    ok = Root.move(src.getAbsolutePath(), dest.getAbsolutePath());
                                }
                                scan.add(src.getAbsolutePath());
                                scan.add(dest.getAbsolutePath());
                                break;
                            }
                            default: {
                                final File dest = unique(new File(target, src.getName()));
                                if (dest.getAbsolutePath().startsWith(src.getAbsolutePath() + "/")) {
                                    ok = false; // a folder into itself
                                    break;
                                }
                                ok = copyTree(src, dest);
                                if (!ok && root) {
                                    ok = Root.copy(src.getAbsolutePath(), dest.getAbsolutePath());
                                }
                                scan.add(dest.getAbsolutePath());
                                break;
                            }
                        }
                    } catch (IOException | SecurityException e) {
                        ok = false;
                    }
                    if (!ok) {
                        failed++;
                    }
                }
                return failed;
            }

            @Override
            protected void onPostExecute(Integer failed) {
                dialog.dismiss();
                if (failed > 0) {
                    Toast.makeText(context, context.getString(R.string.op_failed, failed),
                            Toast.LENGTH_LONG).show();
                }
                // keep Gallery / Music in sync
                MediaScannerConnection.scanFile(context, scan.toArray(new String[0]), null, null);
                done.onDone();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** "photo.jpg" → "photo (1).jpg" when the name is taken */
    static File unique(File f) {
        if (!f.exists()) {
            return f;
        }
        final String name = f.getName();
        final int dot = f.isDirectory() ? -1 : name.lastIndexOf('.');
        final String base = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            final File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) {
                return c;
            }
        }
    }

    static boolean copyTree(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.mkdirs() && !dest.isDirectory()) {
                return false;
            }
            final File[] children = src.listFiles();
            boolean ok = true;
            if (children != null) {
                for (File c : children) {
                    ok &= copyTree(c, new File(dest, c.getName()));
                }
            }
            return ok;
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        dest.setLastModified(src.lastModified());
        return true;
    }

    static boolean deleteTree(File f) {
        if (f.isDirectory()) {
            final File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteTree(c);
                }
            }
        }
        return f.delete() || !f.exists();
    }

    /** total size of a file or folder (for the details dialog) */
    static long sizeOf(File f) {
        if (!f.isDirectory()) {
            return f.length();
        }
        long total = 0;
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                total += sizeOf(c);
            }
        }
        return total;
    }
}
