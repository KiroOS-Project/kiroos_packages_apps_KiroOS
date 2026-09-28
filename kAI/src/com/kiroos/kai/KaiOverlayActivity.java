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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * kAI over the current app (hold the Power button): a card at the bottom of the screen with the
 * kAI aura glowing behind it. From the Power key it listens right away; the answer is
 * written in the card (read aloud when the question was spoken) and added to the kAI chat,
 * which "Continue in kAI" opens. Everything runs on the phone.
 */
public class KaiOverlayActivity extends Activity {

    private static final TimeInterpolator EASE = new PathInterpolator(0.2f, 0.9f, 0.1f, 1f);
    private static final TimeInterpolator EXIT = new PathInterpolator(0.4f, 0f, 0.9f, 0.6f);

    private final Handler mHandler = new Handler();
    private SharedPreferences mPrefs;
    private Markdown mMarkdown;
    private View mDim;
    private View mFrame;
    private KaiAuraView mAura;
    private View mVoiceRow;
    private KaiWaveView mWave;
    private TextView mVoiceLabel;
    private AnswerScroll mScroll;
    private TextView mQuestion;
    private TextView mAnswer;
    private EditText mInput;
    private ImageButton mMic;
    private ImageButton mSend;
    private View mFooter;
    private TextView mStatus;
    private Voice mVoice;
    private boolean mBusy;
    private boolean mClosing;
    private boolean mSpoken;
    private Locale mSpokenLang;
    private final StringBuilder mText = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kai_overlay);
        mPrefs = getSharedPreferences("kai", MODE_PRIVATE);
        LocalEngine.init(getApplicationContext());
        LocalEngine.select(mPrefs.getString("local_model", "base"));
        LocalEngine.sOverlayShowing = true;
        mMarkdown = new Markdown(getColor(R.color.code_bg), getColor(R.color.code_fg),
                getColor(R.color.kiro_accent), getColor(R.color.kiro_text_secondary));
        mDim = findViewById(R.id.dim);
        mFrame = findViewById(R.id.frame);
        mAura = (KaiAuraView) findViewById(R.id.aura);
        mVoiceRow = findViewById(R.id.voice_row);
        mWave = (KaiWaveView) findViewById(R.id.wave);
        mVoiceLabel = (TextView) findViewById(R.id.voice_label);
        mScroll = (AnswerScroll) findViewById(R.id.scroll);
        mQuestion = (TextView) findViewById(R.id.question);
        mAnswer = (TextView) findViewById(R.id.answer);
        mInput = (EditText) findViewById(R.id.input);
        mMic = (ImageButton) findViewById(R.id.btn_mic);
        mSend = (ImageButton) findViewById(R.id.btn_send);
        mFooter = findViewById(R.id.footer);
        mStatus = (TextView) findViewById(R.id.status);
        mVoice = new Voice(this);

        mDim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                close();
            }
        });
        mSend.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        mSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mBusy) {
                    LocalEngine.cancel();
                } else {
                    send();
                }
            }
        });
        KiroTouch.apply(mSend);
        mMic.setVisibility(Voice.available() ? View.VISIBLE : View.GONE);
        mMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVoice();
            }
        });
        KiroTouch.apply(mMic);
        mInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send();
                    return true;
                }
                return false;
            }
        });
        findViewById(R.id.open_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(KaiOverlayActivity.this, KaiActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                close();
            }
        });
        mStatus.setText(LocalEngine.modelName());
        enter();
        start(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Power held again while the card is up: listen again
        start(intent);
    }

    private void start(Intent intent) {
        if (intent != null && intent.getBooleanExtra("from_power_key", false)
                && Voice.available() && !mBusy) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!mVoice.recording()) {
                        toggleVoice();
                    }
                }
            }, 260);
        } else {
            mAura.setMode(KaiAuraView.IDLE);
        }
        // warm the model up while the question is being asked
        new Thread(new Runnable() {
            @Override
            public void run() {
                LocalEngine.ensureRunning();
            }
        }, "kAI-warm").start();
    }

    // --- in and out ----------------------------------------------------------------------------

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void enter() {
        mDim.setAlpha(0f);
        mDim.animate().alpha(1f).setDuration(220).start();
        mAura.setTranslationY(dp(60));
        mAura.animate().translationY(0f).setInterpolator(EASE).setDuration(600).start();
        mFrame.setAlpha(0f);
        mFrame.setTranslationY(dp(96));
        mFrame.setScaleX(0.94f);
        mFrame.setScaleY(0.94f);
        mFrame.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setInterpolator(EASE).setDuration(460).start();
    }

    private void close() {
        if (mClosing) {
            return;
        }
        mClosing = true;
        if (mBusy) {
            LocalEngine.cancel();
        }
        if (mVoice.recording()) {
            mVoice.stop();
        }
        mVoice.silence();
        hideKeyboard();
        mAura.setMode(KaiAuraView.OFF);
        mDim.animate().alpha(0f).setDuration(200).start();
        mAura.animate().translationY(dp(80)).setDuration(260).start();
        mFrame.animate().alpha(0f).translationY(dp(72)).scaleX(0.96f).scaleY(0.96f)
                .setInterpolator(EXIT).setDuration(220)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                }).start();
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    protected void onUserLeaveHint() {
        // Home pressed: no animation to wait for
        if (!mClosing) {
            mClosing = true;
            if (mBusy) {
                LocalEngine.cancel();
            }
            mVoice.stop();
            mVoice.silence();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        LocalEngine.sOverlayShowing = false;
        LocalEngine.releaseLater();
        super.onDestroy();
    }

    private void hideKeyboard() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(mInput.getWindowToken(), 0);
    }

    // --- voice ---------------------------------------------------------------------------------

    private void toggleVoice() {
        if (mBusy || mClosing) {
            return;
        }
        if (mVoice.recording()) {
            mVoice.stop();
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        mVoice.silence();
        hideKeyboard();
        final Locale lang = getResources().getConfiguration().getLocales().get(0);
        showVoice(true);
        mWave.setMode(KaiWaveView.LISTENING);
        mVoiceLabel.setText(R.string.listening);
        mAura.setMode(KaiAuraView.LISTENING);
        mVoice.listen(lang, new Voice.Listener() {
            @Override
            public void onLevel(float level) {
                mAura.setLevel(level);
                mWave.setLevel(level);
            }

            @Override
            public void onTranscribing() {
                mAura.setLevel(0f);
                mAura.setMode(KaiAuraView.THINKING);
                mWave.setMode(KaiWaveView.THINKING);
                mVoiceLabel.setText(R.string.transcribing);
            }

            @Override
            public void onText(String text) {
                resetMic();
                if (mClosing) {
                    return;
                }
                if (!text.isEmpty()) {
                    mSpoken = true;
                    mSpokenLang = WebSearch.looksItalian(text) ? Locale.ITALIAN : lang;
                    mInput.setText(text);
                    send();
                } else {
                    mAura.setMode(KaiAuraView.IDLE);
                }
            }

            @Override
            public void onError(String message) {
                resetMic();
                if (!mClosing) {
                    mAura.setMode(KaiAuraView.IDLE);
                    mInput.setHint(R.string.voice_failed);
                }
            }
        });
    }

    private void resetMic() {
        showVoice(false);
        mInput.setHint(R.string.ask);
    }

    /** while kAI listens the field shows the voice, and the microphone turns blue */
    private void showVoice(boolean on) {
        mVoiceRow.setVisibility(on ? View.VISIBLE : View.GONE);
        mInput.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        mMic.setBackgroundResource(on ? R.drawable.kai_mic_listening : R.drawable.kiro_icon_button);
        mMic.setColorFilter(on ? getColor(R.color.kiro_on_accent)
                : getColor(R.color.kiro_text), android.graphics.PorterDuff.Mode.SRC_IN);
        if (on) {
            mVoiceRow.setAlpha(0f);
            mVoiceRow.animate().alpha(1f).setDuration(180).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == 7 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            toggleVoice();
        }
    }

    // --- asking --------------------------------------------------------------------------------

    private void send() {
        final String text = mInput.getText().toString().trim();
        if (text.isEmpty() || mBusy) {
            return;
        }
        mInput.setText("");
        hideKeyboard();
        showQuestion(text);
        String done = PhoneActions.handle(this, text);
        if (done == null) {
            done = QuickMath.answer(text);   // exact arithmetic, instantly
        }
        if (done != null) {
            mText.setLength(0);
            mText.append(done);
            render();
            finishAnswer(text);
            return;
        }
        setBusy(true);
        mAura.setMode(KaiAuraView.THINKING);
        mText.setLength(0);
        mAnswer.setText("…");

        // the instructions in the language of the question (a small model follows them better)
        final Locale lang = WebSearch.looksItalian(text) ? Locale.ITALIAN
                : getResources().getConfiguration().getLocales().get(0);
        final Configuration conf = new Configuration(getResources().getConfiguration());
        conf.setLocale(lang);
        final Context lc = createConfigurationContext(conf);
        final boolean web = mPrefs.getBoolean("web", true) && online() && WebSearch.wanted(text);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<BridgeClient.Message> history = new ArrayList<>();
                String prompt = text;
                String system = lc.getString(R.string.system_prompt, WebSearch.today(lang));
                if (web) {
                    status(getString(R.string.searching));
                    final List<WebSearch.Result> results = WebSearch.search(
                            text.length() > 200 ? text.substring(0, 200) : text, 4, lang);
                    if (!results.isEmpty()) {
                        // the phone's own model gets the snippets and the question alone
                        system = lc.getString(R.string.web_system);
                        prompt = WebSearch.prompt(text, results, "",
                                lc.getString(R.string.web_results), lc.getString(R.string.question));
                    }
                }
                history.add(new BridgeClient.Message("system", system));
                history.add(new BridgeClient.Message("user", prompt));
                status(LocalEngine.loading() ? getString(R.string.model_loading)
                        : LocalEngine.modelName());
                LocalEngine.generate(history, new BridgeClient.Stream() {
                    private long mLastUi;

                    @Override
                    public void onDelta(String delta) {
                        synchronized (mText) {
                            mText.append(delta);
                        }
                        final long now = System.currentTimeMillis();
                        if (now - mLastUi > 60) {
                            mLastUi = now;
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    render();
                                }
                            });
                        }
                    }

                    @Override
                    public void onDone() {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                render();
                                setBusy(false);
                                finishAnswer(text);
                            }
                        });
                    }

                    @Override
                    public void onError(final String message) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setBusy(false);
                                mAura.setMode(KaiAuraView.IDLE);
                                if (mText.length() == 0) {
                                    mAnswer.setText(getString(R.string.error_answer, message));
                                } else {
                                    render();
                                }
                            }
                        });
                    }
                });
            }
        }, "kAI-overlay").start();
    }

    private void status(final String s) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mStatus.setText(s);
                if (mText.length() == 0) {
                    mAnswer.setText(s);
                }
            }
        });
    }

    private void showQuestion(String text) {
        mQuestion.setText(text);
        mAnswer.setText("…");
        if (mScroll.getVisibility() != View.VISIBLE) {
            mScroll.setVisibility(View.VISIBLE);
            mScroll.setAlpha(0f);
            mScroll.animate().alpha(1f).setDuration(260).start();
        }
        mFooter.setVisibility(View.VISIBLE);
    }

    private void render() {
        final String s;
        synchronized (mText) {
            s = mText.toString();
        }
        mAnswer.setText(s.isEmpty() ? "…" : mMarkdown.render(s));
        mScroll.post(new Runnable() {
            @Override
            public void run() {
                mScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void finishAnswer(String question) {
        final String answer;
        synchronized (mText) {
            answer = mText.toString().trim();
        }
        mAura.setMode(KaiAuraView.IDLE);
        mStatus.setText(LocalEngine.modelName());
        if (answer.isEmpty()) {
            return;
        }
        ChatStore.append(this, question, answer);
        if (mSpoken) {
            mVoice.speak(answer, mSpokenLang);
            mSpoken = false;
        }
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mSend.setImageResource(busy ? R.drawable.ic_stop : R.drawable.ic_send);
        mSend.setContentDescription(getString(busy ? R.string.stop : R.string.send));
    }

    @SuppressWarnings("deprecation")
    private boolean online() {
        final android.net.NetworkInfo n = ((android.net.ConnectivityManager) getSystemService(
                CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    /** the answer grows with its text up to 45% of the screen, then scrolls */
    public static class AnswerScroll extends ScrollView {
        public AnswerScroll(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int max = Math.round(getResources().getDisplayMetrics().heightPixels * 0.45f);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }
}
