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
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App lock settings: after the fingerprint (or the screen lock), the apps with a switch each.
 * The phone app and the home screen are left out, so calls and home always open.
 */
public class AppLockActivity extends Activity implements Auth.Callback {

    private Auth mAuth;
    private View mAuthPanel;
    private View mListPanel;
    private View mNoLockPanel;
    private ListView mList;
    private boolean mUnlocked;
    private final ArrayList<App> mApps = new ArrayList<>();
    private Set<String> mLocked = new HashSet<>();

    private static final class App {
        String pkg;
        CharSequence label;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_applock);
        mAuthPanel = findViewById(R.id.root);
        mListPanel = findViewById(R.id.list_panel);
        mNoLockPanel = findViewById(R.id.nolock_panel);
        mList = (ListView) findViewById(R.id.list);
        mAuth = new Auth(this, mAuthPanel, this);
        findViewById(R.id.set_lock).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD));
            }
        });
        findViewById(R.id.vault).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AppLockActivity.this, VaultActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mAuth.secure()) {
            show(mNoLockPanel);
            return;
        }
        if (mUnlocked) {
            show(mListPanel);
        } else {
            show(mAuthPanel);
            mAuth.start();
        }
    }

    @Override
    protected void onPause() {
        mAuth.stop();
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // leaving the settings locks them again
        if (!isChangingConfigurations()) mUnlocked = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mAuth.onActivityResult(requestCode, resultCode);
    }

    @Override
    public void onAuthenticated() {
        if (!mAuth.secure()) return;
        // the vault opens without asking again for a minute
        Vault.authenticated();
        mUnlocked = true;
        show(mListPanel);
        load();
    }

    private void show(View panel) {
        mAuthPanel.setVisibility(panel == mAuthPanel ? View.VISIBLE : View.GONE);
        mListPanel.setVisibility(panel == mListPanel ? View.VISIBLE : View.GONE);
        mNoLockPanel.setVisibility(panel == mNoLockPanel ? View.VISIBLE : View.GONE);
    }

    private void load() {
        new AsyncTask<Void, Void, List<App>>() {
            @Override
            protected List<App> doInBackground(Void... v) {
                return apps(AppLockActivity.this);
            }

            @Override
            protected void onPostExecute(List<App> apps) {
                if (isDestroyed()) return;
                mLocked = Locks.get(AppLockActivity.this);
                mApps.clear();
                mApps.addAll(apps);
                mList.setAdapter(new Adapter());
            }
        }.execute();
    }

    /** the apps with a launcher icon, minus the ones that must always open */
    private static List<App> apps(Context context) {
        final PackageManager pm = context.getPackageManager();
        final Set<String> skip = new HashSet<>();
        skip.add(context.getPackageName());
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), 0)) {
            skip.add(r.activityInfo.packageName);
        }
        final TelecomManager tm = context.getSystemService(TelecomManager.class);
        if (tm != null && tm.getDefaultDialerPackage() != null) {
            skip.add(tm.getDefaultDialerPackage());
        }
        skip.add("com.android.server.telecom");
        skip.add("com.android.emergency");
        final List<App> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            final String pkg = r.activityInfo.packageName;
            if (skip.contains(pkg) || !seen.add(pkg)) continue;
            final App a = new App();
            a.pkg = pkg;
            a.label = r.activityInfo.applicationInfo.loadLabel(pm);
            a.icon = r.activityInfo.applicationInfo.loadIcon(pm);
            out.add(a);
        }
        final Collator c = Collator.getInstance();
        Collections.sort(out, new Comparator<App>() {
            @Override
            public int compare(App x, App y) {
                return c.compare(x.label.toString(), y.label.toString());
            }
        });
        return out;
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mApps.size();
        }

        @Override
        public App getItem(int position) {
            return mApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_app, parent, false);
            final App a = getItem(position);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(a.icon);
            ((TextView) v.findViewById(R.id.label)).setText(a.label);
            final Switch s = (Switch) v.findViewById(R.id.locked);
            s.setOnCheckedChangeListener(null);
            s.setChecked(mLocked.contains(a.pkg));
            s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    Locks.set(AppLockActivity.this, a.pkg, on);
                    if (on) {
                        mLocked.add(a.pkg);
                    } else {
                        mLocked.remove(a.pkg);
                    }
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    s.toggle();
                }
            });
            return v;
        }
    }
}
