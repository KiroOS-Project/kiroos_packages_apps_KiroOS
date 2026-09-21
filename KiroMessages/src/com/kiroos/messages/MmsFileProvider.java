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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Raw MMS PDUs exchanged with the platform MmsService: it reads the PDU we send and writes
 * the PDU it downloads. Only system uids (the phone process) may open the files.
 */
public class MmsFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.kiroos.messages.mmsfile";
    private static final String DIR = "mms";

    static String newFileName() {
        return UUID.randomUUID().toString() + ".pdu";
    }

    static Uri uri(String name) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(name).build();
    }

    static File file(Context context, String name) {
        final File dir = new File(context.getCacheDir(), DIR);
        dir.mkdirs();
        return new File(dir, new File(name).getName()); // no path traversal
    }

    static void write(Context context, String name, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file(context, name))) {
            out.write(data);
        }
    }

    static byte[] read(Context context, String name) throws IOException {
        final File f = file(context, name);
        final byte[] data = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(data);
        }
        return data;
    }

    static void delete(Context context, String name) {
        file(context, name).delete();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        final int uid = Binder.getCallingUid();
        if (uid >= Process.FIRST_APPLICATION_UID && uid != Process.myUid()) {
            throw new SecurityException("MMS files are only for the phone process");
        }
        final File f = file(getContext(), uri.getLastPathSegment());
        final int flags = mode.contains("w")
                ? ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        return ParcelFileDescriptor.open(f, flags);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.wap.mms-message";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read/write through openFile only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read/write through openFile only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read/write through openFile only");
    }
}
