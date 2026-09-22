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

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All launchable apps of every profile, sorted by label, kept in sync with package
 * installs, updates and removals through LauncherApps.
 */
final class AppRepository {

    interface Listener {
        void onAppsChanged(List<AppInfo> apps);
    }

    private final Context mContext;
    private final LauncherApps mLauncherApps;
    private final UserManager mUserManager;
    private final IconFactory mIcons;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new ArrayList<>();
    private List<AppInfo> mApps = Collections.emptyList();
    private final Map<String, AppInfo> mByKey = new HashMap<>();
    private boolean mLoading;
    private boolean mReloadPending;

    private final LauncherApps.Callback mCallback = new LauncherApps.Callback() {
        @Override
        public void onPackageRemoved(String packageName, UserHandle user) {
            mIcons.invalidate(packageName);
            reload();
        }

        @Override
        public void onPackageAdded(String packageName, UserHandle user) {
            reload();
        }

        @Override
        public void onPackageChanged(String packageName, UserHandle user) {
            mIcons.invalidate(packageName);
            reload();
        }

        @Override
        public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) {
            reload();
        }

        @Override
        public void onPackagesUnavailable(String[] packageNames, UserHandle user, boolean replacing) {
            reload();
        }
    };

    AppRepository(Context context, IconFactory icons) {
        mContext = context.getApplicationContext();
        mIcons = icons;
        mLauncherApps = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mLauncherApps.registerCallback(mCallback, mMain);
    }

    void destroy() {
        mLauncherApps.unregisterCallback(mCallback);
        mListeners.clear();
    }

    void addListener(Listener l) {
        mListeners.add(l);
        if (!mApps.isEmpty()) {
            l.onAppsChanged(mApps);
        }
    }

    List<AppInfo> apps() {
        return mApps;
    }

    AppInfo find(String key) {
        return mByKey.get(key);
    }

    LauncherApps launcherApps() {
        return mLauncherApps;
    }

    void reload() {
        if (mLoading) {
            mReloadPending = true;
            return;
        }
        mLoading = true;
        new AsyncTask<Void, Void, List<AppInfo>>() {
            @Override
            protected List<AppInfo> doInBackground(Void... params) {
                final List<AppInfo> apps = new ArrayList<>();
                for (UserHandle user : mUserManager.getUserProfiles()) {
                    for (LauncherActivityInfo info : mLauncherApps.getActivityList(null, user)) {
                        if (info.getComponentName().getPackageName().equals(mContext.getPackageName())) {
                            continue;
                        }
                        apps.add(new AppInfo(info));
                    }
                }
                Collections.sort(apps, AppInfo.byLabel());
                return apps;
            }

            @Override
            protected void onPostExecute(List<AppInfo> apps) {
                mApps = Collections.unmodifiableList(apps);
                mByKey.clear();
                for (AppInfo a : apps) {
                    if (!mByKey.containsKey(a.key())) {
                        mByKey.put(a.key(), a);
                    }
                }
                mLoading = false;
                for (Listener l : new ArrayList<>(mListeners)) {
                    l.onAppsChanged(mApps);
                }
                // the home has its icons now; render the drawer's ones before it is opened
                final List<AppInfo> all = mApps;
                AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
                    @Override
                    public void run() {
                        mIcons.prewarm(all);
                    }
                });
                if (mReloadPending) {
                    mReloadPending = false;
                    reload();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
