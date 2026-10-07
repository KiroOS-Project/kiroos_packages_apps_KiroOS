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

package com.kiroos.applock;

import android.content.Context;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The private vault: files kept encrypted in the app's own storage.
 * <p>
 * A random AES-256 data key encrypts everything; it is stored wrapped by an AndroidKeyStore key
 * that works only for 5 minutes after the fingerprint or the screen lock, so the data key can
 * only be unwrapped right after the user proved who they are. Files are cut into 64 KiB chunks,
 * each AES-GCM with its own nonce and bound (associated data) to its file, position and "last"
 * flag: a chunk cannot be swapped, reordered or cut off without the decryption failing.
 * <p>
 * Android erases the keystore key when the screen lock is turned off: the vault cannot be opened
 * after that, which the vault screen says.
 */
final class Vault {

    static final class Item {
        String id, name, mime;
        long size, added;
        boolean thumb;
    }

    /** the keystore key is gone (screen lock turned off): the files cannot be read */
    static final class LostKeyException extends Exception {
        LostKeyException(Throwable cause) {
            super(cause);
        }
    }

    private static final String KEY_ALIAS = "kiroos_vault";
    private static final int CHUNK = 64 * 1024;
    private static final int TAG_BITS = 128;
    private static final byte[] MAGIC = {'K', 'V', '1', 0};
    /** proof of a fingerprint / screen lock in this process (never from an intent) */
    private static long sAuthenticatedAt = -1;

    private final File mDir;
    private final SecureRandom mRandom = new SecureRandom();
    private SecretKey mKey;
    final List<Item> items = new ArrayList<>();

    Vault(Context c) {
        mDir = new File(c.getFilesDir(), "vault");
        mDir.mkdirs();
    }

    static void authenticated() {
        sAuthenticatedAt = SystemClock.elapsedRealtime();
    }

    /** the user proved who they are in this app in the last minute */
    static boolean recentlyAuthenticated() {
        return sAuthenticatedAt > 0 && SystemClock.elapsedRealtime() - sAuthenticatedAt < 60000;
    }

    boolean isOpen() {
        return mKey != null;
    }

    boolean exists() {
        return new File(mDir, "key").isFile();
    }

    /** Blocking. Needs a fingerprint or screen lock in the last 5 minutes. */
    void open() throws Exception {
        final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        final File keyFile = new File(mDir, "key");
        if (!ks.containsAlias(KEY_ALIAS)) {
            if (keyFile.isFile()) throw new LostKeyException(null);
            final KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(300)
                    .build());
            g.generateKey();
        }
        final SecretKey wrap = (SecretKey) ks.getKey(KEY_ALIAS, null);
        final byte[] raw;
        try {
            if (!keyFile.isFile()) {
                // a new vault: its data key, kept only wrapped
                raw = new byte[32];
                mRandom.nextBytes(raw);
                final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, wrap);
                final byte[] iv = c.getIV();
                final byte[] ct = c.doFinal(raw);
                final ByteArrayOutputStream b = new ByteArrayOutputStream();
                b.write(iv.length);
                b.write(iv);
                b.write(ct);
                atomicWrite(keyFile, b.toByteArray());
            } else {
                final byte[] all = readAll(new FileInputStream(keyFile));
                final int ivLen = all[0] & 0xff;
                final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, wrap,
                        new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(all, 1, 1 + ivLen)));
                raw = c.doFinal(all, 1 + ivLen, all.length - 1 - ivLen);
            }
        } catch (android.security.keystore.KeyPermanentlyInvalidatedException e) {
            throw new LostKeyException(e);
        }
        mKey = new SecretKeySpec(raw, "AES");
        Arrays.fill(raw, (byte) 0);
        loadIndex();
    }

    void lock() {
        mKey = null;
        items.clear();
    }

    /** erases the vault (after the key was lost, or on request): every file in it is gone */
    void reset() throws Exception {
        lock();
        final File[] files = mDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        final KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS);
    }

    // ---- files

    Item add(InputStream in, String name, String mime, byte[] thumbJpeg) throws Exception {
        final Item it = new Item();
        it.id = UUID.randomUUID().toString();
        it.name = name;
        it.mime = mime != null ? mime : "application/octet-stream";
        it.added = System.currentTimeMillis();
        final File tmp = new File(mDir, it.id + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            it.size = encrypt(in, out, it.id);
        } catch (Exception e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(new File(mDir, it.id))) throw new IOException("rename");
        if (thumbJpeg != null) {
            try (OutputStream out = new FileOutputStream(new File(mDir, it.id + ".t"))) {
                encrypt(new ByteArrayInputStream(thumbJpeg), out, it.id + ".t");
                it.thumb = true;
            }
        }
        items.add(0, it);
        saveIndex();
        return it;
    }

    void read(Item it, OutputStream out) throws Exception {
        try (InputStream in = new FileInputStream(new File(mDir, it.id))) {
            decrypt(in, out, it.id);
        }
    }

    byte[] thumb(Item it) throws Exception {
        if (!it.thumb) return null;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(new File(mDir, it.id + ".t"))) {
            decrypt(in, out, it.id + ".t");
        }
        return out.toByteArray();
    }

    void delete(Item it) throws Exception {
        items.remove(it);
        saveIndex();
        new File(mDir, it.id).delete();
        new File(mDir, it.id + ".t").delete();
    }

    // ---- index

    private void loadIndex() throws Exception {
        items.clear();
        final File f = new File(mDir, "index");
        if (!f.isFile()) return;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(f)) {
            decrypt(in, out, "index");
        }
        final JSONArray a = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
        for (int i = 0; i < a.length(); i++) {
            final JSONObject o = a.getJSONObject(i);
            final Item it = new Item();
            it.id = o.getString("id");
            it.name = o.getString("name");
            it.mime = o.getString("mime");
            it.size = o.getLong("size");
            it.added = o.getLong("added");
            it.thumb = o.optBoolean("thumb");
            if (new File(mDir, it.id).isFile()) items.add(it);
        }
    }

    private void saveIndex() throws Exception {
        final JSONArray a = new JSONArray();
        for (Item it : items) {
            a.put(new JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime)
                    .put("size", it.size).put("added", it.added).put("thumb", it.thumb));
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        encrypt(new ByteArrayInputStream(a.toString().getBytes(StandardCharsets.UTF_8)), out,
                "index");
        atomicWrite(new File(mDir, "index"), out.toByteArray());
    }

    // ---- chunked AES-GCM

    /** @return the plaintext size */
    private long encrypt(InputStream in, OutputStream raw, String id) throws Exception {
        if (mKey == null) throw new IllegalStateException("locked");
        final DataOutputStream out = new DataOutputStream(raw);
        out.write(MAGIC);
        byte[] cur = new byte[CHUNK], next = new byte[CHUNK];
        int n = fill(in, cur);
        long total = 0;
        int index = 0;
        while (true) {
            final int m = n == CHUNK ? fill(in, next) : 0;
            final boolean last = m <= 0;
            final byte[] nonce = new byte[12];
            mRandom.nextBytes(nonce);
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, mKey, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(id, index, last));
            final byte[] ct = c.doFinal(cur, 0, Math.max(n, 0));
            out.writeByte(last ? 1 : 0);
            out.write(nonce);
            out.writeInt(ct.length);
            out.write(ct);
            total += Math.max(n, 0);
            if (last) break;
            final byte[] t = cur;
            cur = next;
            next = t;
            n = m;
            index++;
        }
        out.flush();
        return total;
    }

    private void decrypt(InputStream raw, OutputStream out, String id) throws Exception {
        if (mKey == null) throw new IllegalStateException("locked");
        final DataInputStream in = new DataInputStream(raw);
        final byte[] magic = new byte[4];
        in.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new GeneralSecurityException("not a vault file");
        int index = 0;
        while (true) {
            final boolean last;
            try {
                last = in.readByte() == 1;
            } catch (EOFException e) {
                throw new GeneralSecurityException("cut short");
            }
            final byte[] nonce = new byte[12];
            in.readFully(nonce);
            final int len = in.readInt();
            if (len < 16 || len > CHUNK + 16) throw new GeneralSecurityException("bad chunk");
            final byte[] ct = new byte[len];
            in.readFully(ct);
            final Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, mKey, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(aad(id, index, last));
            out.write(c.doFinal(ct));
            if (last) break;
            index++;
        }
    }

    private static byte[] aad(String id, int index, boolean last) {
        final byte[] b = id.getBytes(StandardCharsets.UTF_8);
        final byte[] a = Arrays.copyOf(b, b.length + 5);
        a[b.length] = (byte) (index >>> 24);
        a[b.length + 1] = (byte) (index >>> 16);
        a[b.length + 2] = (byte) (index >>> 8);
        a[b.length + 3] = (byte) index;
        a[b.length + 4] = (byte) (last ? 1 : 0);
        return a;
    }

    private static int fill(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        while (n < buf.length) {
            final int r = in.read(buf, n, buf.length - n);
            if (r < 0) break;
            n += r;
        }
        return n;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void atomicWrite(File f, byte[] data) throws IOException {
        final File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename " + f.getName());
    }
}
