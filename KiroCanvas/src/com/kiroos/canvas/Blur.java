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

package com.kiroos.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;

/**
 * Gaussian blur for the lock screen. RenderScript's blur reaches 25 px; stronger blurs run on
 * a smaller copy and are scaled back up, which also keeps the live preview quick. amount 0-100.
 */
final class Blur {

    private Blur() {
    }

    static Bitmap apply(Context context, Bitmap src, int amount) {
        if (amount <= 0) {
            return src.copy(Bitmap.Config.ARGB_8888, false);
        }
        // 1-100 -> downscale 1/1.5 .. 1/8 and a 6-25 px kernel: from soft to frosted glass
        final float t = Math.min(100, amount) / 100f;
        final float down = 1.5f + 6.5f * t;
        final float radius = 6f + 19f * t;
        final int w = Math.max(1, Math.round(src.getWidth() / down));
        final int h = Math.max(1, Math.round(src.getHeight() / down));
        final Bitmap small = Bitmap.createScaledBitmap(src, w, h, true)
                .copy(Bitmap.Config.ARGB_8888, true);
        final RenderScript rs = RenderScript.create(context);
        try {
            final Allocation in = Allocation.createFromBitmap(rs, small);
            final Allocation out = Allocation.createTyped(rs, in.getType());
            final ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            blur.setRadius(radius);
            blur.setInput(in);
            blur.forEach(out);
            // a second pass for the strongest settings: smoother, no visible boxes
            if (t > 0.6f) {
                blur.setInput(out);
                blur.forEach(in);
                in.copyTo(small);
            } else {
                out.copyTo(small);
            }
            in.destroy();
            out.destroy();
            blur.destroy();
        } finally {
            rs.destroy();
        }
        return Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
    }
}
