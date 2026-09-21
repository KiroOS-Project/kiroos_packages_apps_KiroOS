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
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.SquircleDrawable;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * Create or edit a contact: photo, name, company, phones, e-mails, address, birthday,
 * website, note. Handles INSERT (with the standard extras), EDIT and INSERT_OR_EDIT.
 */
public class ContactEditActivity extends Activity {

    private static final int REQUEST_PHOTO = 1;
    private static final int REQUEST_PICK_EXISTING = 2;
    private static final int PHOTO_SIDE = 720;

    private static final int[] PHONE_TYPES = {Phone.TYPE_MOBILE, Phone.TYPE_HOME, Phone.TYPE_WORK,
            Phone.TYPE_MAIN, Phone.TYPE_OTHER};
    private static final int[] EMAIL_TYPES = {Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_OTHER};

    private long mRawContactId = -1;
    private Uri mContactUri;
    private byte[] mPhoto;
    private boolean mPhotoChanged;
    private long mPhotoRow = -1;

    private EditText mFirst;
    private EditText mLast;
    private EditText mCompany;
    private EditText mAddress;
    private EditText mWebsite;
    private EditText mNote;
    private TextView mBirthday;
    private String mBirthdayValue;
    private LinearLayout mPhones;
    private LinearLayout mEmails;
    private ImageView mPhotoView;
    private TextView mPhotoLetter;
    /** data rows loaded for editing: mime → id (single-valued fields) */
    private final java.util.Map<String, Long> mSingleRows = new java.util.HashMap<>();
    private final ArrayList<Long> mDeletedRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit);
        mFirst = (EditText) findViewById(R.id.first_name);
        mLast = (EditText) findViewById(R.id.last_name);
        mCompany = (EditText) findViewById(R.id.company);
        mAddress = (EditText) findViewById(R.id.address);
        mWebsite = (EditText) findViewById(R.id.website);
        mNote = (EditText) findViewById(R.id.note);
        mBirthday = (TextView) findViewById(R.id.birthday);
        mPhones = (LinearLayout) findViewById(R.id.phones);
        mEmails = (LinearLayout) findViewById(R.id.emails);
        mPhotoView = (ImageView) findViewById(R.id.photo);
        mPhotoLetter = (TextView) findViewById(R.id.letter);
        mPhotoView.setClipToOutline(true);

        findViewById(R.id.btn_cancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        final TextView save = (TextView) findViewById(R.id.btn_save);
        save.setBackground(SquircleDrawable.button(getColor(R.color.kiro_accent),
                getColor(R.color.kiro_ripple)));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.photo_box).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE), REQUEST_PHOTO);
            }
        });
        findViewById(R.id.add_phone).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRow(mPhones, true, -1, "", Phone.TYPE_MOBILE).requestFocus();
            }
        });
        findViewById(R.id.add_email).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addRow(mEmails, false, -1, "", Email.TYPE_HOME).requestFocus();
            }
        });
        mBirthday.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickBirthday();
            }
        });

        final Intent intent = getIntent();
        final String action = intent.getAction();
        if (Intent.ACTION_EDIT.equals(action) && intent.getData() != null) {
            mContactUri = intent.getData();
            load(mContactUri);
        } else if (Intent.ACTION_INSERT_OR_EDIT.equals(action)) {
            fillFromExtras(intent.getExtras());
            askNewOrExisting();
        } else {
            fillFromExtras(intent.getExtras());
        }
        if (mPhones.getChildCount() == 0) {
            addRow(mPhones, true, -1, "", Phone.TYPE_MOBILE);
        }
        if (mEmails.getChildCount() == 0) {
            addRow(mEmails, false, -1, "", Email.TYPE_HOME);
        }
    }

    // --- INSERT_OR_EDIT ---------------------------------------------------------------------

    private void askNewOrExisting() {
        new AlertDialog.Builder(this)
                .setItems(new String[] {getString(R.string.create_new),
                        getString(R.string.add_to_existing)},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (which == 1) {
                                    startActivityForResult(new Intent(Intent.ACTION_PICK,
                                            Contacts.CONTENT_URI, ContactEditActivity.this,
                                            ContactsActivity.class), REQUEST_PICK_EXISTING);
                                }
                            }
                        })
                .show();
    }

    private void fillFromExtras(Bundle x) {
        if (x == null) {
            return;
        }
        final String name = x.getString(ContactsContract.Intents.Insert.NAME);
        if (!TextUtils.isEmpty(name)) {
            final int space = name.lastIndexOf(' ');
            if (space > 0) {
                mFirst.setText(name.substring(0, space));
                mLast.setText(name.substring(space + 1));
            } else {
                mFirst.setText(name);
            }
        }
        final String company = x.getString(ContactsContract.Intents.Insert.COMPANY);
        if (company != null) {
            mCompany.setText(company);
        }
        final String notes = x.getString(ContactsContract.Intents.Insert.NOTES);
        if (notes != null) {
            mNote.setText(notes);
        }
        final String postal = x.getString(ContactsContract.Intents.Insert.POSTAL);
        if (postal != null) {
            mAddress.setText(postal);
        }
        final String[][] phones = {
                {ContactsContract.Intents.Insert.PHONE, ContactsContract.Intents.Insert.PHONE_TYPE},
                {ContactsContract.Intents.Insert.SECONDARY_PHONE,
                        ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE},
                {ContactsContract.Intents.Insert.TERTIARY_PHONE,
                        ContactsContract.Intents.Insert.TERTIARY_PHONE_TYPE},
        };
        for (String[] p : phones) {
            final String number = x.getString(p[0]);
            if (!TextUtils.isEmpty(number)) {
                addRow(mPhones, true, -1, number, typeExtra(x, p[1], Phone.TYPE_MOBILE));
            }
        }
        final String[][] emails = {
                {ContactsContract.Intents.Insert.EMAIL, ContactsContract.Intents.Insert.EMAIL_TYPE},
                {ContactsContract.Intents.Insert.SECONDARY_EMAIL,
                        ContactsContract.Intents.Insert.SECONDARY_EMAIL_TYPE},
        };
        for (String[] e : emails) {
            final String mail = x.getString(e[0]);
            if (!TextUtils.isEmpty(mail)) {
                addRow(mEmails, false, -1, mail, typeExtra(x, e[1], Email.TYPE_HOME));
            }
        }
    }

    private static int typeExtra(Bundle x, String key, int fallback) {
        final Object v = x.get(key);
        return v instanceof Integer ? (Integer) v : fallback;
    }

    // --- load an existing contact -------------------------------------------------------------

    private void load(final Uri contactUri) {
        new AsyncTask<Void, Void, Boolean>() {
            private final ArrayList<Object[]> mRows = new ArrayList<>();
            private Bitmap mBitmap;

            @Override
            protected Boolean doInBackground(Void... params) {
                final Uri contact = Contacts.lookupContact(getContentResolver(), contactUri);
                if (contact == null) {
                    return false;
                }
                final long contactId = ContentUris.parseId(contact);
                // edit the first raw contact (the one from the preferred account)
                try (Cursor c = getContentResolver().query(RawContacts.CONTENT_URI,
                        new String[] {RawContacts._ID}, RawContacts.CONTACT_ID + "=? AND "
                                + RawContacts.DELETED + "=0",
                        new String[] {String.valueOf(contactId)}, null)) {
                    if (c == null || !c.moveToFirst()) {
                        return false;
                    }
                    mRawContactId = c.getLong(0);
                }
                try (Cursor c = getContentResolver().query(Data.CONTENT_URI, new String[] {
                        Data._ID, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4,
                        Data.DATA5, Data.DATA15}, Data.RAW_CONTACT_ID + "=?",
                        new String[] {String.valueOf(mRawContactId)}, null)) {
                    while (c != null && c.moveToNext()) {
                        final String mime = c.getString(1);
                        if (Photo.CONTENT_ITEM_TYPE.equals(mime)) {
                            final byte[] blob = c.getBlob(7);
                            mPhotoRow = c.getLong(0);
                            if (blob != null) {
                                mBitmap = BitmapFactory.decodeByteArray(blob, 0, blob.length);
                            }
                            continue;
                        }
                        mRows.add(new Object[] {c.getLong(0), mime, c.getString(2),
                                c.isNull(3) ? 0 : c.getInt(3), c.getString(4), c.getString(5),
                                c.getString(6)});
                    }
                }
                // the full size photo, when there is one
                try (InputStream in = Contacts.openContactPhotoInputStream(getContentResolver(),
                        contact, true)) {
                    if (in != null) {
                        mBitmap = BitmapFactory.decodeStream(in);
                    }
                } catch (Exception e) {
                    // keep the thumbnail
                }
                return true;
            }

            @Override
            protected void onPostExecute(Boolean ok) {
                if (!ok) {
                    Toast.makeText(ContactEditActivity.this, R.string.contact_missing,
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                mPhones.removeAllViews();
                mEmails.removeAllViews();
                for (Object[] r : mRows) {
                    final long id = (Long) r[0];
                    final String mime = (String) r[1];
                    final String data1 = (String) r[2];
                    final int type = (Integer) r[3];
                    switch (mime) {
                        case StructuredName.CONTENT_ITEM_TYPE:
                            mSingleRows.put(mime, id); // the name fields come from loadName()
                            break;
                        case Phone.CONTENT_ITEM_TYPE:
                            addRow(mPhones, true, id, data1, type);
                            break;
                        case Email.CONTENT_ITEM_TYPE:
                            addRow(mEmails, false, id, data1, type);
                            break;
                        case Organization.CONTENT_ITEM_TYPE:
                            mSingleRows.put(mime, id);
                            mCompany.setText(data1);
                            break;
                        case StructuredPostal.CONTENT_ITEM_TYPE:
                            mSingleRows.put(mime, id);
                            mAddress.setText(data1);
                            break;
                        case Website.CONTENT_ITEM_TYPE:
                            mSingleRows.put(mime, id);
                            mWebsite.setText(data1);
                            break;
                        case Note.CONTENT_ITEM_TYPE:
                            mSingleRows.put(mime, id);
                            mNote.setText(data1);
                            break;
                        case Event.CONTENT_ITEM_TYPE:
                            if (type == Event.TYPE_BIRTHDAY) {
                                mSingleRows.put(mime, id);
                                mBirthdayValue = data1;
                                mBirthday.setText(data1);
                            }
                            break;
                        default:
                            break;
                    }
                }
                loadName();
                // "add to existing contact": the values the other app wanted to save
                final Bundle add = getIntent().getBundleExtra("add");
                if (add != null) {
                    fillFromExtras(add);
                }
                if (mPhones.getChildCount() == 0) {
                    addRow(mPhones, true, -1, "", Phone.TYPE_MOBILE);
                }
                if (mEmails.getChildCount() == 0) {
                    addRow(mEmails, false, -1, "", Email.TYPE_HOME);
                }
                if (mBitmap != null) {
                    showPhoto(mBitmap);
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** given / family name of the edited raw contact */
    private void loadName() {
        try (Cursor c = getContentResolver().query(Data.CONTENT_URI, new String[] {
                StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME,
                StructuredName.DISPLAY_NAME}, Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                new String[] {String.valueOf(mRawContactId), StructuredName.CONTENT_ITEM_TYPE},
                null)) {
            if (c != null && c.moveToFirst()) {
                final String given = c.getString(0);
                final String family = c.getString(1);
                if (given == null && family == null) {
                    mFirst.setText(c.getString(2));
                } else {
                    mFirst.setText(given);
                    mLast.setText(family);
                }
            }
        }
    }

    // --- rows ---------------------------------------------------------------------------------

    private View addRow(final LinearLayout parent, boolean phone, long id, String value, int type) {
        final View row = LayoutInflater.from(this).inflate(R.layout.item_edit_row, parent, false);
        final EditText field = (EditText) row.findViewById(R.id.value);
        field.setText(value);
        field.setHint(phone ? R.string.phone : R.string.email);
        field.setInputType(phone ? android.text.InputType.TYPE_CLASS_PHONE
                : android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final Spinner spinner = (Spinner) row.findViewById(R.id.type);
        final int[] types = phone ? PHONE_TYPES : EMAIL_TYPES;
        final String[] labels = new String[types.length];
        int selected = 0;
        for (int i = 0; i < types.length; i++) {
            labels[i] = (phone ? Phone.getTypeLabel(getResources(), types[i], null)
                    : Email.getTypeLabel(getResources(), types[i], null)).toString();
            if (types[i] == type) {
                selected = i;
            }
        }
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        row.setTag(new long[] {id, phone ? 1 : 0});
        spinner.setTag(types);
        row.findViewById(R.id.remove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final long rowId = ((long[]) row.getTag())[0];
                if (rowId >= 0) {
                    mDeletedRows.add(rowId);
                }
                parent.removeView(row);
            }
        });
        parent.addView(row);
        return field;
    }

    // --- photo / birthday -----------------------------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PHOTO) {
            setPhoto(data.getData());
        } else if (requestCode == REQUEST_PICK_EXISTING) {
            // keep the values from the calling app and add them to the chosen contact
            final Bundle extras = getIntent().getExtras();
            startActivity(new Intent(Intent.ACTION_EDIT, data.getData(), this,
                    ContactEditActivity.class).putExtra("add", extras));
            finish();
        }
    }

    private void setPhoto(Uri uri) {
        try {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= PHOTO_SIDE) {
                sample *= 2;
            }
            final BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, d);
            }
            if (b == null) {
                return;
            }
            // centre square, like the contact photo is shown
            final int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap square = Bitmap.createBitmap(b, (b.getWidth() - side) / 2,
                    (b.getHeight() - side) / 2, side, side);
            if (side > PHOTO_SIDE) {
                square = Bitmap.createScaledBitmap(square, PHOTO_SIDE, PHOTO_SIDE, true);
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            square.compress(Bitmap.CompressFormat.JPEG, 90, out);
            mPhoto = out.toByteArray();
            mPhotoChanged = true;
            showPhoto(square);
        } catch (Exception e) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showPhoto(Bitmap b) {
        mPhotoView.setImageBitmap(b);
        mPhotoView.setVisibility(View.VISIBLE);
        mPhotoLetter.setVisibility(View.GONE);
    }

    private void pickBirthday() {
        final Calendar c = Calendar.getInstance();
        int y = 1990;
        int m = 0;
        int d = 1;
        if (mBirthdayValue != null && mBirthdayValue.matches("\\d{4}-\\d{2}-\\d{2}")) {
            y = Integer.parseInt(mBirthdayValue.substring(0, 4));
            m = Integer.parseInt(mBirthdayValue.substring(5, 7)) - 1;
            d = Integer.parseInt(mBirthdayValue.substring(8, 10));
        }
        new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int day) {
                mBirthdayValue = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day);
                c.set(year, month, day);
                mBirthday.setText(android.text.format.DateFormat.getLongDateFormat(
                        ContactEditActivity.this).format(c.getTime()));
            }
        }, y, m, d).show();
    }

    // --- save ---------------------------------------------------------------------------------

    private void save() {
        final String first = mFirst.getText().toString().trim();
        final String last = mLast.getText().toString().trim();
        final String company = mCompany.getText().toString().trim();
        boolean hasData = !first.isEmpty() || !last.isEmpty() || !company.isEmpty();
        for (int i = 0; i < mPhones.getChildCount() && !hasData; i++) {
            hasData = !value(mPhones.getChildAt(i)).isEmpty();
        }
        if (!hasData) {
            Toast.makeText(this, R.string.empty_contact, Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        final boolean insert = mRawContactId < 0;
        if (insert) {
            final Account account = Accounts.defaultAccount(this);
            ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, account != null ? account.type : null)
                    .withValue(RawContacts.ACCOUNT_NAME, account != null ? account.name : null)
                    .build());
        }
        // name
        single(ops, insert, StructuredName.CONTENT_ITEM_TYPE, first.isEmpty() && last.isEmpty(),
                new String[] {StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME,
                        StructuredName.DISPLAY_NAME},
                new Object[] {first, last, (first + " " + last).trim()});
        single(ops, insert, Organization.CONTENT_ITEM_TYPE, company.isEmpty(),
                new String[] {Organization.COMPANY}, new Object[] {company});
        final String address = mAddress.getText().toString().trim();
        single(ops, insert, StructuredPostal.CONTENT_ITEM_TYPE, address.isEmpty(),
                new String[] {StructuredPostal.FORMATTED_ADDRESS, StructuredPostal.TYPE},
                new Object[] {address, StructuredPostal.TYPE_HOME});
        final String website = mWebsite.getText().toString().trim();
        single(ops, insert, Website.CONTENT_ITEM_TYPE, website.isEmpty(),
                new String[] {Website.URL}, new Object[] {website});
        final String note = mNote.getText().toString().trim();
        single(ops, insert, Note.CONTENT_ITEM_TYPE, note.isEmpty(),
                new String[] {Note.NOTE}, new Object[] {note});
        single(ops, insert, Event.CONTENT_ITEM_TYPE, mBirthdayValue == null,
                new String[] {Event.START_DATE, Event.TYPE},
                new Object[] {mBirthdayValue, Event.TYPE_BIRTHDAY});

        multi(ops, insert, mPhones, Phone.CONTENT_ITEM_TYPE, Phone.NUMBER, Phone.TYPE);
        multi(ops, insert, mEmails, Email.CONTENT_ITEM_TYPE, Email.ADDRESS, Email.TYPE);
        for (long id : mDeletedRows) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Data.CONTENT_URI, id)).build());
        }
        if (mPhotoChanged && mPhoto != null) {
            if (insert || mPhotoRow < 0) {
                ops.add(dataInsert(insert).withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE)
                        .withValue(Photo.PHOTO, mPhoto).build());
            } else {
                ops.add(ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(Data.CONTENT_URI, mPhotoRow))
                        .withValue(Photo.PHOTO, mPhoto).build());
            }
        }
        try {
            final ContentProviderResult[] res = getContentResolver()
                    .applyBatch(ContactsContract.AUTHORITY, ops);
            if (insert && res.length > 0 && res[0].uri != null) {
                final Uri contact = RawContacts.getContactLookupUri(getContentResolver(),
                        res[0].uri);
                setResult(RESULT_OK, new Intent().setData(contact));
                if (contact != null && Intent.ACTION_INSERT.equals(getIntent().getAction())
                        && getCallingActivity() == null) {
                    startActivity(new Intent(Intent.ACTION_VIEW, contact, this,
                            ContactDetailActivity.class));
                }
            } else {
                setResult(RESULT_OK, new Intent().setData(mContactUri));
            }
            finish();
        } catch (Exception e) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
        }
    }

    private ContentProviderOperation.Builder dataInsert(boolean insert) {
        final ContentProviderOperation.Builder b =
                ContentProviderOperation.newInsert(Data.CONTENT_URI);
        return insert ? b.withValueBackReference(Data.RAW_CONTACT_ID, 0)
                : b.withValue(Data.RAW_CONTACT_ID, mRawContactId);
    }

    /** one-per-contact field: insert, update or delete its row */
    private void single(ArrayList<ContentProviderOperation> ops, boolean insert, String mime,
            boolean empty, String[] cols, Object[] values) {
        final Long existing = mSingleRows.get(mime);
        if (existing == null) {
            if (empty) {
                return;
            }
            final ContentProviderOperation.Builder b = dataInsert(insert)
                    .withValue(Data.MIMETYPE, mime);
            for (int i = 0; i < cols.length; i++) {
                b.withValue(cols[i], values[i]);
            }
            ops.add(b.build());
        } else if (empty) {
            ops.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(Data.CONTENT_URI, existing)).build());
        } else {
            final ContentProviderOperation.Builder b = ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(Data.CONTENT_URI, existing));
            for (int i = 0; i < cols.length; i++) {
                b.withValue(cols[i], values[i]);
            }
            ops.add(b.build());
        }
    }

    private void multi(ArrayList<ContentProviderOperation> ops, boolean insert,
            LinearLayout rows, String mime, String valueCol, String typeCol) {
        for (int i = 0; i < rows.getChildCount(); i++) {
            final View row = rows.getChildAt(i);
            final String value = value(row);
            final long id = ((long[]) row.getTag())[0];
            final Spinner spinner = (Spinner) row.findViewById(R.id.type);
            final int type = ((int[]) spinner.getTag())[spinner.getSelectedItemPosition()];
            if (value.isEmpty()) {
                if (id >= 0) {
                    ops.add(ContentProviderOperation.newDelete(
                            ContentUris.withAppendedId(Data.CONTENT_URI, id)).build());
                }
                continue;
            }
            if (id < 0) {
                ops.add(dataInsert(insert).withValue(Data.MIMETYPE, mime)
                        .withValue(valueCol, value).withValue(typeCol, type).build());
            } else {
                ops.add(ContentProviderOperation.newUpdate(
                        ContentUris.withAppendedId(Data.CONTENT_URI, id))
                        .withValue(valueCol, value).withValue(typeCol, type).build());
            }
        }
    }

    private static String value(View row) {
        return ((EditText) row.findViewById(R.id.value)).getText().toString().trim();
    }
}
