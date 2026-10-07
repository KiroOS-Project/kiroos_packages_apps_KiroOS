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

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Started by SystemUI from the screenshot notification's "Scroll" action. Invisible: it only asks
 * for storage the first time (the long screenshot is saved with the others), then hands over to
 * ScrollCaptureService and finishes, so the app below is what gets captured.
 */
public class ScrollCaptureActivity extends Activity {

    private static final int REQUEST_STORAGE = 1;

    private Uri mOriginal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mOriginal = getIntent().getParcelableExtra(ScrollCaptureService.EXTRA_ORIGINAL);
        final ArrayList<String> need = new ArrayList<>();
        // both: Android 7 gives a process the writable storage view only with READ and WRITE
        for (String p : new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE }) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (need.isEmpty()) {
            start();
        } else {
            requestPermissions(need.toArray(new String[need.size()]), REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        for (int r : results) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.need_storage, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        start();
    }

    private void start() {
        startService(new Intent(this, ScrollCaptureService.class)
                .putExtra(ScrollCaptureService.EXTRA_ORIGINAL, mOriginal));
        finish();
        overridePendingTransition(0, 0);
    }
}
