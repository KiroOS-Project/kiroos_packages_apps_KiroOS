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

package com.kiroos.applock;

import android.content.Intent;
import android.os.Bundle;

/**
 * "Lock app" / "Unlock app" from the home screen's long-press menu: one app, no list. Only Kiro
 * Home can start it (com.kiroos.applock.permission.LOCK_APPS); unlocking asks for the
 * fingerprint or the screen lock first.
 */
public class LockAppActivity extends AppLockActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // never the list from here
        if (requestedPackage() == null && !isFinishing()) finish();
    }

    @Override
    String requestedPackage() {
        final String action = getIntent().getAction();
        if (!ACTION_LOCK_APP.equals(action) && !ACTION_UNLOCK_APP.equals(action)) return null;
        final String pkg = getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        return pkg == null || pkg.isEmpty() || pkg.indexOf(',') >= 0 ? null : pkg;
    }
}
