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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * kAI: chat with a language model running on the PC through the kAI bridge (USB via adb
 * reverse, or LAN). Answers stream in and are rendered as Markdown. The conversation is
 * kept on the phone.
 */
public class KaiActivity extends Activity {

    private static final String SYSTEM_PROMPT = "You are kAI, the assistant of KiroOS on a "
            + "Samsung Galaxy S6. Answer clearly and concisely, in the user's language, using "
            + "Markdown when it helps.";
    /** Ollama's port; over USB "adb reverse tcp:11434 tcp:11434" maps it to the PC */
    private static final int DEFAULT_PORT = 11434;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kai);
        mPrefs = getSharedPreferences("kai", MODE_PRIVATE);
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
        findViewById(R.id.btn_new).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mBusy) {
                    mMessages.clear();
                    save();
                    refresh();
                }
            }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettings();
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
        // shared text ("Share → kAI") becomes the prompt
        final String shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        if (shared != null) {
            mInput.setText(shared);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBridge();
    }

    private BridgeClient client() {
        return new BridgeClient(mPrefs.getString("host", "127.0.0.1"),
                mPrefs.getInt("port", DEFAULT_PORT));
    }

    private void checkBridge() {
        mStatus.setText(R.string.connecting);
        mStatus.setTextColor(getColor(R.color.kiro_text_secondary));
        final BridgeClient c = client();
        final String preferred = mPrefs.getString("model", "");
        new AsyncTask<Void, Void, String>() {
            private boolean mNoModel;

            @Override
            protected String doInBackground(Void... params) {
                try {
                    return c.health(preferred);
                } catch (BridgeClient.NoModelException e) {
                    mNoModel = true;
                    return null;
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(String model) {
                if (isDestroyed()) {
                    return;
                }
                if (model != null) {
                    mStatus.setText(getString(R.string.connected, model.isEmpty()
                            ? getString(R.string.default_model) : model));
                    mStatus.setTextColor(getColor(R.color.kiro_positive));
                } else {
                    mStatus.setText(mNoModel ? R.string.no_model : R.string.not_connected);
                    mStatus.setTextColor(getColor(R.color.kiro_negative));
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
        final BridgeClient.Message answer = new BridgeClient.Message("assistant", "");
        mMessages.add(answer);
        refresh();
        setBusy(true);
        final List<BridgeClient.Message> history = new ArrayList<>();
        history.add(new BridgeClient.Message("system", SYSTEM_PROMPT));
        // the last 20 turns keep context without overflowing small local models
        final int from = Math.max(0, mMessages.size() - 1 - 20);
        for (int i = from; i < mMessages.size() - 1; i++) {
            history.add(mMessages.get(i));
        }
        mClient = client();
        final BridgeClient c = mClient;
        final String preferred = mPrefs.getString("model", "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // ask the PC which model answers (and which protocol it speaks) first
                String model;
                try {
                    model = c.health(preferred);
                } catch (BridgeClient.NoModelException e) {
                    model = null;
                    postError(answer, getString(R.string.no_model));
                } catch (Exception e) {
                    model = null;
                    postError(answer, getString(R.string.not_connected));
                }
                if (model == null) {
                    return;
                }
                c.chat(model, history, new BridgeClient.Stream() {
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
                                if (answer.content.isEmpty()) {
                                    mMessages.remove(answer);
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
                });
            }
        }, "kAI-chat").start();
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

    private File file() {
        return new File(getFilesDir(), "conversation.json");
    }

    private void save() {
        try {
            final JSONArray a = new JSONArray();
            for (BridgeClient.Message m : mMessages) {
                a.put(new JSONObject().put("role", m.role).put("content", m.content));
            }
            try (FileOutputStream out = new FileOutputStream(file())) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            // not fatal: the chat just won't survive a restart
        }
    }

    private void load() {
        final File f = file();
        if (!f.exists()) {
            return;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            final byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                final int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            final JSONArray a = new JSONArray(new String(data, 0, off, StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.getJSONObject(i);
                mMessages.add(new BridgeClient.Message(o.getString("role"), o.getString("content")));
            }
        } catch (Exception e) {
            mMessages.clear();
        }
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
                text.setText("…");
            } else {
                text.setText(mMarkdown.render(content));
            }
            return v;
        }
    }
}
