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

package com.kiroos.home;

import android.content.Context;
import android.util.AttributeSet;
import android.view.DragEvent;
import android.widget.EditText;

/**
 * The drawer's search box. It never takes part in drag and drop: on Android 7 every editable
 * text view accepts any drag and pastes the dragged item's text, so an app dragged out of the
 * drawer and let go while the drawer was still stepping aside landed in the search box (its
 * key written as text, nothing placed on the home) instead of on the home.
 */
public class SearchField extends EditText {

    public SearchField(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean onDragEvent(DragEvent event) {
        return false;
    }
}
