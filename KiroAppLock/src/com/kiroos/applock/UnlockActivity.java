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

import android.app.Activity;
import android.app.ActivityManagerNative;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Shown by the activity manager instead of a locked app. After the fingerprint (or the screen
 * lock) it starts the app again with the one-time token, which unlocks it until the screen
 * turns off. Back goes home.
 */
public class UnlockActivity extends Activity implements Auth.Callback {

    private static final String TAG = "KiroAppLock";

    private Auth mAuth;
    private boolean mDone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unlock);
        mAuth = new Auth(this, findViewById(R.id.root), this);
        fill();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // another locked app, or the same one again with a new token
        setIntent(intent);
        mDone = false;
        fill();
    }

    private void fill() {
        final String pkg = getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        final PackageManager pm = getPackageManager();
        CharSequence label = pkg;
        try {
            final ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = ai.loadLabel(pm);
            ((ImageView) findViewById(R.id.icon)).setImageDrawable(ai.loadIcon(pm));
        } catch (PackageManager.NameNotFoundException | NullPointerException e) {
            Log.w(TAG, "app " + pkg, e);
        }
        ((TextView) findViewById(R.id.title)).setText(getString(R.string.app_locked, label));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mDone) mAuth.start();
    }

    @Override
    protected void onPause() {
        mAuth.stop();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mAuth.onActivityResult(requestCode, resultCode);
    }

    @Override
    public void onAuthenticated() {
        if (mDone) return;
        mDone = true;
        final Intent i = getIntent();
        final long token = i.getLongExtra(Locks.TOKEN, 0);
        final int taskId = i.getIntExtra(Intent.EXTRA_TASK_ID, -1);
        final IntentSender sender = i.getParcelableExtra(Intent.EXTRA_INTENT);
        boolean started = false;
        if (taskId != -1) {
            // from Recents: the task as it was
            final Bundle options = new Bundle();
            options.putLong(Locks.TOKEN, token);
            try {
                ActivityManagerNative.getDefault().startActivityFromRecents(taskId, options);
                started = true;
            } catch (RemoteException | IllegalArgumentException e) {
                Log.w(TAG, "task " + taskId, e);
            }
        }
        if (!started && sender != null) {
            try {
                startIntentSender(sender, new Intent().putExtra(Locks.TOKEN, token), 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Log.w(TAG, "start", e);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
