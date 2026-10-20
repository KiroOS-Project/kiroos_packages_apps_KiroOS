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

package com.kiroos.setupwizard;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

/**
 * Recommended apps (Developer Beta 5): Google Maps, which KiroOS cannot ship (it replaced
 * Atlas), installed from the Play Store in one tap; the home's Maps icon is already in place.
 * Shown only with the Play Store on the phone and Maps not installed yet. The other apps are
 * in KeurStore, which the page names.
 */
class AppsPage extends SetupPage {

    private static final String MAPS = "com.google.android.apps.maps";
    private static final String PLAY = "com.android.vending";

    private View mRow;
    private Button mInstall;

    AppsPage(SetupActivity activity) {
        super(activity);
    }

    static boolean available(Context c) {
        return installed(c, PLAY) && !installed(c, MAPS);
    }

    private static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    int iconRes() {
        return R.drawable.ic_store;
    }

    @Override
    int titleRes() {
        return R.string.apps_title;
    }

    @Override
    int summaryRes() {
        return R.string.apps_summary;
    }

    @Override
    void onCreateContent(LayoutInflater inflater, ViewGroup content) {
        mRow = addAction(content, R.string.apps_maps, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMaps();
            }
        });
        setActionValue(mRow, mActivity.getText(R.string.apps_maps_summary));
        mInstall = addButton(content, R.string.apps_install_maps, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMaps();
            }
        });
        addStatus(content).setText(R.string.apps_store_note);
    }

    /** the Play Store's page for Maps (Back returns here) */
    private void openMaps() {
        final Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + MAPS))
                .setPackage(PLAY);
        try {
            mActivity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            mInstall.setEnabled(false);
        }
    }

    @Override
    void onShown() {
        refresh();
    }

    /** after the Play Store: installed, or still to install */
    void refresh() {
        if (mInstall == null) return;
        final boolean done = installed(mActivity, MAPS);
        mInstall.setText(done ? R.string.apps_maps_installed : R.string.apps_install_maps);
        mInstall.setEnabled(!done);
        setActionValue(mRow, mActivity.getText(done ? R.string.apps_maps_installed
                : R.string.apps_maps_summary));
    }
}
