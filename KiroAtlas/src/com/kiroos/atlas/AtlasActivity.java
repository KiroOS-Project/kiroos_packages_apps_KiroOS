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

package com.kiroos.atlas;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atlas: the map, place search, directions by car, bike or on foot, and turn-by-turn navigation
 * with voice. The screen has four states: browsing the map, a place, a route, navigating.
 */
public class AtlasActivity extends Activity implements MapView.Listener, LocationListener {

    private static final int REQ_LOCATION = 1;
    private static final int BROWSE = 0, PLACE = 1, ROUTE = 2, NAV = 3;
    private static final double[] NAV_ZOOM = {16.5, 17, 17.5};
    /** voice: the early and the last announcement, in meters, by mode */
    private static final int[] FAR = {800, 250, 120};
    private static final int[] NEAR = {150, 50, 25};
    private static final int[] OFF_ROUTE = {50, 35, 35};

    private MapView mMap;
    private EditText mSearch;
    private View mClear, mSearchBar, mNavBar, mSheet, mModes;
    private ListView mResults;
    private TextView mTitle, mDetail, mNavDistance, mNavText;
    private TextView[] mModeChips;
    private Button mPrimary, mSecondary;

    private final Handler mHandler = new Handler();
    private final ExecutorService mNet = Executors.newSingleThreadExecutor();
    private SharedPreferences mPrefs;
    private LocationManager mLocations;
    private AudioManager mAudio;
    private boolean mMetric;

    private int mState = BROWSE;
    private int mSearchGen, mRouteGen;
    private boolean mQuiet;
    private final List<Places.Place> mFound = new ArrayList<>();
    private PlaceAdapter mAdapter;
    private Places.Place mPlace;
    private int mMode;
    private Router.Route mRoute;
    private boolean mRouteBusy, mRoutePending;

    private boolean mListening;
    private Location mLocation;
    private boolean mCenterOnFix = true;

    private Navigator mNav;
    private boolean mFollow;
    private int mSpokenStep = -1, mSpokenLevel;
    private int mOffRouteFixes;
    private long mLastReroute;
    private TextToSpeech mTts;
    private boolean mTtsReady;
    private final List<String> mPendingSpeech = new ArrayList<>();
    private int mUtterance;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_atlas);
        mPrefs = getSharedPreferences("atlas", MODE_PRIVATE);
        mLocations = (LocationManager) getSystemService(LOCATION_SERVICE);
        mAudio = (AudioManager) getSystemService(AUDIO_SERVICE);
        // road distances in miles in the US, the UK, Liberia and Myanmar. The country is where
        // the phone is (its mobile network, else its SIM), not the language: English (US) in
        // Italy still drives in kilometers
        final android.telephony.TelephonyManager tm =
                (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String country = tm != null ? tm.getNetworkCountryIso() : null;
        if ((country == null || country.isEmpty()) && tm != null) country = tm.getSimCountryIso();
        if (country == null || country.isEmpty()) country = Locale.getDefault().getCountry();
        country = country.toUpperCase(Locale.US);
        mMetric = !("US".equals(country) || "GB".equals(country) || "LR".equals(country)
                || "MM".equals(country));
        mMode = Math.max(0, Math.min(2, mPrefs.getInt("mode", Router.CAR)));

        mMap = (MapView) findViewById(R.id.map);
        mSearch = (EditText) findViewById(R.id.search);
        mClear = findViewById(R.id.clear);
        mSearchBar = findViewById(R.id.search_bar);
        mNavBar = findViewById(R.id.nav_bar);
        mNavDistance = (TextView) findViewById(R.id.nav_distance);
        mNavText = (TextView) findViewById(R.id.nav_text);
        mResults = (ListView) findViewById(R.id.results);
        mSheet = findViewById(R.id.sheet);
        mTitle = (TextView) findViewById(R.id.title);
        mDetail = (TextView) findViewById(R.id.detail);
        mModes = findViewById(R.id.modes);
        mModeChips = new TextView[] {(TextView) findViewById(R.id.mode_car),
                (TextView) findViewById(R.id.mode_bike), (TextView) findViewById(R.id.mode_foot)};
        mPrimary = (Button) findViewById(R.id.primary);
        mSecondary = (Button) findViewById(R.id.secondary);

        mMap.setListener(this);
        if (mPrefs.contains("lat")) {
            mMap.setCamera(Double.parseDouble(mPrefs.getString("lat", "0")),
                    Double.parseDouble(mPrefs.getString("lon", "0")),
                    Double.parseDouble(mPrefs.getString("zoom", "5")));
        }

        mAdapter = new PlaceAdapter();
        mResults.setAdapter(mAdapter);
        mResults.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showPlace(mFound.get(position), true);
            }
        });
        mSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (mQuiet) return;
                mHandler.removeCallbacks(mSearchSoon);
                if (s.toString().trim().length() >= 3) {
                    mHandler.postDelayed(mSearchSoon, 700);
                } else {
                    mSearchGen++;
                    mFound.clear();
                    showResults();
                }
            }
        });
        mSearch.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
                mHandler.removeCallbacks(mSearchSoon);
                search(mSearch.getText().toString().trim(), true);
                return true;
            }
        });
        mSearch.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                showResults();
            }
        });
        mClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mSearch.setText("");
                mSearch.requestFocus();
                keyboard(true);
            }
        });
        findViewById(R.id.locate).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                locate();
            }
        });
        findViewById(R.id.attribution).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(AtlasActivity.this)
                        .setTitle(R.string.credits_title)
                        .setMessage(R.string.credits)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
        for (int i = 0; i < mModeChips.length; i++) {
            final int mode = i;
            mModeChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setMode(mode);
                }
            });
        }
        mPrimary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                primary();
            }
        });
        mSecondary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                secondary();
            }
        });

        setState(BROWSE);
        handleIntent(getIntent());
        if (!hasLocationPermission() && !mPrefs.getBoolean("asked_location", false)) {
            mPrefs.edit().putBoolean("asked_location", true).apply();
            requestLocationPermission();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startLocation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // navigation goes on in the background (NavService keeps the app alive)
        if (mState != NAV) stopLocation();
        mPrefs.edit().putString("lat", Double.toString(mMap.centerLat()))
                .putString("lon", Double.toString(mMap.centerLon()))
                .putString("zoom", Double.toString(mMap.zoom())).apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLocation();
        NavService.stop(this);
        mHandler.removeCallbacksAndMessages(null);
        mNet.shutdownNow();
        mMap.release();
        if (mTts != null) mTts.shutdown();
    }

    @Override
    public void onBackPressed() {
        if (mResults.getVisibility() == View.VISIBLE) {
            mSearch.clearFocus();
            keyboard(false);
        } else if (mState == NAV) {
            endNavigation();
        } else if (mState == ROUTE) {
            clearRoute();
            setState(PLACE);
        } else if (mState == PLACE) {
            mPlace = null;
            mMap.clearPin();
            setState(BROWSE);
        } else {
            super.onBackPressed();
        }
    }

    // ---- states

    private void setState(int state) {
        mState = state;
        mSearchBar.setVisibility(state == NAV ? View.GONE : View.VISIBLE);
        mNavBar.setVisibility(state == NAV ? View.VISIBLE : View.GONE);
        mSheet.setVisibility(state == BROWSE ? View.GONE : View.VISIBLE);
        mModes.setVisibility(state == ROUTE ? View.VISIBLE : View.GONE);
        mSecondary.setVisibility(View.VISIBLE);
        mPrimary.setEnabled(true);
        mPrimary.setAlpha(1f);
        if (state == NAV) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        switch (state) {
            case PLACE:
                mTitle.setText(mPlace.name);
                String detail = mPlace.detail;
                if (mLocation != null) {
                    final String d = Geo.formatDistance(Geo.distance(mLocation.getLatitude(),
                            mLocation.getLongitude(), mPlace.lat, mPlace.lon), mMetric);
                    detail = detail.isEmpty() ? d : detail + " · " + d;
                }
                mDetail.setText(detail);
                mSecondary.setText(R.string.share);
                mPrimary.setText(R.string.directions);
                break;
            case ROUTE:
                for (int i = 0; i < mModeChips.length; i++) mModeChips[i].setSelected(i == mMode);
                if (mRoute != null) {
                    mTitle.setText(summary(mRoute.duration, mRoute.distance));
                    mDetail.setText(getString(R.string.arrive_at, eta(mRoute.duration)) + " · "
                            + mPlace.name);
                } else {
                    mTitle.setText(mRoutePending ? R.string.finding_location
                            : R.string.finding_route);
                    mDetail.setText(mPlace.name);
                }
                mPrimary.setEnabled(mRoute != null);
                // the accent button has no disabled look of its own
                mPrimary.setAlpha(mRoute != null ? 1f : 0.4f);
                mSecondary.setText(R.string.close);
                mPrimary.setText(R.string.start);
                break;
            case NAV:
                mSecondary.setText(R.string.overview);
                mPrimary.setText(R.string.end);
                break;
        }
    }

    private void primary() {
        switch (mState) {
            case PLACE: directions(); break;
            case ROUTE: if (mRoute != null) startNavigation(); break;
            case NAV: endNavigation(); break;
        }
    }

    private void secondary() {
        switch (mState) {
            case PLACE: share(); break;
            case ROUTE: clearRoute(); setState(PLACE); break;
            case NAV:
                mFollow = false;
                mMap.fit(mNav.route.points);
                break;
        }
    }

    // ---- search and places

    private final Runnable mSearchSoon = new Runnable() {
        @Override
        public void run() {
            search(mSearch.getText().toString().trim(), false);
        }
    };

    private void search(final String query, final boolean explicit) {
        if (query.isEmpty()) return;
        final int gen = ++mSearchGen;
        final double lat = mMap.centerLat(), lon = mMap.centerLon();
        mNet.execute(new Runnable() {
            @Override
            public void run() {
                List<Places.Place> found = null;
                Exception error = null;
                try {
                    found = Places.search(query, lat, lon, 8);
                } catch (Exception e) {
                    error = e;
                    android.util.Log.i("Atlas", "request failed: " + e);
                }
                final List<Places.Place> result = found;
                final Exception err = error;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mSearchGen || isDestroyed()) return;
                        mFound.clear();
                        if (result != null) mFound.addAll(result);
                        showResults();
                        if (explicit) {
                            if (err != null) toast(err instanceof IOException ? R.string.offline
                                    : R.string.search_failed);
                            else if (mFound.isEmpty()) toast(R.string.no_results);
                            // one match, or a geo: link (the field is not being typed in):
                            // straight to the best match
                            else if (mFound.size() == 1 || !mSearch.hasFocus()) {
                                showPlace(mFound.get(0), true);
                            }
                        }
                    }
                });
            }
        });
    }

    private void showResults() {
        mAdapter.notifyDataSetChanged();
        mResults.setVisibility(!mFound.isEmpty() && mSearch.hasFocus() && mState != NAV
                ? View.VISIBLE : View.GONE);
    }

    private void showPlace(Places.Place p, boolean move) {
        if (mState == NAV) endNavigation();
        clearRoute();
        mPlace = p;
        mSearch.clearFocus();
        keyboard(false);
        mCenterOnFix = false;
        mMap.setPin(p.lat, p.lon);
        if (move) mMap.animateTo(p.lat, p.lon, Math.max(mMap.zoom(), 16));
        setState(PLACE);
    }

    /** a pin dropped by hand: its address comes from Photon */
    private void dropPin(final double lat, final double lon) {
        final Places.Place p = new Places.Place();
        p.lat = lat;
        p.lon = lon;
        p.name = getString(R.string.dropped_pin);
        p.detail = String.format(Locale.US, "%.5f, %.5f", lat, lon);
        showPlace(p, false);
        mNet.execute(new Runnable() {
            @Override
            public void run() {
                Places.Place found = null;
                try {
                    found = Places.reverse(lat, lon);
                } catch (Exception e) {
                    // offline: the coordinates stay
                }
                final Places.Place r = found;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (r == null || mPlace != p || isDestroyed()) return;
                        p.name = r.name;
                        p.detail = r.detail;
                        if (mState == PLACE) setState(PLACE);
                    }
                });
            }
        });
    }

    private void share() {
        final String url = String.format(Locale.US,
                "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f",
                mPlace.lat, mPlace.lon, mPlace.lat, mPlace.lon);
        final Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, mPlace.name + "\n" + url);
        startActivity(Intent.createChooser(send, getString(R.string.share)));
    }

    /** geo: links from other apps (and kAI): geo:lat,lon, geo:lat,lon?z=, geo:0,0?q= */
    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        final Uri u = intent.getData();
        if (u == null || !"geo".equals(u.getScheme())) return;
        final String enc = u.getEncodedSchemeSpecificPart();
        if (enc == null) return;
        final int qi = enc.indexOf('?');
        String coords = Uri.decode(qi < 0 ? enc : enc.substring(0, qi));
        final int semi = coords.indexOf(';');
        if (semi >= 0) coords = coords.substring(0, semi);
        String query = null;
        double zoom = 16;
        if (qi >= 0) {
            for (String kv : enc.substring(qi + 1).split("&")) {
                final int e = kv.indexOf('=');
                if (e < 0) continue;
                final String v = Uri.decode(kv.substring(e + 1).replace('+', ' '));
                if ("q".equals(kv.substring(0, e))) query = v.trim();
                if ("z".equals(kv.substring(0, e))) zoom = parse(v, zoom);
            }
        }
        double lat = Double.NaN, lon = Double.NaN;
        final String[] c = coords.split(",");
        if (c.length >= 2) {
            lat = parse(c[0], Double.NaN);
            lon = parse(c[1], Double.NaN);
        }
        final boolean hasPoint = !Double.isNaN(lat) && !Double.isNaN(lon)
                && !(lat == 0 && lon == 0);
        if (query != null && !query.isEmpty()) {
            // "lat,lon(label)"
            final Matcher m = Pattern.compile(
                    "^(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*(?:\\((.*)\\))?$").matcher(query);
            if (m.matches()) {
                final Places.Place p = new Places.Place();
                p.lat = parse(m.group(1), 0);
                p.lon = parse(m.group(2), 0);
                p.name = m.group(3) != null && !m.group(3).isEmpty() ? m.group(3)
                        : getString(R.string.dropped_pin);
                p.detail = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon);
                showPlace(p, true);
                return;
            }
            if (hasPoint) mMap.setCamera(lat, lon, 13);
            mQuiet = true;
            mSearch.setText(query);
            mQuiet = false;
            mCenterOnFix = false;
            search(query, true);
            return;
        }
        if (hasPoint) {
            mMap.setCamera(lat, lon, zoom);
            dropPin(lat, lon);
        }
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- routes

    private void setMode(int mode) {
        if (mode == mMode) return;
        mMode = mode;
        mPrefs.edit().putInt("mode", mode).apply();
        clearRoute();
        directions();
    }

    private void directions() {
        mRoute = null;
        if (!hasLocationPermission()) {
            mRoutePending = true;
            setState(ROUTE);
            requestLocationPermission();
            return;
        }
        if (!locationOn()) {
            toast(R.string.need_location);
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        startLocation();
        if (mLocation == null) {
            mRoutePending = true;
            setState(ROUTE);
            return;
        }
        mRoutePending = false;
        setState(ROUTE);
        route(mLocation.getLatitude(), mLocation.getLongitude(), false);
    }

    private void route(final double lat, final double lon, final boolean reroute) {
        final int gen = ++mRouteGen;
        final Places.Place to = mPlace;
        final int mode = mMode;
        mRouteBusy = true;
        mNet.execute(new Runnable() {
            @Override
            public void run() {
                Router.Route found = null;
                Exception error = null;
                try {
                    found = Router.route(AtlasActivity.this, mode, lat, lon, to.lat, to.lon);
                } catch (Exception e) {
                    error = e;
                    android.util.Log.i("Atlas", "request failed: " + e);
                }
                final Router.Route r = found;
                final Exception err = error;
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mRouteGen || to != mPlace || isDestroyed()) return;
                        mRouteBusy = false;
                        if (r == null) {
                            if (reroute) return; // the old route stays; the next fix retries
                            toast(err instanceof IOException ? R.string.offline
                                    : R.string.route_failed);
                            setState(PLACE);
                            return;
                        }
                        mRoute = r;
                        mMap.setRoute(r.points);
                        if (mState == NAV) {
                            mNav = new Navigator(r);
                            mSpokenStep = -1;
                            if (mLocation != null) navigate(mLocation);
                        } else {
                            setState(ROUTE);
                            mMap.fit(r.points);
                        }
                    }
                });
            }
        });
    }

    private void clearRoute() {
        mRouteGen++;
        mRouteBusy = false;
        mRoutePending = false;
        mRoute = null;
        mMap.setRoute(null);
    }

    // ---- navigation

    private void startNavigation() {
        mNav = new Navigator(mRoute);
        mFollow = true;
        mSpokenStep = -1;
        mSpokenLevel = 0;
        mOffRouteFixes = 0;
        mSearch.clearFocus();
        keyboard(false);
        setState(NAV);
        // until the first fix: the route as planned
        final List<Router.Step> steps = mRoute.steps;
        mNavDistance.setText(steps.isEmpty() ? ""
                : Geo.formatDistance(steps.get(0).distance, mMetric));
        mNavText.setText(steps.size() > 1 ? steps.get(1).text : "");
        mTitle.setText(summary(mRoute.duration, mRoute.distance));
        mDetail.setText(getString(R.string.arrive_at, eta(mRoute.duration)) + " · " + mPlace.name);
        NavService.start(this, mPlace.name);
        if (!steps.isEmpty()) speak(steps.get(0).text);
        if (mLocation != null) navigate(mLocation);
    }

    private void endNavigation() {
        NavService.stop(this);
        mNav = null;
        mFollow = false;
        if (mTts != null) mTts.stop();
        setState(mRoute != null ? ROUTE : PLACE);
        if (mRoute != null) mMap.fit(mRoute.points);
    }

    private void navigate(Location l) {
        final Navigator.Progress p = mNav.update(l.getLatitude(), l.getLongitude());
        if (mFollow) mMap.animateTo(l.getLatitude(), l.getLongitude(), NAV_ZOOM[mMode]);

        // arrived
        if (p.remaining < (mMode == Router.CAR ? 30 : 15)) {
            speak(getString(R.string.nav_arrive));
            NavService.stop(this);
            mNav = null;
            clearRoute();
            setState(PLACE);
            return;
        }

        // off the route: a new one from here, at most every 10 s
        final double limit = OFF_ROUTE[mMode] + Math.min(l.getAccuracy(), 50);
        if (p.offRoute > limit) {
            final long now = SystemClock.elapsedRealtime();
            if (++mOffRouteFixes >= 3 && !mRouteBusy && now - mLastReroute > 10000) {
                mLastReroute = now;
                mOffRouteFixes = 0;
                speak(getString(R.string.rerouting));
                route(l.getLatitude(), l.getLongitude(), true);
            }
        } else {
            mOffRouteFixes = 0;
        }

        final List<Router.Step> steps = mNav.route.steps;
        final String text = p.step >= 0 ? steps.get(p.step).text : getString(R.string.nav_arrive);
        mNavDistance.setText(Geo.formatDistance(p.toStep, mMetric));
        mNavText.setText(text);
        final double left = mNav.route.duration * p.remaining / Math.max(1, mNav.length());
        mTitle.setText(summary(left, p.remaining));
        mDetail.setText(getString(R.string.arrive_at, eta(left)) + " · " + mPlace.name);

        // voice: once early ("In 800 m, turn left"), once at the maneuver; the arrival is said
        // when there (above), not 150 m before
        if (p.step != mSpokenStep) {
            mSpokenStep = p.step;
            mSpokenLevel = 0;
        }
        if (p.step < 0 || p.step == steps.size() - 1) return;
        if (mSpokenLevel < 2 && p.toStep <= NEAR[mMode]) {
            mSpokenLevel = 2;
            speak(text);
        } else if (mSpokenLevel < 1 && p.toStep <= FAR[mMode] && p.toStep > NEAR[mMode] * 2) {
            mSpokenLevel = 1;
            final double rounded = p.toStep >= 200 ? Math.round(p.toStep / 50) * 50 : p.toStep;
            speak(getString(R.string.nav_in, Geo.formatDistance(rounded, mMetric), text));
        }
    }

    private void speak(String text) {
        if (android.util.Log.isLoggable("Atlas", android.util.Log.DEBUG)) {
            android.util.Log.d("Atlas", "say: " + text);
        }
        if (mTts == null) {
            mPendingSpeech.add(text);
            mTts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    mTtsReady = status == TextToSpeech.SUCCESS;
                    if (!mTtsReady) {
                        mPendingSpeech.clear();
                        return;
                    }
                    mTts.setLanguage(Locale.getDefault());
                    mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String id) {
                        }

                        @Override
                        public void onDone(String id) {
                            mAudio.abandonAudioFocus(null);
                        }

                        @Override
                        public void onError(String id) {
                            mAudio.abandonAudioFocus(null);
                        }
                    });
                    // what was said while the engine started ("Head out", then the first
                    // turn), in order
                    for (String s : mPendingSpeech) say(s);
                    mPendingSpeech.clear();
                }
            });
            return;
        }
        if (!mTtsReady) {
            mPendingSpeech.add(text);
            return;
        }
        say(text);
    }

    private void say(String text) {
        // the music ducks while Atlas speaks
        mAudio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        final Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
        mTts.speak(text, TextToSpeech.QUEUE_ADD, params, "atlas" + (++mUtterance));
    }

    private String summary(double seconds, double meters) {
        final long min = Math.max(1, Math.round(seconds / 60));
        final String time = min < 60 ? getString(R.string.dur_min, min)
                : getString(R.string.dur_hours, min / 60, min % 60);
        return time + " · " + Geo.formatDistance(meters, mMetric);
    }

    private String eta(double seconds) {
        return DateFormat.getTimeFormat(this)
                .format(new Date(System.currentTimeMillis() + (long) (seconds * 1000)));
    }

    // ---- location

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            startLocation();
            if (mRoutePending && mState == ROUTE) directions();
        } else {
            if (mState == ROUTE) setState(PLACE);
            mRoutePending = false;
            toast(R.string.location_denied);
        }
    }

    private boolean locationOn() {
        try {
            return mLocations.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || mLocations.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private void startLocation() {
        if (mListening || !hasLocationPermission()) return;
        for (String provider : new String[] {LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER}) {
            try {
                if (mLocations.getProvider(provider) == null) continue;
                // a disabled provider is listened to as well: its fixes come once it is on
                mLocations.requestLocationUpdates(provider, 1000, 0, this);
                mListening = true;
                final Location last = mLocations.getLastKnownLocation(provider);
                if (last != null && System.currentTimeMillis() - last.getTime() < 10 * 60000
                        && (mLocation == null || last.getTime() > mLocation.getTime())) {
                    onLocationChanged(last);
                }
            } catch (SecurityException | IllegalArgumentException e) {
                // no such provider on this phone
            }
        }
    }

    private void stopLocation() {
        if (!mListening) return;
        mLocations.removeUpdates(this);
        mListening = false;
    }

    private void locate() {
        if (mState == NAV) {
            mFollow = true;
            if (mLocation != null) {
                mMap.animateTo(mLocation.getLatitude(), mLocation.getLongitude(),
                        NAV_ZOOM[mMode]);
            }
            return;
        }
        if (mLocation != null) {
            mMap.animateTo(mLocation.getLatitude(), mLocation.getLongitude(),
                    Math.max(mMap.zoom(), 16));
            return;
        }
        mCenterOnFix = true;
        if (!hasLocationPermission()) {
            requestLocationPermission();
        } else if (!locationOn()) {
            toast(R.string.need_location);
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else {
            startLocation();
            toast(R.string.finding_location);
        }
    }

    @Override
    public void onLocationChanged(Location l) {
        // a network fix is coarser: it waits while GPS has a recent one
        if (mLocation != null && LocationManager.NETWORK_PROVIDER.equals(l.getProvider())
                && LocationManager.GPS_PROVIDER.equals(mLocation.getProvider())
                && l.getTime() - mLocation.getTime() < 15000) {
            return;
        }
        final boolean first = mLocation == null;
        mLocation = l;
        mMap.setLocation(l.getLatitude(), l.getLongitude(), l.getAccuracy());
        if (mState == NAV && mNav != null) {
            navigate(l);
        } else if (mRoutePending && mState == ROUTE) {
            mRoutePending = false;
            route(l.getLatitude(), l.getLongitude(), false);
            setState(ROUTE);
        } else if (mCenterOnFix && (first || mState == BROWSE)) {
            mCenterOnFix = false;
            mMap.animateTo(l.getLatitude(), l.getLongitude(), Math.max(mMap.zoom(), 15));
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    // ---- map

    @Override
    public void onUserMoved() {
        mFollow = false;
        mCenterOnFix = false;
    }

    @Override
    public void onLongPress(double lat, double lon) {
        if (mState == NAV) return;
        dropPin(lat, lon);
    }

    @Override
    public void onTap(double lat, double lon) {
        if (mSearch.hasFocus()) {
            mSearch.clearFocus();
            keyboard(false);
        }
    }

    // ---- helpers

    private void keyboard(boolean show) {
        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (show) imm.showSoftInput(mSearch, 0);
        else imm.hideSoftInputFromWindow(mSearch.getWindowToken(), 0);
    }

    private void toast(int text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private final class PlaceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mFound.size();
        }

        @Override
        public Object getItem(int position) {
            return mFound.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(AtlasActivity.this)
                        .inflate(R.layout.item_place, parent, false);
            }
            final Places.Place p = mFound.get(position);
            ((TextView) view.findViewById(R.id.name)).setText(p.name);
            final TextView detail = (TextView) view.findViewById(R.id.place_detail);
            detail.setText(p.detail);
            detail.setVisibility(p.detail.isEmpty() ? View.GONE : View.VISIBLE);
            return view;
        }
    }
}
