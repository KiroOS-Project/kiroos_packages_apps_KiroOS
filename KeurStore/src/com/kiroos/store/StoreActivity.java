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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * KeurStore 1.1: Featured, Games, Utility and Kiro apps. "Get" opens the app's Google Play
 * page or, for apps with an official download, installs it silently here; installed apps
 * show "Open". Every installed app is compiled ahead of time by the system.
 */
public class StoreActivity extends Activity {

    private static final String[] SECTIONS = {"featured", "games", "utility", "kiro"};
    private static final int[] SECTION_NAMES = {R.string.section_featured, R.string.section_games,
            R.string.section_utility, R.string.section_kiro};

    private final Handler mHandler = new Handler();
    private final Adapter mAdapter = new Adapter();
    private final Map<String, Integer> mProgress = new HashMap<>();
    private Map<String, List<Catalog.App>> mCatalog;
    private String mSection = SECTIONS[0];
    private final List<TextView> mChips = new ArrayList<>();

    private final BroadcastReceiver mDone = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            final String pkg = i.getStringExtra("pkg");
            mProgress.remove(pkg);
            Toast.makeText(StoreActivity.this, i.getBooleanExtra("ok", false)
                    ? R.string.installed : R.string.install_failed, Toast.LENGTH_SHORT).show();
            mAdapter.notifyDataSetChanged();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_store);
        final boolean italian = "it".equals(Locale.getDefault().getLanguage());
        mCatalog = Catalog.load(this, italian);
        final LinearLayout chips = (LinearLayout) findViewById(R.id.sections);
        for (int i = 0; i < SECTIONS.length; i++) {
            final String id = SECTIONS[i];
            final TextView chip = (TextView) LayoutInflater.from(this)
                    .inflate(R.layout.section_chip, chips, false);
            chip.setText(SECTION_NAMES[i]);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mSection = id;
                    updateChips();
                    mAdapter.notifyDataSetChanged();
                }
            });
            chips.addView(chip);
            mChips.add(chip);
        }
        updateChips();
        ((ListView) findViewById(R.id.list)).setAdapter(mAdapter);
        registerReceiver(mDone, new IntentFilter(Installer.ACTION_DONE));
        UpdateJob.schedule(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (Catalog.refresh(StoreActivity.this)) {
                    final Map<String, List<Catalog.App>> fresh = Catalog.load(StoreActivity.this,
                            italian);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCatalog = fresh;
                            mAdapter.notifyDataSetChanged();
                        }
                    });
                }
            }
        }, "KeurStore-catalogue").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAdapter.notifyDataSetChanged();   // installed / not installed may have changed
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mDone);
        super.onDestroy();
    }

    private void updateChips() {
        for (int i = 0; i < mChips.size(); i++) {
            final boolean on = SECTIONS[i].equals(mSection);
            mChips.get(i).setSelected(on);
            mChips.get(i).setTextColor(getColor(on ? R.color.kiro_on_accent : R.color.kiro_text));
        }
    }

    private List<Catalog.App> apps() {
        final List<Catalog.App> l = mCatalog.get(mSection);
        return l != null ? l : new ArrayList<Catalog.App>();
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void get(final Catalog.App app) {
        if (installed(app.pkg)) {
            final Intent launch = getPackageManager().getLaunchIntentForPackage(app.pkg);
            if (launch != null) {
                startActivity(launch);
            }
            return;
        }
        if (!app.direct()) {
            // Google Play installs and updates it; KiroOS compiles it once installed
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + app.pkg)).setPackage("com.android.vending"));
            } catch (android.content.ActivityNotFoundException e) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + app.pkg)));
            }
            return;
        }
        if (mProgress.containsKey(app.pkg)) {
            return;
        }
        mProgress.put(app.pkg, 0);
        mAdapter.notifyDataSetChanged();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = Installer.install(StoreActivity.this, app,
                        new Installer.Progress() {
                            @Override
                            public void onProgress(final int percent) {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        mProgress.put(app.pkg, percent);
                                        mAdapter.notifyDataSetChanged();
                                    }
                                });
                            }
                        });
                if (!ok) {
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mProgress.remove(app.pkg);
                            mAdapter.notifyDataSetChanged();
                            Toast.makeText(StoreActivity.this, R.string.install_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, "KeurStore-install").start();
    }

    /** a letter on a squircle in a colour of its own, until the app is installed */
    private Drawable iconFor(Catalog.App app) {
        try {
            return getPackageManager().getApplicationIcon(app.pkg);
        } catch (PackageManager.NameNotFoundException e) {
            final int[] colors = {0xFF3987E5, 0xFF7B5CFF, 0xFFFF8A3D, 0xFF34C759, 0xFFFF5B50,
                    0xFF2FC7B5, 0xFFD45CF0};
            final int color = colors[Math.abs(app.pkg.hashCode()) % colors.length];
            final String letter = app.name.substring(0, 1).toUpperCase(Locale.ROOT);
            return new SquircleDrawable(color) {
                private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                public void draw(Canvas canvas) {
                    super.draw(canvas);
                    mText.setColor(0xFFFFFFFF);
                    mText.setTextAlign(Paint.Align.CENTER);
                    mText.setTextSize(getBounds().height() * 0.46f);
                    mText.setFakeBoldText(true);
                    canvas.drawText(letter, getBounds().exactCenterX(),
                            getBounds().exactCenterY() - (mText.ascent() + mText.descent()) / 2,
                            mText);
                }
            };
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps().size();
        }

        @Override
        public Catalog.App getItem(int i) {
            return apps().get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) {
                v = LayoutInflater.from(StoreActivity.this).inflate(R.layout.row_app, parent, false);
            }
            final Catalog.App app = getItem(i);
            ((ImageView) v.findViewById(R.id.icon)).setImageDrawable(iconFor(app));
            ((TextView) v.findViewById(R.id.name)).setText(app.name);
            ((TextView) v.findViewById(R.id.desc)).setText(app.desc);
            final TextView badge = (TextView) v.findViewById(R.id.badge);
            badge.setText(app.source.equals("system") ? R.string.badge_kiro : R.string.badge_optimized);
            final TextView action = (TextView) v.findViewById(R.id.action);
            final Integer p = mProgress.get(app.pkg);
            if (p != null) {
                action.setText(p >= 0 ? p + "%" : getString(R.string.downloading));
                action.setEnabled(false);
            } else {
                action.setEnabled(true);
                action.setText(installed(app.pkg) ? R.string.open : R.string.get);
            }
            action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    get(app);
                }
            });
            KiroTouch.apply(action);
            return v;
        }
    }
}
