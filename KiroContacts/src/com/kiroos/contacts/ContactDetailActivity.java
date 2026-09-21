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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.kiroos.ui.KiroTouch;
import com.kiroos.ui.SquircleDrawable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** One contact: photo, quick actions, all details; edit, share, favourite, ringtone, delete. */
public class ContactDetailActivity extends Activity {

    private static final int REQUEST_RINGTONE = 1;

    private static final class Item {
        String mime;
        String value;
        String label;
    }

    private static final class Detail {
        Uri contactUri;
        long id;
        String lookupKey;
        String name;
        boolean starred;
        String ringtone;
        Bitmap photo;
        final List<Item> items = new ArrayList<>();
    }

    private Detail mDetail;
    private Uri mRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        mRequested = getIntent().getData();
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btn_edit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mDetail != null) {
                    startActivity(new Intent(Intent.ACTION_EDIT, mDetail.contactUri,
                            ContactDetailActivity.this, ContactEditActivity.class));
                }
            }
        });
        findViewById(R.id.btn_star).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleStar();
            }
        });
        findViewById(R.id.btn_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    private void load() {
        final Uri requested = mRequested;
        new AsyncTask<Void, Void, Detail>() {
            @Override
            protected Detail doInBackground(Void... params) {
                try {
                    return query(requested);
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Detail d) {
                if (d == null) {
                    Toast.makeText(ContactDetailActivity.this, R.string.contact_missing,
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                mDetail = d;
                bind();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Accepts lookup / contact / raw contact / data uris. */
    private Detail query(Uri requested) {
        if (requested == null) {
            return null;
        }
        Uri contact = Contacts.lookupContact(getContentResolver(), requested);
        if (contact == null) {
            // a data row (e.g. a phone number picked elsewhere): find its contact
            try (Cursor c = getContentResolver().query(requested,
                    new String[] {Data.CONTACT_ID, Data.LOOKUP_KEY}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    contact = Contacts.getLookupUri(c.getLong(0), c.getString(1));
                }
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (contact == null) {
            return null;
        }
        final Detail d = new Detail();
        try (Cursor c = getContentResolver().query(contact, new String[] {Contacts._ID,
                Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.STARRED,
                Contacts.CUSTOM_RINGTONE}, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            d.id = c.getLong(0);
            d.lookupKey = c.getString(1);
            d.name = c.getString(2);
            d.starred = c.getInt(3) != 0;
            d.ringtone = c.getString(4);
        }
        d.contactUri = Contacts.getLookupUri(d.id, d.lookupKey);
        try (InputStream in = Contacts.openContactPhotoInputStream(getContentResolver(),
                ContactsContract.Contacts.CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(d.id)).build(), true)) {
            if (in != null) {
                d.photo = BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            d.photo = null;
        }
        try (Cursor c = getContentResolver().query(Data.CONTENT_URI, new String[] {
                Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4},
                Data.CONTACT_ID + "=?", new String[] {String.valueOf(d.id)},
                Data.IS_SUPER_PRIMARY + " DESC, " + Data.IS_PRIMARY + " DESC")) {
            while (c != null && c.moveToNext()) {
                final Item it = new Item();
                it.mime = c.getString(0);
                it.value = c.getString(1);
                if (TextUtils.isEmpty(it.value)) {
                    continue;
                }
                final int type = c.isNull(2) ? 0 : c.getInt(2);
                final String custom = c.getString(3);
                switch (it.mime) {
                    case Phone.CONTENT_ITEM_TYPE:
                        it.label = Phone.getTypeLabel(getResources(), type, custom).toString();
                        break;
                    case Email.CONTENT_ITEM_TYPE:
                        it.label = Email.getTypeLabel(getResources(), type, custom).toString();
                        break;
                    case StructuredPostal.CONTENT_ITEM_TYPE:
                        it.label = StructuredPostal.getTypeLabel(getResources(), type, custom)
                                .toString();
                        break;
                    case Event.CONTENT_ITEM_TYPE:
                        it.label = getString(Event.getTypeResource(type));
                        break;
                    case Organization.CONTENT_ITEM_TYPE:
                        it.label = getString(R.string.company);
                        if (!TextUtils.isEmpty(c.getString(4))) {
                            it.value = c.getString(4) + " · " + it.value; // title · company
                        }
                        break;
                    case Website.CONTENT_ITEM_TYPE:
                        it.label = getString(R.string.website);
                        break;
                    case Note.CONTENT_ITEM_TYPE:
                        it.label = getString(R.string.note);
                        break;
                    case Nickname.CONTENT_ITEM_TYPE:
                        it.label = getString(R.string.nickname);
                        break;
                    default:
                        continue;
                }
                d.items.add(it);
            }
        }
        return d;
    }

    private void bind() {
        final Detail d = mDetail;
        final String name = TextUtils.isEmpty(d.name) ? getString(R.string.no_name) : d.name;
        ((TextView) findViewById(R.id.name)).setText(name);
        final ImageView photo = (ImageView) findViewById(R.id.photo);
        final TextView letter = (TextView) findViewById(R.id.letter);
        photo.setClipToOutline(true);
        if (d.photo != null) {
            photo.setImageBitmap(d.photo);
            photo.setVisibility(View.VISIBLE);
        } else {
            photo.setVisibility(View.GONE);
            letter.setText(Avatars.initial(name));
        }
        ((ImageButton) findViewById(R.id.btn_star)).setImageResource(
                d.starred ? R.drawable.ic_star : R.drawable.ic_star_border);

        // quick actions for the main number / address
        final Item phone = first(Phone.CONTENT_ITEM_TYPE);
        final Item email = first(Email.CONTENT_ITEM_TYPE);
        setupAction(R.id.action_call, R.drawable.ic_call, R.string.call, phone != null,
                new Runnable() {
            @Override
            public void run() {
                call(phone.value);
            }
        });
        setupAction(R.id.action_message, R.drawable.ic_chat, R.string.message, phone != null,
                new Runnable() {
            @Override
            public void run() {
                message(phone.value);
            }
        });
        setupAction(R.id.action_email, R.drawable.ic_email, R.string.email, email != null,
                new Runnable() {
            @Override
            public void run() {
                email(email.value);
            }
        });

        final LinearLayout list = (LinearLayout) findViewById(R.id.items);
        list.removeAllViews();
        for (final Item it : d.items) {
            final View row = LayoutInflater.from(this).inflate(R.layout.item_detail, list, false);
            ((TextView) row.findViewById(R.id.value)).setText(it.value);
            ((TextView) row.findViewById(R.id.label)).setText(it.label);
            final ImageView icon = (ImageView) row.findViewById(R.id.icon);
            final ImageButton second = (ImageButton) row.findViewById(R.id.second);
            second.setVisibility(View.GONE);
            switch (it.mime) {
                case Phone.CONTENT_ITEM_TYPE:
                    icon.setImageResource(R.drawable.ic_call);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            call(it.value);
                        }
                    });
                    second.setImageResource(R.drawable.ic_chat);
                    second.setVisibility(View.VISIBLE);
                    second.setContentDescription(getString(R.string.message));
                    second.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            message(it.value);
                        }
                    });
                    break;
                case Email.CONTENT_ITEM_TYPE:
                    icon.setImageResource(R.drawable.ic_email);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            email(it.value);
                        }
                    });
                    break;
                case StructuredPostal.CONTENT_ITEM_TYPE:
                    icon.setImageResource(R.drawable.ic_place);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            open(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("geo:0,0?q=" + Uri.encode(it.value))));
                        }
                    });
                    break;
                case Website.CONTENT_ITEM_TYPE:
                    icon.setImageResource(R.drawable.ic_link);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            final String url = it.value.contains("://") ? it.value
                                    : "http://" + it.value;
                            open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        }
                    });
                    break;
                case Event.CONTENT_ITEM_TYPE:
                    icon.setImageResource(R.drawable.ic_event);
                    break;
                default:
                    icon.setImageResource(R.drawable.ic_info);
                    break;
            }
            list.addView(row);
        }
        if (d.ringtone != null) {
            final View row = LayoutInflater.from(this).inflate(R.layout.item_detail, list, false);
            final android.media.Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(d.ringtone));
            ((TextView) row.findViewById(R.id.value)).setText(r != null ? r.getTitle(this) : "");
            ((TextView) row.findViewById(R.id.label)).setText(R.string.ringtone);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_music);
            row.findViewById(R.id.second).setVisibility(View.GONE);
            list.addView(row);
        }
        KiroTouch.enter(findViewById(R.id.header), list);
    }

    private Item first(String mime) {
        for (Item it : mDetail.items) {
            if (it.mime.equals(mime)) {
                return it;
            }
        }
        return null;
    }

    private void setupAction(int id, int icon, int label, boolean enabled, final Runnable action) {
        final View v = findViewById(id);
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
        final ImageView iv = (ImageView) v.findViewById(R.id.action_icon);
        iv.setImageResource(icon);
        iv.setBackground(SquircleDrawable.button(
                getColor(R.color.kiro_surface_raised), getColor(R.color.kiro_ripple)));
        ((TextView) v.findViewById(R.id.action_label)).setText(label);
        v.setContentDescription(getText(label));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                action.run();
            }
        });
        KiroTouch.apply(v);
    }

    private void call(String number) {
        open(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)));
    }

    private void message(String number) {
        open(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)));
    }

    private void email(String address) {
        open(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)));
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleStar() {
        if (mDetail == null) {
            return;
        }
        final ContentValues v = new ContentValues();
        v.put(Contacts.STARRED, mDetail.starred ? 0 : 1);
        getContentResolver().update(mDetail.contactUri, v, null, null);
        mDetail.starred = !mDetail.starred;
        ((ImageButton) findViewById(R.id.btn_star)).setImageResource(
                mDetail.starred ? R.drawable.ic_star : R.drawable.ic_star_border);
    }

    private void showMenu(View anchor) {
        if (mDetail == null) {
            return;
        }
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.share);
        menu.getMenu().add(0, 2, 0, R.string.set_ringtone);
        menu.getMenu().add(0, 3, 0, R.string.delete);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        final Uri vcard = Uri.withAppendedPath(Contacts.CONTENT_VCARD_URI,
                                mDetail.lookupKey);
                        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                                .setType(Contacts.CONTENT_VCARD_TYPE)
                                .putExtra(Intent.EXTRA_STREAM, vcard)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                getString(R.string.share)));
                        return true;
                    case 2:
                        final Intent pick = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                                        RingtoneManager.TYPE_RINGTONE)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
                        if (mDetail.ringtone != null) {
                            pick.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    Uri.parse(mDetail.ringtone));
                        }
                        startActivityForResult(pick, REQUEST_RINGTONE);
                        return true;
                    case 3:
                        confirmDelete();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_RINGTONE && resultCode == RESULT_OK && mDetail != null) {
            final Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            final ContentValues v = new ContentValues();
            v.put(Contacts.CUSTOM_RINGTONE, picked == null || RingtoneManager.isDefault(picked)
                    ? null : picked.toString());
            getContentResolver().update(mDetail.contactUri, v, null, null);
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, mDetail.name))
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        getContentResolver().delete(mDetail.contactUri, null, null);
                        finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
