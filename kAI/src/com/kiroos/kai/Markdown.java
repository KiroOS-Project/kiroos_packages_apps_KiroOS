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

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → styled text for the chat: headings, bold, italic, strikethrough, inline code,
 * fenced code blocks, bullet and numbered lists, block quotes, links and horizontal rules.
 * Tolerant of half-written input (it renders while the answer streams in).
 */
final class Markdown {

    private static final Pattern INLINE = Pattern.compile(
            "(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(__[^_\\n]+__)|(\\*[^*\\n]+\\*)|(_[^_\\n]+_)"
                    + "|(~~[^~\\n]+~~)|(\\[[^\\]\\n]+\\]\\([^)\\s]+\\))");
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)[.)]\\s+(.*)");

    private final int mCodeBg;
    private final int mCodeFg;
    private final int mAccent;
    private final int mMuted;

    Markdown(int codeBg, int codeFg, int accent, int muted) {
        mCodeBg = codeBg;
        mCodeFg = codeFg;
        mAccent = accent;
        mMuted = muted;
    }

    CharSequence render(String md) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        final String[] lines = md.replace("\r", "").split("\n", -1);
        boolean inCode = false;
        int codeStart = 0;
        for (int i = 0; i < lines.length; i++) {
            final String line = lines[i];
            final String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    codeStart = out.length();
                } else {
                    inCode = false;
                    styleCode(out, codeStart, out.length());
                }
                continue;
            }
            if (inCode) {
                out.append(line).append('\n');
                continue;
            }
            final int start = out.length();
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') {
                    level++;
                }
                final String text = trimmed.substring(level).trim();
                inline(out, text);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(level <= 1 ? 1.35f : level == 2 ? 1.2f : 1.08f),
                        start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")
                    || trimmed.startsWith("+ ")) {
                final int indent = line.indexOf(trimmed.charAt(0));
                inline(out, trimmed.substring(2));
                out.setSpan(new BulletSpan(24 + indent * 8, mAccent), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (NUMBERED.matcher(trimmed).matches()) {
                final Matcher m = NUMBERED.matcher(trimmed);
                m.matches();
                out.append(m.group(1)).append(". ");
                out.setSpan(new ForegroundColorSpan(mAccent), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                inline(out, m.group(2));
                out.setSpan(new LeadingMarginSpan.Standard(12, 36), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.startsWith(">")) {
                inline(out, trimmed.substring(1).trim());
                out.setSpan(new QuoteSpan(mAccent), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(mMuted), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                out.append("────────────");
                out.setSpan(new ForegroundColorSpan(mMuted), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                inline(out, line);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        if (inCode) {
            // a code block still being written: style what is there
            styleCode(out, codeStart, out.length());
        }
        // no trailing blank lines
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private void styleCode(SpannableStringBuilder out, int start, int end) {
        if (end <= start) {
            return;
        }
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(mCodeBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(mCodeFg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.9f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void inline(SpannableStringBuilder out, String text) {
        final Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            final int s = out.length();
            final String tok = m.group();
            if (m.group(1) != null) {
                out.append(tok.substring(1, tok.length() - 1));
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(mCodeBg), s, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(mCodeFg), s, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null || m.group(3) != null) {
                out.append(tok.substring(2, tok.length() - 2));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(4) != null || m.group(5) != null) {
                out.append(tok.substring(1, tok.length() - 1));
                out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(6) != null) {
                out.append(tok.substring(2, tok.length() - 2));
                out.setSpan(new StrikethroughSpan(), s, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                final int close = tok.indexOf("](");
                final String label = tok.substring(1, close);
                final String url = tok.substring(close + 2, tok.length() - 1);
                out.append(label);
                out.setSpan(new URLSpan(url), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        out.append(text, last, text.length());
    }
}
