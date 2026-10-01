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

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.kiroos.ui.KiroTouch;

import java.util.ArrayList;
import java.util.List;

/**
 * Kiro Setup Wizard 1.2. Pages: language, Wi-Fi, mobile network, date and time, Google
 * account (with Play services), location and privacy, screen lock and fingerprint,
 * appearance, kAI, what's in KiroOS, done. It follows the system theme (light on a new
 * phone; the appearance page changes it and the wizard redraws on the same page). It is the
 * home activity until setup completes; finishing sets DEVICE_PROVISIONED and
 * USER_SETUP_COMPLETE and disables this component, so the next home is Kiro Home.
 */
public class SetupActivity extends Activity {

    private static final String STATE_PAGE = "page";
    /**
     * Developer preview on an already set up phone (adb shell am start -n
     * com.kiroos.setupwizard/.SetupActivity --ez com.kiroos.setupwizard.PREVIEW true): the
     * pages work normally, but finishing only closes the wizard.
     */
    static final String EXTRA_PREVIEW = "com.kiroos.setupwizard.PREVIEW";

    private boolean mPreview;

    private final List<SetupPage> mPages = new ArrayList<>();
    private FrameLayout mContainer;
    private LinearLayout mDots;
    private Button mBack;
    private Button mNext;
    private int mIndex;
    private View mCurrentView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPreview = getIntent().getBooleanExtra(EXTRA_PREVIEW, false);
        if (isSetupComplete(this) && !mPreview) {
            // e.g. a restore or an adb provisioned device: nothing to do
            finishSetup();
            return;
        }
        setContentView(R.layout.activity_setup);
        mContainer = (FrameLayout) findViewById(R.id.page_container);
        mDots = (LinearLayout) findViewById(R.id.progress_dots);
        mBack = (Button) findViewById(R.id.button_back);
        mNext = (Button) findViewById(R.id.button_next);
        KiroTouch.apply(mBack, mNext);

        mPages.add(new LanguagePage(this));
        mPages.add(new WifiPage(this));
        mPages.add(new MobilePage(this));
        mPages.add(new DateTimePage(this));
        if (GooglePage.available(this)) {
            mPages.add(new GooglePage(this));
        }
        mPages.add(new PrivacyPage(this));
        mPages.add(new SecurityPage(this));
        mPages.add(new AppearancePage(this));
        if (KaiPage.available(this)) {
            mPages.add(new KaiPage(this));
        }
        mPages.add(new HighlightsPage(this));
        mPages.add(new DonePage(this));
        buildDots();

        mBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                show(mIndex - 1, false);
            }
        });
        mNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final SetupPage page = mPages.get(mIndex);
                page.onLeave();
                if (mIndex == mPages.size() - 1) {
                    finishSetup();
                } else {
                    show(mIndex + 1, true);
                }
            }
        });

        // the status bar stays locked (no quick settings, no notifications) during setup
        setStatusBarLocked(true);
        show(savedInstanceState != null ? savedInstanceState.getInt(STATE_PAGE, 0) : 0, true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, mIndex);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mPages.isEmpty()) {
            mPages.get(mIndex).onShown();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!mPages.isEmpty()) {
            mPages.get(mIndex).onHidden();
        }
    }

    @Override
    public void onBackPressed() {
        if (mIndex > 0) {
            show(mIndex - 1, false);
        }
        // on the first page Back does nothing: there is nothing behind the setup
    }

    /** Pages call this when their state changes what the Next button says. */
    void refreshButtons() {
        final SetupPage page = mPages.get(mIndex);
        mBack.setVisibility(mIndex == 0 ? View.INVISIBLE : View.VISIBLE);
        mNext.setText(page.nextLabel());
    }

    /** Called by the language page after a locale change recreated the activity. */
    int currentIndex() {
        return mIndex;
    }

    private void show(int index, boolean forward) {
        if (index < 0 || index >= mPages.size()) {
            return;
        }
        if (mCurrentView != null) {
            mPages.get(mIndex).onHidden();
            final View old = mCurrentView;
            final float dx = getResources().getDisplayMetrics().widthPixels * 0.25f;
            old.animate().alpha(0f).translationX(forward ? -dx : dx).setDuration(220)
                    .setInterpolator(KiroTouch.EASE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mContainer.removeView(old);
                        }
                    }).start();
        }
        mIndex = index;
        final SetupPage page = mPages.get(index);
        final View view = page.getView(mContainer);
        if (view.getParent() != null) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        mContainer.addView(view);
        view.setAlpha(0f);
        view.setTranslationX((forward ? 1 : -1) * getResources().getDisplayMetrics().widthPixels * 0.25f);
        view.animate().alpha(1f).translationX(0f).setDuration(320)
                .setInterpolator(KiroTouch.EASE).start();
        mCurrentView = view;
        page.onShown();
        updateDots();
        refreshButtons();
    }

    private void buildDots() {
        final int size = Math.round(getResources().getDisplayMetrics().density * 6);
        final int gap = Math.round(getResources().getDisplayMetrics().density * 4);
        for (int i = 0; i < mPages.size(); i++) {
            final View dot = new View(this);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(gap);
            lp.setMarginEnd(gap);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.setup_dot);
            mDots.addView(dot);
        }
    }

    private void updateDots() {
        for (int i = 0; i < mDots.getChildCount(); i++) {
            final View dot = mDots.getChildAt(i);
            final boolean current = i == mIndex;
            dot.animate().alpha(current ? 1f : 0.3f).scaleX(current ? 1.6f : 1f)
                    .setDuration(200).start();
        }
    }

    static boolean isSetupComplete(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) == 1
                && Settings.Secure.getInt(context.getContentResolver(),
                        Settings.Secure.USER_SETUP_COMPLETE, 0) == 1;
    }

    private void setStatusBarLocked(boolean locked) {
        final StatusBarManager sbm = (StatusBarManager) getSystemService(Context.STATUS_BAR_SERVICE);
        if (sbm != null) {
            sbm.disable(locked ? StatusBarManager.DISABLE_EXPAND
                    | StatusBarManager.DISABLE_NOTIFICATION_ICONS
                    | StatusBarManager.DISABLE_NOTIFICATION_ALERTS
                    | StatusBarManager.DISABLE_SEARCH
                    | StatusBarManager.DISABLE_RECENT : StatusBarManager.DISABLE_NONE);
        }
    }

    /** Marks the device provisioned, disables the wizard and hands over to Kiro Home. */
    private void finishSetup() {
        if (mPreview) {
            setStatusBarLocked(false);
            finish();
            return;
        }
        Settings.Global.putInt(getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 1);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, 1);
        setStatusBarLocked(false);

        final PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(this, SetupActivity.class),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        final Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }
}
