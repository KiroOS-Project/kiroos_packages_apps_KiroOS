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

package com.kiroos.store;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The installs in progress, for the whole app: a screen that is recreated (rotation, theme)
 * finds them where they are, and one app is never downloaded twice at the same time.
 */
final class Installs {

    interface Listener {
        /** on the main thread: the progress of pkg changed (or it ended: result >= 0) */
        void onChanged(String pkg, int result);
    }

    private static final String TAG = "KeurStore";
    private static final Map<String, Integer> sProgress = new ConcurrentHashMap<>();
    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private Installs() {
    }

    /** download percent (0-100), -1 while Android installs it, null when not installing */
    static Integer progress(String pkg) {
        return sProgress.get(pkg);
    }

    static void listen(Listener l) {
        sListeners.add(l);
    }

    static void unlisten(Listener l) {
        sListeners.remove(l);
    }

    private static void changed(final String pkg, final int result) {
        sMain.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : sListeners) {
                    l.onChanged(pkg, result);
                }
            }
        });
    }

    static void start(Context c, final Catalog.App app) {
        if (sProgress.containsKey(app.pkg)) {
            return;
        }
        sProgress.put(app.pkg, 0);
        changed(app.pkg, -1);
        final Context ac = c.getApplicationContext();
        Log.i(TAG, app.pkg + ": install requested (" + app.source + ")");
        new Thread(new Runnable() {
            @Override
            public void run() {
                int result = Installer.FAILED;
                try {
                    result = Installer.install(ac, app, new Installer.Progress() {
                        @Override
                        public void onProgress(int percent) {
                            sProgress.put(app.pkg, percent);
                            changed(app.pkg, -1);
                        }
                    });
                } finally {
                    sProgress.remove(app.pkg);
                    changed(app.pkg, result);
                }
            }
        }, "KeurStore-install-" + app.pkg).start();
    }
}
