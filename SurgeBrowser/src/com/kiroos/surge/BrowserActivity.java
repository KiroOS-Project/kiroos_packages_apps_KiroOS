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

package com.kiroos.surge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebViewDatabase;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Surge Browser: tabs of WebViews, a combined address / search bar, built-in ad and tracker
 * blocking, bookmarks, history, desktop mode, downloads, file uploads and full screen video.
 * 1.2: find in page and text size.
 */
public class BrowserActivity extends Activity {

    private static final int MAX_TABS = 12;
    private static final int REQUEST_FILE = 1;
    private static final int HISTORY_SIZE = 200;
    private static final String SEP = "";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/70.0.3538.110 Safari/537.36";
    private static final String[][] ENGINES = {
            {"DuckDuckGo", "https://duckduckgo.com/?q="},
            {"Google", "https://www.google.com/search?q="},
            {"Bing", "https://www.bing.com/search?q="},
            {"Startpage", "https://www.startpage.com/do/search?query="},
    };

    /** one open page */
    private final class Tab {
        final WebView web;
        int blocked;
        boolean desktop;

        Tab(WebView web) {
            this.web = web;
        }

        String title() {
            final String t = web.getTitle();
            return TextUtils.isEmpty(t) || "about:blank".equals(t) ? getString(R.string.new_tab) : t;
        }
    }

    private final List<Tab> mTabs = new ArrayList<>();
    private Tab mCurrent;
    private SharedPreferences mPrefs;
    private FrameLayout mContent;
    private EditText mUrl;
    private ProgressBar mProgress;
    private TextView mTabCount;
    private TextView mShield;
    private View mTabsPanel;
    private ListView mTabsList;
    private FrameLayout mFullscreen;
    private View mCustomView;
    private WebChromeClient.CustomViewCallback mCustomCallback;
    private ValueCallback<Uri[]> mFileCallback;
    private String mPendingDownload;
    /** 1.2: find in page */
    private View mFindBar;
    private EditText mFindText;
    private TextView mFindCount;
    /** 1.2: text size, as a percentage */
    private static final int[] TEXT_ZOOMS = {80, 90, 100, 115, 130, 150};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);
        mPrefs = getSharedPreferences("surge", MODE_PRIVATE);
        setupFind();
        mContent = (FrameLayout) findViewById(R.id.content);
        mUrl = (EditText) findViewById(R.id.url);
        mProgress = (ProgressBar) findViewById(R.id.progress);
        mTabCount = (TextView) findViewById(R.id.tab_count);
        mShield = (TextView) findViewById(R.id.shield);
        mTabsPanel = findViewById(R.id.tabs_panel);
        mTabsList = (ListView) findViewById(R.id.tabs_list);
        mFullscreen = (FrameLayout) findViewById(R.id.fullscreen);

        mUrl.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_GO || event != null
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    go(mUrl.getText().toString());
                    return true;
                }
                return false;
            }
        });
        mUrl.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    // show the full address to edit, all selected
                    // (the start page is about:blank: nothing to edit there, keep the hint)
                    final String url = mCurrent != null ? mCurrent.web.getUrl() : null;
                    if (url != null && !url.startsWith("data:") && !"about:blank".equals(url)) {
                        mUrl.setText(url);
                    }
                    mUrl.selectAll();
                } else {
                    showAddress();
                }
            }
        });
        findViewById(R.id.btn_tabs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTabs(mTabsPanel.getVisibility() != View.VISIBLE);
            }
        });
        findViewById(R.id.btn_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        findViewById(R.id.btn_new_tab).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newTab(null, true);
                showTabs(false);
                mUrl.requestFocus();
                showKeyboard();
            }
        });
        mShield.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(BrowserActivity.this, getString(R.string.blocked_here,
                        mCurrent != null ? mCurrent.blocked : 0), Toast.LENGTH_SHORT).show();
            }
        });
        mTabsList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                switchTo(mTabs.get(position));
                showTabs(false);
            }
        });
        mTabsList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                closeTab(mTabs.get(position));
                refreshTabList();
                return true;
            }
        });

        final String url = urlFrom(getIntent());
        newTab(url, true);
        if (getIntent().getBooleanExtra(EXTRA_SEARCH, false)) {
            focusAddress();
        }
    }

    /** Beta 4: the Surge widget's search bar opens a new tab with the keyboard up */
    static final String EXTRA_SEARCH = "com.kiroos.surge.extra.SEARCH";

    private void focusAddress() {
        mUrl.requestFocus();
        mUrl.post(new Runnable() {
            @Override
            public void run() {
                showKeyboard();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SEARCH, false)) {
            newTab(null, true);
            showTabs(false);
            focusAddress();
            return;
        }
        final String url = urlFrom(intent);
        if (url != null) {
            newTab(url, true);
        }
    }

    private String urlFrom(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Intent.ACTION_WEB_SEARCH.equals(intent.getAction())) {
            final String q = intent.getStringExtra(SearchManager.QUERY);
            return q != null ? searchUrl(q) : null;
        }
        final Uri data = intent.getData();
        return data != null ? data.toString() : null;
    }

    // --- tabs -----------------------------------------------------------------------------------

    private Tab newTab(String url, boolean select) {
        if (mTabs.size() >= MAX_TABS) {
            closeTab(mTabs.get(0)); // oldest goes
        }
        final WebView web = new WebView(this);
        final Tab tab = new Tab(web);
        setup(tab);
        mTabs.add(tab);
        if (select) {
            switchTo(tab);
        }
        if (url != null) {
            web.loadUrl(url);
        } else {
            showStartPage(tab);
        }
        updateTabCount();
        return tab;
    }

    private void switchTo(Tab tab) {
        closeFind();
        if (mCurrent != null) {
            mCurrent.web.onPause();
        }
        mCurrent = tab;
        mContent.removeAllViews();
        mContent.addView(tab.web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tab.web.onResume();
        showAddress();
        updateShield();
        mProgress.setVisibility(tab.web.getProgress() < 100 ? View.VISIBLE : View.GONE);
    }

    private void closeTab(Tab tab) {
        final int index = mTabs.indexOf(tab);
        mTabs.remove(tab);
        mContent.removeView(tab.web);
        tab.web.stopLoading();
        tab.web.destroy();
        if (mTabs.isEmpty()) {
            newTab(null, true);
        } else if (tab == mCurrent) {
            switchTo(mTabs.get(Math.max(0, index - 1)));
        }
        updateTabCount();
    }

    private void updateTabCount() {
        mTabCount.setText(String.valueOf(mTabs.size()));
    }

    private void showTabs(boolean show) {
        mTabsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            refreshTabList();
            hideKeyboard();
        }
    }

    private void refreshTabList() {
        final List<String> titles = new ArrayList<>();
        for (Tab t : mTabs) {
            titles.add((t == mCurrent ? "● " : "") + t.title());
        }
        mTabsList.setAdapter(new ArrayAdapter<>(this, R.layout.item_tab, R.id.tab_title, titles));
    }

    // --- WebView setup --------------------------------------------------------------------------

    private void setup(final Tab tab) {
        final WebView web = tab.web;
        web.setBackgroundColor(getColor(R.color.kiro_black));
        final WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(mPrefs.getInt("text_zoom", 100));
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,
                !mPrefs.getBoolean("block_3p_cookies", true));
        web.setFindListener(new WebView.FindListener() {
            @Override
            public void onFindResultReceived(int active, int count, boolean done) {
                if (tab == mCurrent && done) {
                    mFindCount.setText(count == 0
                            ? (mFindText.length() == 0 ? "" : "0/0")
                            : (active + 1) + "/" + count);
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                    WebResourceRequest request) {
                if (mPrefs.getBoolean("adblock", true) && !request.isForMainFrame()
                        && AdBlocker.get(BrowserActivity.this).isAd(request.getUrl())) {
                    tab.blocked++;
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            if (tab == mCurrent) {
                                updateShield();
                            }
                        }
                    });
                    return AdBlocker.empty();
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                final Uri uri = request.getUrl();
                final String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme)
                        || "data".equals(scheme)) {
                    return false;
                }
                // tel:, mailto:, intent:, market:… go to the right app
                try {
                    final Intent i = "intent".equals(scheme)
                            ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, uri);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(BrowserActivity.this, R.string.no_app, Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.blocked = 0;
                if (tab == mCurrent) {
                    mProgress.setVisibility(View.VISIBLE);
                    showAddress();
                    updateShield();
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                // the web's canvas is white unless a page paints its own: on the theme's black
                // a page without a background was black text on black (example.com uses CSS
                // light-dark(), newer than Chromium 119, so its background is dropped). Black
                // stays only while loading, so the dark theme has no white flash.
                final boolean web = url != null
                        && (url.startsWith("http:") || url.startsWith("https:"));
                view.setBackgroundColor(web ? Color.WHITE : getColor(R.color.kiro_black));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (tab == mCurrent) {
                    mProgress.setVisibility(View.GONE);
                    showAddress();
                }
                addHistory(view.getTitle(), url);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (tab == mCurrent) {
                    mProgress.setProgress(progress);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (tab == mCurrent && !mUrl.hasFocus()) {
                    showAddress();
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                if (mFileCallback != null) {
                    mFileCallback.onReceiveValue(null);
                }
                mFileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQUEST_FILE);
                } catch (ActivityNotFoundException e) {
                    mFileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(final String origin,
                    final GeolocationPermissions.Callback callback) {
                new AlertDialog.Builder(BrowserActivity.this)
                        .setMessage(getString(R.string.location_prompt, Uri.parse(origin).getHost()))
                        .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                callback.invoke(origin, true, false);
                            }
                        })
                        .setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                callback.invoke(origin, false, false);
                            }
                        })
                        .show();
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                new AlertDialog.Builder(BrowserActivity.this)
                        .setMessage(getString(R.string.media_prompt, request.getOrigin().getHost()))
                        .setPositiveButton(R.string.allow, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                request.grant(request.getResources());
                            }
                        })
                        .setNegativeButton(R.string.deny, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                request.deny();
                            }
                        })
                        .show();
            }
        });
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String disposition,
                    String mime, long length) {
                download(url, userAgent, disposition, mime);
            }
        });
        web.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                return onLongPress(web.getHitTestResult());
            }
        });
    }

    private boolean onLongPress(final WebView.HitTestResult hit) {
        if (hit == null || hit.getExtra() == null) {
            return false;
        }
        final int type = hit.getType();
        final String extra = hit.getExtra();
        final List<String> items = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            items.add(getString(R.string.open_new_tab));
            ids.add(1);
            items.add(getString(R.string.copy_link));
            ids.add(2);
            items.add(getString(R.string.share_link));
            ids.add(3);
        }
        if (type == WebView.HitTestResult.IMAGE_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            items.add(getString(R.string.download_image));
            ids.add(4);
        }
        if (items.isEmpty()) {
            return false;
        }
        new AlertDialog.Builder(this)
                .setTitle(extra.length() > 80 ? extra.substring(0, 80) + "…" : extra)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (ids.get(which)) {
                            case 1:
                                newTab(extra, false);
                                Toast.makeText(BrowserActivity.this, R.string.opened_in_background,
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 2:
                                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                        .setPrimaryClip(ClipData.newPlainText(null, extra));
                                Toast.makeText(BrowserActivity.this, R.string.copied,
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 3:
                                share(extra, null);
                                break;
                            default:
                                download(extra, mCurrent.web.getSettings().getUserAgentString(),
                                        null, null);
                                break;
                        }
                    }
                })
                .show();
        return true;
    }

    // --- navigation -----------------------------------------------------------------------------

    private void go(String input) {
        final String text = input.trim();
        if (text.isEmpty() || mCurrent == null) {
            return;
        }
        String url;
        if (text.contains("://") || text.startsWith("about:")) {
            url = text;
        } else if (!text.contains(" ") && (Patterns.WEB_URL.matcher(text).matches()
                || text.startsWith("localhost") || text.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*"))) {
            url = "https://" + text;
        } else {
            url = searchUrl(text);
        }
        mCurrent.web.loadUrl(url);
        mCurrent.web.requestFocus();
        hideKeyboard();
    }

    private String searchUrl(String query) {
        final int engine = mPrefs.getInt("engine", 0);
        return ENGINES[engine][1] + Uri.encode(query);
    }

    private void showAddress() {
        if (mCurrent == null || mUrl.hasFocus()) {
            return;
        }
        final String url = mCurrent.web.getUrl();
        if (url == null || url.startsWith("data:") || "about:blank".equals(url)) {
            mUrl.setText("");
            mUrl.setHint(R.string.url_hint);
            return;
        }
        // show the host, like modern browsers; the full address when editing
        final Uri u = Uri.parse(url);
        mUrl.setText(u.getHost() != null ? u.getHost().replaceFirst("^www\\.", "") : url);
        final boolean secure = "https".equals(u.getScheme());
        mUrl.setCompoundDrawablesRelativeWithIntrinsicBounds(
                secure ? R.drawable.ic_lock_small : 0, 0, 0, 0);
    }

    private void updateShield() {
        final int n = mCurrent != null ? mCurrent.blocked : 0;
        mShield.setVisibility(mPrefs.getBoolean("adblock", true) ? View.VISIBLE : View.GONE);
        mShield.setText(n > 99 ? "99+" : String.valueOf(n));
    }

    private void showStartPage(Tab tab) {
        final StringBuilder links = new StringBuilder();
        for (String[] b : bookmarks()) {
            links.append("<a href=\"").append(TextUtils.htmlEncode(b[1])).append("\">")
                    .append(TextUtils.htmlEncode(b[0])).append("</a>");
        }
        final String html = "<!DOCTYPE html><html><head><meta name=viewport "
                + "content=\"width=device-width,initial-scale=1\"><style>"
                + "body{background:#000;color:#fff;font-family:sans-serif;margin:0;padding:48px 20px}"
                + "h1{font-weight:300;font-size:40px;margin:0 0 4px}p{color:#999;margin:0 0 28px}"
                + "a{display:block;padding:16px;margin:0 0 10px;background:#1C1C1F;color:#fff;"
                + "text-decoration:none;border-radius:18px;overflow:hidden;text-overflow:ellipsis;"
                + "white-space:nowrap}</style></head><body><h1>Surge</h1><p>"
                + TextUtils.htmlEncode(getString(R.string.start_hint)) + "</p>" + links
                + "</body></html>";
        tab.web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
    }

    @Override
    public void onBackPressed() {
        if (mCustomView != null) {
            exitFullscreen();
        } else if (mFindBar.getVisibility() == View.VISIBLE) {
            closeFind();
        } else if (mTabsPanel.getVisibility() == View.VISIBLE) {
            showTabs(false);
        } else if (mUrl.hasFocus()) {
            mUrl.clearFocus();
            mCurrent.web.requestFocus();
        } else if (mCurrent != null && mCurrent.web.canGoBack()) {
            mCurrent.web.goBack();
        } else if (mTabs.size() > 1) {
            closeTab(mCurrent);
        } else {
            super.onBackPressed();
        }
    }

    // --- menu -----------------------------------------------------------------------------------

    private void showMenu(View anchor) {
        final PopupMenu menu = new PopupMenu(this, anchor);
        final boolean page = mCurrent != null && mCurrent.web.getUrl() != null
                && mCurrent.web.getUrl().startsWith("http");
        menu.getMenu().add(0, 1, 0, R.string.forward).setEnabled(mCurrent != null
                && mCurrent.web.canGoForward());
        menu.getMenu().add(0, 2, 0, R.string.reload);
        menu.getMenu().add(0, 3, 0, R.string.new_tab);
        menu.getMenu().add(0, 4, 0, R.string.add_bookmark).setEnabled(page);
        menu.getMenu().add(0, 5, 0, R.string.bookmarks);
        menu.getMenu().add(0, 6, 0, R.string.history);
        menu.getMenu().add(0, 7, 0, R.string.share).setEnabled(page);
        menu.getMenu().add(0, 8, 0, R.string.desktop_site).setCheckable(true)
                .setChecked(mCurrent != null && mCurrent.desktop);
        menu.getMenu().add(0, 9, 0, R.string.adblock).setCheckable(true)
                .setChecked(mPrefs.getBoolean("adblock", true));
        menu.getMenu().add(0, 12, 0, R.string.find_in_page).setEnabled(page);
        menu.getMenu().add(0, 13, 0, R.string.text_size);
        menu.getMenu().add(0, 10, 0, R.string.search_engine);
        menu.getMenu().add(0, 11, 0, R.string.clear_data);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        mCurrent.web.goForward();
                        return true;
                    case 2:
                        mCurrent.web.reload();
                        return true;
                    case 3:
                        newTab(null, true);
                        mUrl.requestFocus();
                        showKeyboard();
                        return true;
                    case 4:
                        addBookmark(mCurrent.web.getTitle(), mCurrent.web.getUrl());
                        return true;
                    case 5:
                        showList(R.string.bookmarks, bookmarks(), true);
                        return true;
                    case 6:
                        showList(R.string.history, history(), false);
                        return true;
                    case 7:
                        share(mCurrent.web.getUrl(), mCurrent.web.getTitle());
                        return true;
                    case 8:
                        toggleDesktop();
                        return true;
                    case 9:
                        mPrefs.edit().putBoolean("adblock", !mPrefs.getBoolean("adblock", true))
                                .apply();
                        updateShield();
                        mCurrent.web.reload();
                        return true;
                    case 10:
                        chooseEngine();
                        return true;
                    case 11:
                        confirmClearData();
                        return true;
                    case 12:
                        openFind();
                        return true;
                    case 13:
                        chooseTextSize();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    // --- find in page and text size (1.2) ------------------------------------------------------

    private void setupFind() {
        mFindBar = findViewById(R.id.find_bar);
        mFindText = (EditText) findViewById(R.id.find_text);
        mFindCount = (TextView) findViewById(R.id.find_count);
        mFindText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (mCurrent != null && mFindBar.getVisibility() == View.VISIBLE) {
                    if (s.length() == 0) {
                        mCurrent.web.clearMatches();
                        mFindCount.setText("");
                    } else {
                        mCurrent.web.findAllAsync(s.toString());
                    }
                }
            }
        });
        mFindText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (mCurrent != null) {
                    mCurrent.web.findNext(true);
                }
                return true;
            }
        });
        findViewById(R.id.find_next).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrent != null && mFindText.length() > 0) {
                    mCurrent.web.findNext(true);
                }
            }
        });
        findViewById(R.id.find_prev).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCurrent != null && mFindText.length() > 0) {
                    mCurrent.web.findNext(false);
                }
            }
        });
        findViewById(R.id.find_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeFind();
            }
        });
    }

    private void openFind() {
        mFindBar.setVisibility(View.VISIBLE);
        mFindText.setText("");
        mFindCount.setText("");
        mFindText.requestFocus();
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(mFindText, 0);
    }

    private void closeFind() {
        if (mFindBar == null || mFindBar.getVisibility() != View.VISIBLE) {
            return;
        }
        mFindBar.setVisibility(View.GONE);
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(mFindText.getWindowToken(), 0);
        if (mCurrent != null) {
            mCurrent.web.clearMatches();
        }
    }

    private void chooseTextSize() {
        final int current = mPrefs.getInt("text_zoom", 100);
        final String[] names = new String[TEXT_ZOOMS.length];
        int checked = 2;
        for (int i = 0; i < TEXT_ZOOMS.length; i++) {
            names[i] = TEXT_ZOOMS[i] + " %";
            if (TEXT_ZOOMS[i] == current) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.text_size)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final int zoom = TEXT_ZOOMS[which];
                        mPrefs.edit().putInt("text_zoom", zoom).apply();
                        for (Tab t : mTabs) {
                            t.web.getSettings().setTextZoom(zoom);
                        }
                        d.dismiss();
                    }
                })
                .show();
    }

    private void toggleDesktop() {
        mCurrent.desktop = !mCurrent.desktop;
        final WebSettings s = mCurrent.web.getSettings();
        s.setUserAgentString(mCurrent.desktop ? DESKTOP_UA : null);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        mCurrent.web.reload();
    }

    private void chooseEngine() {
        final String[] names = new String[ENGINES.length];
        for (int i = 0; i < ENGINES.length; i++) {
            names[i] = ENGINES[i][0];
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.search_engine)
                .setSingleChoiceItems(names, mPrefs.getInt("engine", 0),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                mPrefs.edit().putInt("engine", which).apply();
                                d.dismiss();
                            }
                        })
                .show();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_data)
                .setMessage(R.string.clear_data_confirm)
                .setPositiveButton(R.string.clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        CookieManager.getInstance().removeAllCookies(null);
                        WebStorage.getInstance().deleteAllData();
                        WebViewDatabase.getInstance(BrowserActivity.this).clearFormData();
                        for (Tab t : mTabs) {
                            t.web.clearCache(true);
                            t.web.clearHistory();
                        }
                        mPrefs.edit().remove("history").apply();
                        Toast.makeText(BrowserActivity.this, R.string.cleared,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- bookmarks & history ------------------------------------------------------------------

    private List<String[]> read(String key) {
        final List<String[]> out = new ArrayList<>();
        final String s = mPrefs.getString(key, "");
        if (!s.isEmpty()) {
            final List<String> parts = Arrays.asList(s.split(SEP, -1));
            for (int i = 0; i + 1 < parts.size(); i += 2) {
                out.add(new String[] {parts.get(i), parts.get(i + 1)});
            }
        }
        return out;
    }

    private void write(String key, List<String[]> items) {
        final List<String> flat = new ArrayList<>();
        for (String[] i : items) {
            flat.add(i[0].replace(SEP, " "));
            flat.add(i[1]);
        }
        mPrefs.edit().putString(key, TextUtils.join(SEP, flat)).apply();
    }

    private List<String[]> bookmarks() {
        return read("bookmarks");
    }

    private List<String[]> history() {
        return read("history");
    }

    private void addBookmark(String title, String url) {
        final List<String[]> list = bookmarks();
        for (String[] b : list) {
            if (b[1].equals(url)) {
                Toast.makeText(this, R.string.already_bookmarked, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        list.add(0, new String[] {TextUtils.isEmpty(title) ? url : title, url});
        write("bookmarks", list);
        Toast.makeText(this, R.string.bookmarked, Toast.LENGTH_SHORT).show();
    }

    private void addHistory(String title, String url) {
        if (url == null || !url.startsWith("http")) {
            return;
        }
        final List<String[]> list = history();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[1].equals(url)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, new String[] {TextUtils.isEmpty(title) ? url : title, url});
        while (list.size() > HISTORY_SIZE) {
            list.remove(list.size() - 1);
        }
        write("history", list);
    }

    private void showList(int title, final List<String[]> items, final boolean bookmarks) {
        if (items.isEmpty()) {
            Toast.makeText(this, bookmarks ? R.string.no_bookmarks : R.string.no_history,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[items.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = items.get(i)[0] + "\n" + items.get(i)[1];
        }
        final AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        mCurrent.web.loadUrl(items.get(which)[1]);
                    }
                });
        if (bookmarks) {
            b.setNeutralButton(R.string.edit_bookmarks, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    removeBookmarks(items, labels);
                }
            });
        } else {
            b.setNeutralButton(R.string.clear, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    mPrefs.edit().remove("history").apply();
                }
            });
        }
        b.show();
    }

    private void removeBookmarks(final List<String[]> items, String[] labels) {
        final boolean[] checked = new boolean[items.size()];
        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_bookmarks)
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final List<String[]> keep = new ArrayList<>();
                        for (int i = 0; i < items.size(); i++) {
                            if (!checked[i]) {
                                keep.add(items.get(i));
                            }
                        }
                        write("bookmarks", keep);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- downloads, sharing, files ---------------------------------------------------------------

    private void download(String url, String userAgent, String disposition, String mime) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            mPendingDownload = url;
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        final String name = URLUtil.guessFileName(url, disposition, mime);
        try {
            final DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setMimeType(mime)
                    .setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    .addRequestHeader("User-Agent", userAgent != null ? userAgent : "")
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) != null
                            ? CookieManager.getInstance().getCookie(url) : "");
            r.allowScanningByMediaScanner();
            getSystemService(DownloadManager.class).enqueue(r);
            Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == 1 && mPendingDownload != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            download(mPendingDownload, mCurrent.web.getSettings().getUserAgentString(), null, null);
        }
        mPendingDownload = null;
    }

    private void share(String url, String title) {
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url)
                .putExtra(Intent.EXTRA_SUBJECT, title), getString(R.string.share)));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_FILE && mFileCallback != null) {
            mFileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,
                    data));
            mFileCallback = null;
        }
    }

    // --- full screen video ------------------------------------------------------------------------

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (mCustomView != null) {
            callback.onCustomViewHidden();
            return;
        }
        mCustomView = view;
        mCustomCallback = callback;
        mFullscreen.addView(view);
        mFullscreen.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR);
    }

    private void exitFullscreen() {
        if (mCustomView == null) {
            return;
        }
        mFullscreen.removeView(mCustomView);
        mFullscreen.setVisibility(View.GONE);
        mCustomView = null;
        if (mCustomCallback != null) {
            mCustomCallback.onCustomViewHidden();
        }
        mCustomCallback = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    // --- lifecycle ------------------------------------------------------------------------------

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(mUrl.getWindowToken(), 0);
    }

    private void showKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .showSoftInput(mUrl, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mCurrent != null) {
            mCurrent.web.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mCurrent != null) {
            mCurrent.web.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        for (Tab t : mTabs) {
            t.web.destroy();
        }
        mTabs.clear();
        super.onDestroy();
    }
}
