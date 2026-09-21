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

package com.kiroos.messages;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.SmsMessage;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FilterQueryProvider;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MultiAutoCompleteTextView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * One conversation as bubbles on black, with the compose bar (text + one picture). Without
 * a thread it starts a new message: recipients with contact suggestions, then the thread.
 */
public class ConversationActivity extends Activity {

    private static final String EXTRA_THREAD = "thread";
    private static final int REQUEST_IMAGE = 1;
    private static final int THUMB_SIDE = 720;

    private static final LruCache<Uri, Bitmap> THUMBS = new LruCache<Uri, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(Uri key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private final Handler mHandler = new Handler();
    private final Adapter mAdapter = new Adapter();
    private long mThread = -1;
    private List<String> mAddresses = new ArrayList<>();
    private boolean mGroup;
    private Uri mAttachment;

    private TextView mTitle;
    private TextView mSubtitle;
    private MultiAutoCompleteTextView mRecipients;
    private ListView mList;
    private EditText mCompose;
    private TextView mCounter;
    private View mAttachmentBox;
    private ImageView mAttachmentImage;
    private View mCall;
    private boolean mLoading;
    private boolean mDirty;

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            load();
        }
    };

    static Intent intent(Context context, long thread) {
        return new Intent(context, ConversationActivity.class).putExtra(EXTRA_THREAD, thread)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation);
        mTitle = (TextView) findViewById(R.id.title);
        mSubtitle = (TextView) findViewById(R.id.subtitle);
        mRecipients = (MultiAutoCompleteTextView) findViewById(R.id.recipients);
        mList = (ListView) findViewById(R.id.messages);
        mCompose = (EditText) findViewById(R.id.compose);
        mCounter = (TextView) findViewById(R.id.counter);
        mAttachmentBox = findViewById(R.id.attachment);
        mAttachmentImage = (ImageView) findViewById(R.id.attachment_image);
        mAttachmentImage.setClipToOutline(true);
        mAttachmentImage.setBackground(new SquircleDrawable(getColor(R.color.kiro_surface_raised)));
        mCall = findViewById(R.id.btn_call);
        mList.setAdapter(mAdapter);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        mCall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mAddresses.size() == 1) {
                    startActivity(new Intent(Intent.ACTION_DIAL,
                            Uri.fromParts("tel", mAddresses.get(0), null)));
                }
            }
        });
        final ImageButton send = (ImageButton) findViewById(R.id.btn_send);
        send.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSend();
            }
        });
        KiroTouch.apply(send);
        findViewById(R.id.btn_attach).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(Intent.createChooser(
                        new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                                .addCategory(Intent.CATEGORY_OPENABLE),
                        getString(R.string.attach)), REQUEST_IMAGE);
            }
        });
        findViewById(R.id.attachment_remove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAttachment(null);
            }
        });
        mCompose.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateCounter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        mList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showMessageMenu(mAdapter.getItem(position));
                return true;
            }
        });
        mList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final MessageStore.Message m = mAdapter.getItem(position);
                if (m.status == MessageStore.STATUS_FAILED) {
                    resend(m);
                }
            }
        });
        setupRecipientSuggestions();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        mThread = intent.getLongExtra(EXTRA_THREAD, -1);
        mAddresses = new ArrayList<>();
        final Uri data = intent.getData();
        if (data != null && data.getScheme() != null && data.getScheme().startsWith("sms")
                || data != null && data.getScheme() != null && data.getScheme().startsWith("mms")) {
            mAddresses = HeadlessSmsSendService.recipients(data);
        }
        if (mAddresses.isEmpty() && intent.getStringExtra("address") != null) {
            mAddresses = HeadlessSmsSendService.recipients(
                    Uri.fromParts("smsto", intent.getStringExtra("address"), null));
        }
        String body = intent.getStringExtra("sms_body");
        if (body == null) {
            body = intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (body != null && mCompose.length() == 0) {
            mCompose.setText(body);
            mCompose.setSelection(mCompose.length());
        }
        final Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream != null) {
            setAttachment(stream);
        }
        if (mThread < 0 && !mAddresses.isEmpty()) {
            final List<String> to = mAddresses;
            new AsyncTask<Void, Void, Long>() {
                @Override
                protected Long doInBackground(Void... params) {
                    try {
                        return MessageStore.threadIdFor(ConversationActivity.this, to);
                    } catch (RuntimeException e) {
                        return -1L;
                    }
                }

                @Override
                protected void onPostExecute(Long thread) {
                    openThread(thread, to);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else if (mThread >= 0) {
            final long thread = mThread;
            new AsyncTask<Void, Void, List<String>>() {
                @Override
                protected List<String> doInBackground(Void... params) {
                    return MessageStore.addressesOf(ConversationActivity.this, thread);
                }

                @Override
                protected void onPostExecute(List<String> addresses) {
                    openThread(thread, addresses);
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            // new message: pick recipients first
            mRecipients.setVisibility(View.VISIBLE);
            mRecipients.requestFocus();
            mTitle.setText(R.string.new_message);
            mCall.setVisibility(View.GONE);
        }
    }

    private void openThread(long thread, List<String> addresses) {
        mThread = thread;
        mAddresses = new ArrayList<>(addresses);
        mGroup = mAddresses.size() > 1;
        mRecipients.setVisibility(View.GONE);
        mCall.setVisibility(mAddresses.size() == 1 ? View.VISIBLE : View.GONE);
        new AsyncTask<Void, Void, String[]>() {
            @Override
            protected String[] doInBackground(Void... params) {
                final String title = Names.title(ConversationActivity.this, mAddresses);
                final String sub = mAddresses.size() == 1
                        && Names.lookup(ConversationActivity.this, mAddresses.get(0)) != null
                        ? Names.format(mAddresses.get(0)) : null;
                return new String[] {title, sub};
            }

            @Override
            protected void onPostExecute(String[] s) {
                mTitle.setText(s[0]);
                mSubtitle.setText(s[1]);
                mSubtitle.setVisibility(s[1] != null ? View.VISIBLE : View.GONE);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        Notifier.sOpenThread = thread;
        Notifier.cancel(this, thread);
        markRead();
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            getContentResolver().registerContentObserver(Uri.parse("content://mms-sms/"), true,
                    mObserver);
        } catch (SecurityException e) {
            // no permission
        }
        if (mThread >= 0) {
            Notifier.sOpenThread = mThread;
            Notifier.cancel(this, mThread);
            markRead();
            load();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        getContentResolver().unregisterContentObserver(mObserver);
        Notifier.sOpenThread = -1;
    }

    private void markRead() {
        final long thread = mThread;
        final Context app = getApplicationContext();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                MessageStore.markThreadRead(app, thread);
            }
        });
    }

    private void load() {
        if (mThread < 0) {
            return;
        }
        if (mLoading) {
            mDirty = true;
            return;
        }
        mLoading = true;
        final long thread = mThread;
        new AsyncTask<Void, Void, List<MessageStore.Message>>() {
            @Override
            protected List<MessageStore.Message> doInBackground(Void... params) {
                final List<MessageStore.Message> list =
                        MessageStore.messages(ConversationActivity.this, thread);
                for (MessageStore.Message m : list) {
                    if (m.incoming && m.address != null) {
                        Names.lookup(ConversationActivity.this, m.address); // warm the cache
                    }
                }
                return list;
            }

            @Override
            protected void onPostExecute(List<MessageStore.Message> list) {
                mLoading = false;
                final boolean atBottom = mList.getLastVisiblePosition() >= mAdapter.getCount() - 2;
                mAdapter.set(list);
                if (atBottom) {
                    mList.setSelection(mAdapter.getCount() - 1);
                }
                if (mDirty) {
                    mDirty = false;
                    load();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // --- compose --------------------------------------------------------------------------

    private void updateCounter() {
        final String text = mCompose.getText().toString();
        if (text.isEmpty() || mAttachment != null || mGroup) {
            mCounter.setVisibility(View.GONE);
            return;
        }
        final int[] len = SmsMessage.calculateLength(text, false);
        final int parts = len[0];
        final int remaining = len[2];
        if (parts > 1 || remaining <= 20) {
            mCounter.setText(parts > 1 ? remaining + " / " + parts : String.valueOf(remaining));
            mCounter.setVisibility(View.VISIBLE);
        } else {
            mCounter.setVisibility(View.GONE);
        }
    }

    private void setAttachment(Uri uri) {
        mAttachment = uri;
        mAttachmentBox.setVisibility(uri != null ? View.VISIBLE : View.GONE);
        if (uri != null) {
            loadThumb(uri, mAttachmentImage);
        } else {
            mAttachmentImage.setImageDrawable(null);
        }
        updateCounter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            setAttachment(data.getData());
        }
    }

    private void onSend() {
        final String text = mCompose.getText().toString().trim();
        if (text.isEmpty() && mAttachment == null) {
            return;
        }
        if (mThread < 0) {
            final List<String> to = parseRecipients(mRecipients.getText().toString());
            if (to.isEmpty()) {
                Toast.makeText(this, R.string.no_recipients, Toast.LENGTH_SHORT).show();
                return;
            }
            mAddresses = to;
        }
        final List<String> to = new ArrayList<>(mAddresses);
        final Uri image = mAttachment;
        final boolean opened = mThread >= 0;
        mCompose.setText("");
        setAttachment(null);
        final Context app = getApplicationContext();
        new AsyncTask<Void, Void, Long>() {
            @Override
            protected Long doInBackground(Void... params) {
                try {
                    Sender.send(app, to, text, image);
                    return MessageStore.threadIdFor(app, to);
                } catch (RuntimeException e) {
                    return -1L;
                }
            }

            @Override
            protected void onPostExecute(Long thread) {
                if (thread < 0) {
                    Toast.makeText(ConversationActivity.this, R.string.not_default,
                            Toast.LENGTH_LONG).show();
                } else if (!opened) {
                    openThread(thread, to);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** "Anna <+39 340 …>, 3331234567" → numbers */
    private static List<String> parseRecipients(String text) {
        final List<String> out = new ArrayList<>();
        for (String part : text.split("[,;]")) {
            String p = part.trim();
            final int lt = p.lastIndexOf('<');
            final int gt = p.lastIndexOf('>');
            if (lt >= 0 && gt > lt) {
                p = p.substring(lt + 1, gt);
            }
            p = p.replaceAll("[\\s()-]", "");
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    private void setupRecipientSuggestions() {
        final SimpleCursorAdapter adapter = new SimpleCursorAdapter(this,
                R.layout.item_suggestion, null,
                new String[] {Phone.DISPLAY_NAME, Phone.NUMBER},
                new int[] {R.id.name, R.id.number}, 0);
        adapter.setFilterQueryProvider(new FilterQueryProvider() {
            @Override
            public Cursor runQuery(CharSequence constraint) {
                if (TextUtils.isEmpty(constraint)) {
                    return null;
                }
                try {
                    return getContentResolver().query(Uri.withAppendedPath(
                            Phone.CONTENT_FILTER_URI, Uri.encode(constraint.toString())),
                            new String[] {Phone._ID, Phone.DISPLAY_NAME, Phone.NUMBER},
                            null, null, null);
                } catch (RuntimeException e) {
                    return null;
                }
            }
        });
        adapter.setCursorToStringConverter(new SimpleCursorAdapter.CursorToStringConverter() {
            @Override
            public CharSequence convertToString(Cursor c) {
                return c.getString(1) + " <" + c.getString(2) + ">";
            }
        });
        mRecipients.setAdapter(adapter);
        mRecipients.setTokenizer(new MultiAutoCompleteTextView.CommaTokenizer());
    }

    // --- message actions ------------------------------------------------------------------

    private void showMessageMenu(final MessageStore.Message m) {
        final List<String> items = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        if (!TextUtils.isEmpty(m.body)) {
            items.add(getString(R.string.copy_text));
            ids.add(1);
            items.add(getString(R.string.forward));
            ids.add(2);
        }
        if (m.status == MessageStore.STATUS_FAILED) {
            items.add(getString(R.string.resend));
            ids.add(3);
        }
        items.add(getString(R.string.delete));
        ids.add(4);
        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (ids.get(which)) {
                            case 1:
                                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                                        .setPrimaryClip(ClipData.newPlainText(null, m.body));
                                Toast.makeText(ConversationActivity.this, R.string.copied,
                                        Toast.LENGTH_SHORT).show();
                                break;
                            case 2:
                                startActivity(new Intent(ConversationActivity.this,
                                        ConversationActivity.class)
                                        .putExtra(Intent.EXTRA_TEXT, m.body));
                                break;
                            case 3:
                                resend(m);
                                break;
                            case 4:
                                deleteMessage(m);
                                break;
                            default:
                                break;
                        }
                    }
                })
                .show();
    }

    private void resend(final MessageStore.Message m) {
        final List<String> to = new ArrayList<>(mAddresses);
        final Context app = getApplicationContext();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    MessageStore.deleteMessage(app, m);
                    Sender.send(app, to, m.body, m.images.isEmpty() ? null : m.images.get(0));
                } catch (RuntimeException e) {
                    // not the default SMS app
                }
            }
        });
    }

    private void deleteMessage(final MessageStore.Message m) {
        final Context app = getApplicationContext();
        AsyncTask.THREAD_POOL_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    MessageStore.deleteMessage(app, m);
                } catch (RuntimeException e) {
                    // not the default SMS app
                }
            }
        });
    }

    private void showImage(Uri uri) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final ImageView iv = new ImageView(this);
        iv.setBackgroundColor(getColor(R.color.kiro_black));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadThumb(uri, iv);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        d.setContentView(iv);
        d.show();
    }

    private void loadThumb(final Uri uri, final ImageView into) {
        into.setTag(uri);
        final Bitmap cached = THUMBS.get(uri);
        if (cached != null) {
            into.setImageBitmap(cached);
            return;
        }
        into.setImageDrawable(null);
        new AsyncTask<Void, Void, Bitmap>() {
            @Override
            protected Bitmap doInBackground(Void... params) {
                try {
                    final BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        BitmapFactory.decodeStream(in, null, o);
                    }
                    int sample = 1;
                    while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= THUMB_SIDE) {
                        sample *= 2;
                    }
                    final BitmapFactory.Options d = new BitmapFactory.Options();
                    d.inSampleSize = sample;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        return BitmapFactory.decodeStream(in, null, d);
                    }
                } catch (Exception e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap b) {
                if (b != null) {
                    THUMBS.put(uri, b);
                    if (uri.equals(into.getTag())) {
                        into.setImageBitmap(b);
                    }
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    // --- bubbles --------------------------------------------------------------------------

    private final class Adapter extends BaseAdapter {
        private List<MessageStore.Message> mItems = Collections.emptyList();

        void set(List<MessageStore.Message> items) {
            mItems = items;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public MessageStore.Message getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            final MessageStore.Message m = mItems.get(position);
            return m.mms ? -m.id : m.id;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View v = convertView != null ? convertView : LayoutInflater.from(
                    ConversationActivity.this).inflate(R.layout.item_message, parent, false);
            final MessageStore.Message m = mItems.get(position);
            final LinearLayout row = (LinearLayout) v.findViewById(R.id.row);
            final LinearLayout bubble = (LinearLayout) v.findViewById(R.id.bubble);
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bubble.getLayoutParams();
            lp.gravity = m.incoming ? Gravity.START : Gravity.END;
            bubble.setLayoutParams(lp);
            bubble.setBackgroundResource(m.incoming ? R.drawable.bubble_in : R.drawable.bubble_out);
            bubble.setAlpha(m.status == MessageStore.STATUS_SENDING ? 0.6f : 1f);

            // day separator
            final TextView day = (TextView) v.findViewById(R.id.day);
            if (position == 0 || !sameDay(mItems.get(position - 1).date, m.date)) {
                day.setText(DateUtils.formatDateTime(ConversationActivity.this, m.date,
                        DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_WEEKDAY
                                | DateUtils.FORMAT_ABBREV_MONTH));
                day.setVisibility(View.VISIBLE);
            } else {
                day.setVisibility(View.GONE);
            }

            final TextView sender = (TextView) v.findViewById(R.id.sender);
            if (mGroup && m.incoming && m.address != null) {
                final String n = Names.lookup(ConversationActivity.this, m.address);
                sender.setText(n != null ? n : Names.format(m.address));
                sender.setVisibility(View.VISIBLE);
            } else {
                sender.setVisibility(View.GONE);
            }

            final TextView body = (TextView) v.findViewById(R.id.body);
            body.setText(m.body);
            body.setVisibility(TextUtils.isEmpty(m.body) ? View.GONE : View.VISIBLE);

            final LinearLayout images = (LinearLayout) v.findViewById(R.id.images);
            images.removeAllViews();
            final int side = getResources().getDimensionPixelSize(R.dimen.bubble_image);
            for (final Uri img : m.images) {
                final ImageView iv = new ImageView(ConversationActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(new SquircleDrawable(getColor(R.color.kiro_surface_raised)));
                iv.setClipToOutline(true);
                final LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(side, side);
                ilp.bottomMargin = Math.round(4 * getResources().getDisplayMetrics().density);
                images.addView(iv, ilp);
                loadThumb(img, iv);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        showImage(img);
                    }
                });
            }

            final TextView meta = (TextView) v.findViewById(R.id.meta);
            final String time = DateFormat.getTimeFormat(ConversationActivity.this)
                    .format(m.date);
            if (m.status == MessageStore.STATUS_FAILED) {
                meta.setText(getString(R.string.not_sent_retry));
                meta.setTextColor(getColor(R.color.kiro_negative));
            } else if (m.status == MessageStore.STATUS_SENDING) {
                meta.setText(getString(R.string.sending));
                meta.setTextColor(getColor(R.color.meta_text));
            } else {
                meta.setText(time);
                meta.setTextColor(getColor(R.color.meta_text));
            }
            row.setContentDescription((m.incoming ? getString(R.string.received)
                    : getString(R.string.sent)) + ", " + time + ", " + m.body);
            return v;
        }
    }

    private static boolean sameDay(long a, long b) {
        final Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        final Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }
}
