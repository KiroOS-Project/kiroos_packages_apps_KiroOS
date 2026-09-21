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
import android.accounts.AccountManager;
import android.content.Context;

/**
 * Where new contacts are saved: the first Google account (so they sync and survive a
 * reset), otherwise the phone itself (no account).
 */
final class Accounts {

    private Accounts() {
    }

    static Account defaultAccount(Context context) {
        try {
            final Account[] google = AccountManager.get(context).getAccountsByType("com.google");
            if (google.length > 0) {
                return google[0];
            }
        } catch (SecurityException e) {
            // no GET_ACCOUNTS: local contact
        }
        return null;
    }
}
