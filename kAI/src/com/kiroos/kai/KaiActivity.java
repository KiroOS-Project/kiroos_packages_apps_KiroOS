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

package com.kiroos.kai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * kAI: chat with the Keng 1.5 models running on the phone (LocalEngine), with optional web
 * search. Answers stream in and are rendered as Markdown. The conversation is kept on the
 * phone (ChatStore), shared with the overlay that holding Power opens.
 */
public class KaiActivity extends Activity {

    /** starts the sources line under an answer that used the web */
    static final String SOURCES_MARK = "\n\n🔎 ";
    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]+)\\]\\((https?://[^)\\s]+)\\)");
    /** Ollama's port; over USB "adb reverse tcp:11434 tcp:11434" maps it to the PC */
    /** with EXTRA_TEXT: ask it right away (the home's kAI field, KaiQuickAskActivity) */
    static final String EXTRA_SEND = "com.kiroos.kai.extra.SEND";

    private static final int DEFAULT_PORT = 11434;
    /** first character of R.string.error_answer in every language */
    private static final String ERROR_MARK = "⚠";

    private final Handler mHandler = new Handler();
    private final List<BridgeClient.Message> mMessages = new ArrayList<>();
    private final Adapter mAdapter = new Adapter();
    private SharedPreferences mPrefs;
    private Markdown mMarkdown;
    private ListView mList;
    private EditText mInput;
    private ImageButton mSend;
    private TextView mStatus;
    private View mEmpty;
    private BridgeClient mClient;
    private boolean mBusy;
    private boolean mResumed;
    private Voice mVoice;
    private ImageButton mMic;
    /** the question came by voice: the answer is read aloud */
    private boolean mSpokenQuestion;
    private java.util.Locale mSpokenLang;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kai);
        mPrefs = getSharedPreferences("kai", MODE_PRIVATE);
        LocalEngine.init(getApplicationContext());
        LocalEngine.select(mPrefs.getString("local_model", "base"));
        mMarkdown = new Markdown(getColor(R.color.code_bg), getColor(R.color.code_fg),
                getColor(R.color.kiro_accent), getColor(R.color.kiro_text_secondary));
        mList = (ListView) findViewById(R.id.list);
        mInput = (EditText) findViewById(R.id.input);
        mSend = (ImageButton) findViewById(R.id.btn_send);
        mStatus = (TextView) findViewById(R.id.status);
        mEmpty = findViewById(R.id.empty);
        mList.setAdapter(mAdapter);
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final BridgeClient.Message m = mAdapter.getItem(position);
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("kAI", m.content));
                Toast.makeText(KaiActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                showSources(mAdapter.getItem(position));
            }
        });
        final ImageButton webButton = (ImageButton) findViewById(R.id.btn_web);
        updateWebButton(webButton);
        webButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final boolean on = !mPrefs.getBoolean("web", true);
                mPrefs.edit().putBoolean("web", on).apply();
                updateWebButton(webButton);
                Toast.makeText(KaiActivity.this, on ? R.string.web_on : R.string.web_off,
                        Toast.LENGTH_SHORT).show();
            }
        });
        mSend.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        mSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mBusy) {
                    if (mClient != null) {
                        mClient.cancel();
                    }
                } else {
                    send();
                }
            }
        });
        KiroTouch.apply(mSend);
        mVoice = new Voice(this);
        mMic = (ImageButton) findViewById(R.id.btn_mic);
        mMic.setVisibility(Voice.available() ? View.VISIBLE : View.GONE);
        mMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVoice();
            }
        });
        KiroTouch.apply(mMic);
        findViewById(R.id.btn_new).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBusy) {
                    // the chat is not lost: it goes into the history
                    ChatStore.archive(KaiActivity.this, mMessages);
                    mMessages.clear();
                    save();
                    refresh();
                }
            }
        });
        findViewById(R.id.btn_history).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBusy) {
                    showHistory();
                }
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBusy) {
                    chooseModel();
                }
            }
        });
        mStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkBridge();
            }
        });
        load();
        refresh();
        handleIntent(getIntent());
    }

    /*
     * kAI is singleTask: when it is already open (behind the home), the kAI bar's microphone
     * and the home's text field reach it here, not in onCreate. Before Beta 4 they only
     * brought it to the front, and it did not listen.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent.getBooleanExtra("from_power_key", false) && Voice.available()) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mVoice.recording()) {
                        toggleVoice();
                    }
                }
            }, 350);
        }
        // shared text ("Share → kAI") becomes the prompt; from the home's field it is asked
        final String shared = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null) {
            mInput.setText(shared);
            mInput.setSelection(shared.length());
            if (intent.getBooleanExtra(EXTRA_SEND, false)) {
                // after onResume, which reloads the chat from disk
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!mBusy) {
                            send();
                        }
                    }
                });
            }
        }
        // done once: not again when the activity is recreated (rotation, theme)
        intent.removeExtra("from_power_key");
        intent.removeExtra(Intent.EXTRA_TEXT);
        intent.removeExtra(EXTRA_SEND);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mHandler.removeCallbacks(mStopLocal);
        LocalEngine.keep();
        // questions asked from the overlay (hold Power) were added to the chat on disk
        if (!mBusy) {
            mMessages.clear();
            load();
            refresh();
        }
        checkBridge();
    }

    @Override
    protected void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mRetry);
        // give the model's memory back a few minutes after leaving kAI
        mHandler.removeCallbacks(mStopLocal);
        mHandler.postDelayed(mStopLocal, 3 * 60 * 1000);
        super.onPause();
    }

    private final Runnable mStopLocal = new Runnable() {
        @Override
        public void run() {
            if (!mResumed && !mBusy) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        LocalEngine.stop();
                    }
                }, "kAI-stop").start();
            }
        }
    };

    private final Runnable mRetry = new Runnable() {
        @Override
        public void run() {
            if (mResumed && !mBusy) {
                checkBridge();
            }
        }
    };

    private BridgeClient client() {
        return new BridgeClient(mPrefs.getString("host", "127.0.0.1"),
                mPrefs.getInt("port", DEFAULT_PORT));
    }

    /** who answers: a model on the PC (faster, bigger) when reachable, else the phone's own */
    private static final class Target {
        final BridgeClient client;
        final String model;
        final boolean local;

        Target(BridgeClient client, String model, boolean local) {
            this.client = client;
            this.model = model;
            this.local = local;
        }
    }

    /**
     * Blocking (worker thread). The PC first (USB tunnel or LAN address from the settings),
     * then the on-device model, started if needed. Throws NoModelException / Exception
     * only when neither can answer.
     */
    private Target resolve(boolean startLocal) throws Exception {
        // KiroOS 2.1: kAI runs on the phone only, no PC and no cloud
        if (!LocalEngine.available()) {
            throw new BridgeClient.NoModelException();
        }
        if (!startLocal || LocalEngine.ensureRunning()) {
            return new Target(BridgeClient.local(), LocalEngine.modelName(), true);
        }
        throw new Exception("loading");
    }

    private void checkBridge() {
        mStatus.setText(R.string.connecting);
        mStatus.setTextColor(getColor(R.color.kiro_text_secondary));
        new AsyncTask<Void, Void, Target>() {
            private boolean mNoModel;
            private boolean mLoading;

            @Override
            protected Target doInBackground(Void... params) {
                try {
                    // warm the on-device model up now, so the first answer is quick
                    return resolve(true);
                } catch (BridgeClient.NoModelException e) {
                    mNoModel = true;
                    return null;
                } catch (Exception e) {
                    mLoading = LocalEngine.loading();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Target t) {
                if (isDestroyed()) {
                    return;
                }
                if (t != null) {
                    mStatus.setText(getString(t.local ? R.string.on_device : R.string.connected,
                            t.model.isEmpty() ? getString(R.string.default_model) : t.model));
                    mStatus.setTextColor(getColor(R.color.kiro_positive));
                } else if (mLoading) {
                    // the phone's own model is still loading: not an error, look again soon
                    mStatus.setText(R.string.model_loading);
                    mStatus.setTextColor(getColor(R.color.kiro_text_secondary));
                    mHandler.removeCallbacks(mRetry);
                    if (mResumed) {
                        mHandler.postDelayed(mRetry, 1000);
                    }
                } else {
                    mStatus.setText(mNoModel ? R.string.no_model : R.string.not_connected);
                    mStatus.setTextColor(getColor(R.color.kiro_negative));
                    // keep trying while the app is open (the model may still be starting)
                    mHandler.removeCallbacks(mRetry);
                    if (mResumed) {
                        mHandler.postDelayed(mRetry, 4000);
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void send() {
        final String text = mInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        mInput.setText("");
        mMessages.add(new BridgeClient.Message("user", text));
        String done = PhoneActions.handle(this, text);
        if (done == null) {
            done = QuickMath.answer(text);   // exact arithmetic, instantly
        }
        if (done != null) {
            if (mSpokenQuestion) {
                mVoice.speak(done, mSpokenLang);
                mSpokenQuestion = false;
            }
            mMessages.add(new BridgeClient.Message("assistant", done));
            refresh();
            save();
            return;
        }
        final BridgeClient.Message answer = new BridgeClient.Message("assistant", "");
        mMessages.add(answer);
        final boolean web = mPrefs.getBoolean("web", true) && online() && WebSearch.wanted(text);
        if (web) {
            answer.status = getString(R.string.searching);
        }
        refresh();
        setBusy(true);
        // the model's instructions in the language of the question (a small model follows
        // them better): the phone may be in English while the user writes in Italian
        final Locale lang = WebSearch.looksItalian(text) ? Locale.ITALIAN
                : getResources().getConfiguration().getLocales().get(0);
        final Configuration conf = new Configuration(getResources().getConfiguration());
        conf.setLocale(lang);
        final android.content.Context pc = createConfigurationContext(conf);
        final List<BridgeClient.Message> history = new ArrayList<>();
        history.add(new BridgeClient.Message("system", systemPrompt(pc, lang)));
        // the last 20 turns keep context without overflowing small local models (fewer when
        // web results take room)
        final int from = Math.max(0, mMessages.size() - 1 - (web ? 8 : 20));
        String previousQuestion = null;
        for (int i = from; i < mMessages.size() - 1; i++) {
            final BridgeClient.Message m = mMessages.get(i);
            // failed turns are not conversation: leave out the error and its question
            if (isError(m) || (i + 1 < mMessages.size() - 1 && isError(mMessages.get(i + 1)))) {
                continue;
            }
            if ("user".equals(m.role) && i < mMessages.size() - 2) {
                previousQuestion = m.content;
            }
            // the sources line is for the reader, not for the model
            final int cut = m.content.indexOf(SOURCES_MARK);
            history.add(cut < 0 ? m : new BridgeClient.Message(m.role, m.content.substring(0, cut)));
        }
        // a short follow-up ("and when?") is searched together with the question before it
        final String query = previousQuestion != null && text.split("\\s+").length < 5
                ? previousQuestion + " " + text : text;
        // a spoken question gets its answer from the voice, Flash-Lite Live
        final boolean spoken = mSpokenQuestion;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // the PC if it is there, else the phone's own model
                final Target t;
                try {
                    t = resolve(true);
                } catch (BridgeClient.NoModelException e) {
                    postError(answer, getString(R.string.no_model));
                    return;
                } catch (Exception e) {
                    postError(answer, getString(LocalEngine.loading()
                            ? R.string.model_loading : R.string.not_connected));
                    return;
                }
                mClient = t.client;
                if (t.local) {
                    // the phone reads ~25 tokens/s: the last three exchanges are enough context
                    while (history.size() > 7) {
                        history.remove(1);
                    }
                }
                List<WebSearch.Result> results = new ArrayList<>();
                if (web) {
                    results = WebSearch.search(query.length() > 200 ? query.substring(0, 200)
                            : query, 4, lang);
                    if (!results.isEmpty()) {
                        // the phone's own model reads ~25 tokens per second and a small model
                        // gets lost in long pages: it gets the result snippets only, the PC
                        // also the best paragraphs of the first page
                        String excerpt = "";
                        if (!t.local) {
                            postStatus(answer, getString(R.string.reading,
                                    results.get(0).site()));
                            excerpt = WebSearch.excerpt(results.get(0).url, query, 1500);
                            if (excerpt.isEmpty() && results.size() > 1) {
                                excerpt = WebSearch.excerpt(results.get(1).url, query, 1500);
                            }
                            history.get(0).content += " "
                                    + pc.getString(R.string.web_instruction);
                        } else {
                            // the 0.5B model echoes what it sees: earlier answers and today's
                            // date end up as the answer (measured). It gets the results and the
                            // question alone.
                            while (history.size() > 2) {
                                history.remove(1);
                            }
                            history.set(0, new BridgeClient.Message("system",
                                    pc.getString(R.string.web_system)));
                        }
                        // the model sees the results with the question; the chat shows the
                        // question alone
                        history.set(history.size() - 1, new BridgeClient.Message("user",
                                WebSearch.prompt(text, results, excerpt,
                                        pc.getString(R.string.web_results),
                                        pc.getString(R.string.question))));
                        postStatus(answer, getString(R.string.writing));
                    } else {
                        postStatus(answer, null);
                    }
                }
                final List<WebSearch.Result> sources = results;
                final BridgeClient.Stream stream = new BridgeClient.Stream() {
                    private long mLastUi;

                    @Override
                    public void onDelta(final String delta) {
                        synchronized (answer) {
                            answer.content += delta;
                        }
                        final long now = System.currentTimeMillis();
                        if (now - mLastUi > 60) { // ~16 fps of re-rendering is plenty
                            mLastUi = now;
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mAdapter.notifyDataSetChanged();
                                    mList.setSelection(mAdapter.getCount() - 1);
                                }
                            });
                        }
                    }

                    @Override
                    public void onDone() {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mSpokenQuestion && !answer.content.isEmpty()) {
                                    mVoice.speak(answer.content, mSpokenLang);
                                }
                                mSpokenQuestion = false;
                                if (answer.content.isEmpty()) {
                                    mMessages.remove(answer);
                                } else if (!sources.isEmpty()) {
                                    answer.content = answer.content.trim()
                                            + WebSearch.sources(sources);
                                }
                                setBusy(false);
                                refresh();
                                save();
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        postError(answer, message);
                    }
                };
                if (t.local && spoken && LocalEngine.LIVE.installed()) {
                    LocalEngine.generate(LocalEngine.LIVE, history, stream);
                } else {
                    t.client.chat(t.model, history, stream);
                }
            }
        }, "kAI-chat").start();
    }

    /**
     * The system prompt in the answer language, naming the model kAI runs on: every
     * translation says "Samsung Galaxy S6", which becomes the S6 edge / edge+ on those phones.
     */
    static String systemPrompt(android.content.Context c, java.util.Locale lang) {
        final String prompt = c.getString(R.string.system_prompt, WebSearch.today(lang));
        final String model = android.os.Build.MODEL;
        return model.startsWith("Galaxy S6 ")
                ? prompt.replace("Samsung Galaxy S6", "Samsung " + model) : prompt;
    }

    /** the placeholder of an answer that has no text yet (null: the plain "…") */
    private void postStatus(final BridgeClient.Message answer, final String status) {
        answer.status = status;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAdapter.notifyDataSetChanged();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        final android.net.NetworkInfo n = ((android.net.ConnectivityManager) getSystemService(
                CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    /** tapping an answer with sources lists them; one opens in the browser */
    private void showSources(BridgeClient.Message m) {
        final int at = m.content.indexOf(SOURCES_MARK);
        if (at < 0) {
            return;
        }
        final Matcher lm = LINK.matcher(m.content.substring(at));
        final List<String> names = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        while (lm.find()) {
            names.add(lm.group(1));
            urls.add(lm.group(2));
        }
        if (urls.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.sources)
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(urls.get(which))));
                        } catch (android.content.ActivityNotFoundException e) {
                            // no browser
                        }
                    }
                })
                .show();
    }

    private void updateWebButton(ImageButton b) {
        final boolean on = mPrefs.getBoolean("web", true);
        b.setAlpha(on ? 1f : 0.35f);
        b.setContentDescription(getString(on ? R.string.web_on : R.string.web_off));
    }

    /** An answer bubble that only says why there was no answer. */
    private boolean isError(BridgeClient.Message m) {
        return "assistant".equals(m.role) && m.content.startsWith(ERROR_MARK);
    }

    /** Shows why there is no answer in the answer bubble (from any thread). */
    private void postError(final BridgeClient.Message answer, final String message) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (answer.content.isEmpty()) {
                    answer.content = getString(R.string.error_answer, message);
                }
                setBusy(false);
                refresh();
                save();
                checkBridge();
            }
        });
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        mSend.setContentDescription(getString(busy ? R.string.stop : R.string.send));
    }

    private void refresh() {
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mMessages.isEmpty() ? View.VISIBLE : View.GONE);
        if (!mMessages.isEmpty()) {
            mList.setSelection(mAdapter.getCount() - 1);
        }
    }

    // --- settings ------------------------------------------------------------------------------

    private void toggleVoice() {
        if (mBusy) {
            return;
        }
        if (mVoice.recording()) {
            mVoice.stop();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        mVoice.silence();
        final java.util.Locale lang = getResources().getConfiguration().getLocales().get(0);
        mMic.setColorFilter(getColor(R.color.kiro_accent));
        mInput.setHint(R.string.listening);
        mVoice.listen(lang, new Voice.Listener() {
            @Override
            public void onLevel(float level) {
                mMic.setScaleX(1f + 0.25f * level);
                mMic.setScaleY(1f + 0.25f * level);
            }

            @Override
            public void onTranscribing() {
                mMic.setScaleX(1f);
                mMic.setScaleY(1f);
                mInput.setHint(R.string.transcribing);
            }

            @Override
            public void onText(String text) {
                resetMic();
                if (!text.isEmpty()) {
                    mSpokenQuestion = true;
                    mSpokenLang = WebSearch.looksItalian(text) ? java.util.Locale.ITALIAN : lang;
                    mInput.setText(text);
                    send();
                }
            }

            @Override
            public void onError(String message) {
                resetMic();
                Toast.makeText(KaiActivity.this, R.string.voice_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void resetMic() {
        mMic.clearColorFilter();
        mMic.setScaleX(1f);
        mMic.setScaleY(1f);
        mInput.setHint(R.string.ask);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 7 && results.length > 0
                && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toggleVoice();
        }
    }

    /** Keng 1.5 Flash-Lite Live / Lite / Base / Pro, in a sheet from the bottom */
    private void chooseModel() {
        KaiModelSheet.show(this, new KaiModelSheet.Listener() {
            @Override
            public void onPicked(LocalEngine.Model m) {
                if (m == LocalEngine.selected()) {
                    return;
                }
                mPrefs.edit().putString("local_model", m.id).apply();
                LocalEngine.select(m.id);
                checkBridge();   // loads it now, the status line says when it is ready
            }
        });
    }

    private void showSettings() {
        final View v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        final EditText host = (EditText) v.findViewById(R.id.host);
        final EditText port = (EditText) v.findViewById(R.id.port);
        final EditText model = (EditText) v.findViewById(R.id.model);
        host.setText(mPrefs.getString("host", "127.0.0.1"));
        port.setText(String.valueOf(mPrefs.getInt("port", DEFAULT_PORT)));
        model.setText(mPrefs.getString("model", ""));
        new AlertDialog.Builder(this)
                .setTitle(R.string.bridge)
                .setView(v)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int p;
                        try {
                            p = Integer.parseInt(port.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            p = DEFAULT_PORT;
                        }
                        mPrefs.edit().putString("host", host.getText().toString().trim())
                                .putInt("port", p)
                                .putString("model", model.getText().toString().trim()).apply();
                        checkBridge();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // --- persistence ---------------------------------------------------------------------------

    /**
     * The earlier chats, newest first: a tap opens one (the current chat goes into the history
     * in its place), a long press deletes one, "Clear history" deletes them all.
     */
    private void showHistory() {
        final List<ChatStore.Past> past = ChatStore.history(this);
        if (past.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return past.size();
            }

            @Override
            public ChatStore.Past getItem(int position) {
                return past.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                final View v = convertView != null ? convertView : LayoutInflater.from(
                        parent.getContext()).inflate(android.R.layout.simple_list_item_2,
                        parent, false);
                final ChatStore.Past p = getItem(position);
                final TextView title = (TextView) v.findViewById(android.R.id.text1);
                title.setSingleLine(true);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setText(p.title.isEmpty() ? getString(R.string.history_untitled)
                        : p.title);
                ((TextView) v.findViewById(android.R.id.text2)).setText(
                        android.text.format.DateUtils.getRelativeDateTimeString(
                                KaiActivity.this, p.time,
                                android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                android.text.format.DateUtils.WEEK_IN_MILLIS, 0));
                return v;
            }
        };
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.history_title)
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final ChatStore.Past p = past.get(which);
                        final List<BridgeClient.Message> chat = ChatStore.open(p);
                        ChatStore.delete(p);
                        ChatStore.archive(KaiActivity.this, mMessages);
                        mMessages.clear();
                        mMessages.addAll(chat);
                        save();
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.history_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        new AlertDialog.Builder(KaiActivity.this)
                                .setMessage(R.string.history_clear_confirm)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.history_clear,
                                        new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface d2, int w) {
                                                ChatStore.clearHistory(KaiActivity.this);
                                            }
                                        })
                                .show();
                    }
                })
                .create();
        dialog.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position,
                    long id) {
                ChatStore.delete(past.remove(position));
                adapter.notifyDataSetChanged();
                Toast.makeText(KaiActivity.this, R.string.history_deleted,
                        Toast.LENGTH_SHORT).show();
                if (past.isEmpty()) {
                    dialog.dismiss();
                }
                return true;
            }
        });
        dialog.show();
    }

    private void save() {
        ChatStore.save(this, mMessages);
    }

    private void load() {
        mMessages.addAll(ChatStore.load(this));
    }

    // --- list -----------------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mMessages.size();
        }

        @Override
        public BridgeClient.Message getItem(int position) {
            return mMessages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    KaiActivity.this).inflate(R.layout.item_message, parent, false);
            final BridgeClient.Message m = mMessages.get(position);
            final boolean user = "user".equals(m.role);
            final TextView text = (TextView) v.findViewById(R.id.text);
            final LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) text.getLayoutParams();
            lp.gravity = user ? Gravity.END : Gravity.START;
            text.setLayoutParams(lp);
            text.setBackgroundResource(user ? R.drawable.bubble_user : R.drawable.bubble_ai);
            final String content;
            synchronized (m) {
                content = m.content;
            }
            if (user) {
                text.setText(content);
            } else if (content.isEmpty()) {
                final String status = m.status;
                text.setText(status != null ? status : "…");
            } else {
                text.setText(mMarkdown.render(content));
            }
            return v;
        }
    }
}
