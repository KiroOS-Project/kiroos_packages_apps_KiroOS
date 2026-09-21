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

package com.kiroos.contacts;

import android.accounts.Account;
import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.provider.ContactsContract.Contacts;
import android.util.Log;
import android.widget.Toast;

import com.android.vcard.VCardConfig;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryCommitter;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardParser_V30;
import com.android.vcard.exception.VCardVersionException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** vCard (.vcf) import into the default account and export of all contacts to Download. */
final class VCardIO {

    private static final String TAG = "KiroContacts";

    private VCardIO() {
    }

    static void importFrom(final Activity activity, final Uri uri) {
        final Context app = activity.getApplicationContext();
        Toast.makeText(activity, R.string.importing, Toast.LENGTH_SHORT).show();
        new AsyncTask<Void, Void, Integer>() {
            @Override
            protected Integer doInBackground(Void... params) {
                // vCard 2.1 first; files that declare 3.0 are parsed again with the 3.0 parser
                try {
                    return parse(app, uri, new VCardParser_V21(VCardConfig.VCARD_TYPE_DEFAULT));
                } catch (VCardVersionException e) {
                    try {
                        return parse(app, uri, new VCardParser_V30(VCardConfig.VCARD_TYPE_DEFAULT));
                    } catch (Exception e2) {
                        Log.w(TAG, "vCard import failed", e2);
                        return -1;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "vCard import failed", e);
                    return -1;
                }
            }

            @Override
            protected void onPostExecute(Integer count) {
                Toast.makeText(app, count < 0 ? app.getString(R.string.import_failed)
                        : app.getString(R.string.imported, count), Toast.LENGTH_LONG).show();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private static int parse(Context context, Uri uri, VCardParser parser) throws Exception {
        final Account account = Accounts.defaultAccount(context);
        final VCardEntryConstructor constructor =
                new VCardEntryConstructor(VCardConfig.VCARD_TYPE_DEFAULT, account);
        final VCardEntryCommitter committer =
                new VCardEntryCommitter(context.getContentResolver());
        final int[] count = {0};
        constructor.addEntryHandler(committer);
        constructor.addEntryHandler(new VCardEntryHandler() {
            @Override
            public void onStart() {
            }

            @Override
            public void onEntryCreated(VCardEntry entry) {
                count[0]++;
            }

            @Override
            public void onEnd() {
            }
        });
        parser.addInterpreter(constructor);
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            parser.parse(in);
        }
        return count[0];
    }

    /** All contacts in one .vcf in the Download folder. */
    static void exportAll(final Activity activity) {
        final Context app = activity.getApplicationContext();
        new AsyncTask<Void, Void, File>() {
            @Override
            protected File doInBackground(Void... params) {
                final StringBuilder keys = new StringBuilder();
                try (Cursor c = app.getContentResolver().query(Contacts.CONTENT_URI,
                        new String[] {Contacts.LOOKUP_KEY}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        if (keys.length() > 0) {
                            keys.append(':');
                        }
                        keys.append(c.getString(0));
                    }
                }
                if (keys.length() == 0) {
                    return null;
                }
                final File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                final File out = new File(dir, "contacts-" + new SimpleDateFormat(
                        "yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".vcf");
                final Uri all = Uri.withAppendedPath(Contacts.CONTENT_MULTI_VCARD_URI,
                        Uri.encode(keys.toString()));
                try (InputStream in = app.getContentResolver().openInputStream(all);
                     OutputStream os = new FileOutputStream(out)) {
                    final byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                    return out;
                } catch (Exception e) {
                    Log.w(TAG, "vCard export failed", e);
                    out.delete();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(File f) {
                Toast.makeText(app, f == null ? app.getString(R.string.export_failed)
                        : app.getString(R.string.exported, f.getName()), Toast.LENGTH_LONG).show();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
