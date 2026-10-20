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

import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.provider.AlarmClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;
import java.util.Locale;

/**
 * The home's clock (Beta 5): a widget of the grid in three sizes, drawn here rather than laid
 * out with text views. The text views of Beta 4.1 took their size from a measure pass, and a
 * resize could leave them measured for the old size (cut, or nothing at all on the medium
 * size); drawn, the clock always fills the size it has on this very frame.
 *
 * Small (2 x 2): the time and a short date. Medium (4 x 2): a large time, the date and the
 * next alarm. Large (4 x 4): the time, the date and three tiles (Alarm, Timer, Stopwatch)
 * that open the Clock app there.
 */
public class HomeClockView extends View {

    private static final int SMALL = 0;
    private static final int MEDIUM = 1;
    private static final int LARGE = 2;

    private final TextPaint mTime = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint mSmall = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mTile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final RectF[] mTiles = {new RectF(), new RectF(), new RectF()};
    private final float mDp;
    private float mDownX = -1;
    private float mDownY = -1;
    private boolean mAttached;

    public HomeClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDp = getResources().getDisplayMetrics().density;
        mTime.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        mTime.setColor(0xFFFFFFFF);
        mTime.setFontFeatureSettings("tnum");
        mText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        mText.setColor(0xE6FFFFFF);
        mSmall.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mSmall.setColor(0x8CFFFFFF);
        mSmall.setLetterSpacing(0.06f);
        mTile.setColor(0x14FFFFFF);
        // readable on light wallpapers, like the icons' labels
        final float r = 6 * mDp;
        mTime.setShadowLayer(r, 0, mDp, 0x66000000);
        mText.setShadowLayer(r, 0, mDp, 0x80000000);
        setClickable(true);
        setLongClickable(true);
        setContentDescription(context.getString(R.string.clock_widget));
    }

    // --- time ticks ----------------------------------------------------------------------

    private final BroadcastReceiver mTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            invalidate();
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            final IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_LOCALE_CHANGED);
            f.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            getContext().registerReceiver(mTick, f);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAttached) {
            mAttached = false;
            getContext().unregisterReceiver(mTick);
        }
        super.onDetachedFromWindow();
    }

    // --- size ----------------------------------------------------------------------------

    private int size() {
        final int w = getWidth();
        final int h = getHeight();
        if (h > w * 0.75f) return LARGE;
        final int screen = getResources().getDisplayMetrics().widthPixels;
        return w < screen * 0.62f ? SMALL : MEDIUM;
    }

    /** the largest text size at which s fits in width, at most max */
    private static float fit(TextPaint p, String s, float width, float max) {
        p.setTextSize(max);
        final float w = p.measureText(s);
        return w <= width ? max : Math.max(1f, max * width / w);
    }

    private String time(Calendar c) {
        final boolean is24 = DateFormat.is24HourFormat(getContext());
        return DateFormat.format(is24 ? "HH:mm" : "h:mm", c).toString();
    }

    private String date(Calendar c, boolean shortForm) {
        final Locale l = getResources().getConfiguration().locale;
        final String skeleton = shortForm ? "EEEdMMM" : "EEEEdMMMM";
        final String s = DateFormat.format(DateFormat.getBestDateTimePattern(l, skeleton), c)
                .toString();
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(l) + s.substring(1);
    }

    /** the next alarm as a time ("07:30"), or null */
    private String nextAlarm() {
        final AlarmManager am = getContext().getSystemService(AlarmManager.class);
        final AlarmManager.AlarmClockInfo next = am != null ? am.getNextAlarmClock() : null;
        if (next == null) return null;
        final Calendar c = Calendar.getInstance();
        c.setTimeInMillis(next.getTriggerTime());
        final String t = time(c);
        // more than a day away: with the day
        if (next.getTriggerTime() - System.currentTimeMillis() > 24 * 3600 * 1000L) {
            return DateFormat.format("EEE", c) + " " + t;
        }
        return t;
    }

    // --- drawing -------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;
        final Calendar now = Calendar.getInstance();
        final String time = time(now);
        final float pad = 10 * mDp;
        final float inner = w - 2 * pad;
        switch (size()) {
            case SMALL: {
                final float ts = fit(mTime, time, inner, h * 0.36f);
                mTime.setTextSize(ts);
                mText.setTextSize(Math.max(11 * mDp, Math.min(14 * mDp, ts * 0.3f)));
                final String d = ellipsize(date(now, true), mText, inner);
                final float block = ts * 0.74f + 8 * mDp + mText.getTextSize();
                final float top = (h - block) / 2f;
                canvas.drawText(time, pad, top + ts * 0.74f, mTime);
                canvas.drawText(d, pad + mDp, top + block, mText);
                break;
            }
            case MEDIUM: {
                final float ts = fit(mTime, time, inner, h * 0.5f);
                mTime.setTextSize(ts);
                mText.setTextSize(Math.max(12 * mDp, Math.min(16 * mDp, ts * 0.22f)));
                final String alarm = nextAlarm();
                String line = date(now, false);
                if (alarm != null) line += "  ·  " + getContext().getString(R.string.clock_alarm_at, alarm);
                line = ellipsize(line, mText, inner);
                final float block = ts * 0.74f + 12 * mDp + mText.getTextSize();
                final float top = (h - block) / 2f;
                canvas.drawText(time, pad - ts * 0.02f, top + ts * 0.74f, mTime);
                canvas.drawText(line, pad + mDp, top + block, mText);
                break;
            }
            default: {
                final float tileH = Math.min(h * 0.26f, 72 * mDp);
                final float gap = 8 * mDp;
                final float textArea = h - tileH - 3 * pad;
                final float ts = fit(mTime, time, inner, textArea * 0.62f);
                mTime.setTextSize(ts);
                mText.setTextSize(Math.max(13 * mDp, Math.min(17 * mDp, ts * 0.2f)));
                canvas.drawText(time, pad - ts * 0.02f, pad + ts * 0.8f, mTime);
                canvas.drawText(ellipsize(date(now, false), mText, inner), pad + mDp,
                        pad + ts * 0.8f + 12 * mDp + mText.getTextSize(), mText);
                // three tiles at the bottom: Alarm (with the next one), Timer, Stopwatch
                final float tw = (inner - 2 * gap) / 3f;
                final float tt = h - pad - tileH;
                final String alarm = nextAlarm();
                final String[] labels = {
                        getContext().getString(R.string.clock_tile_alarm),
                        getContext().getString(R.string.clock_tile_timer),
                        getContext().getString(R.string.clock_tile_stopwatch),
                };
                final String[] values = {alarm != null ? alarm : "—", "", ""};
                // too small for a label over a value: a centred symbol only (no "S…")
                final boolean compact = tw < 74 * mDp || tileH < 58 * mDp;
                for (int i = 0; i < 3; i++) {
                    final float l = pad + i * (tw + gap);
                    mTiles[i].set(l, tt, l + tw, tt + tileH);
                    canvas.drawRoundRect(mTiles[i], 16 * mDp, 16 * mDp, mTile);
                    if (compact) {
                        drawTileGlyph(canvas, i, mTiles[i].centerX() - 9 * mDp,
                                mTiles[i].centerY() + 9 * mDp);
                        continue;
                    }
                    mSmall.setTextSize(10.5f * mDp);
                    final float lx = l + 10 * mDp;
                    canvas.drawText(ellipsize(labels[i].toUpperCase(), mSmall, tw - 20 * mDp),
                            lx, tt + 10 * mDp + mSmall.getTextSize(), mSmall);
                    if (!values[i].isEmpty()) {
                        final float vs = fit(mText, values[i], tw - 20 * mDp, 19 * mDp);
                        mText.setTextSize(vs);
                        canvas.drawText(values[i], lx, tt + tileH - 12 * mDp, mText);
                    } else {
                        drawTileGlyph(canvas, i, lx, tt + tileH - 12 * mDp);
                    }
                }
                break;
            }
        }
    }

    /** a small line drawing for the Alarm (bell), Timer (hourglass) and Stopwatch tiles */
    private void drawTileGlyph(Canvas c, int which, float x, float baseline) {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f * mDp);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xE6FFFFFF);
        final float s = 18 * mDp;
        final float t = baseline - s;
        if (which == 0) {
            final android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x + s * 0.15f, t + s * 0.78f);
            path.lineTo(x + s * 0.85f, t + s * 0.78f);
            path.lineTo(x + s * 0.76f, t + s * 0.62f);
            path.lineTo(x + s * 0.76f, t + s * 0.42f);
            path.cubicTo(x + s * 0.76f, t + s * 0.05f, x + s * 0.24f, t + s * 0.05f,
                    x + s * 0.24f, t + s * 0.42f);
            path.lineTo(x + s * 0.24f, t + s * 0.62f);
            path.close();
            c.drawPath(path, p);
            c.drawLine(x + s * 0.42f, t + s * 0.96f, x + s * 0.58f, t + s * 0.96f, p);
        } else if (which == 1) {
            final android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x + s * 0.2f, t);
            path.lineTo(x + s * 0.8f, t);
            path.lineTo(x + s * 0.2f, t + s);
            path.lineTo(x + s * 0.8f, t + s);
            path.close();
            c.drawPath(path, p);
        } else {
            c.drawCircle(x + s / 2f, t + s * 0.58f, s * 0.42f, p);
            c.drawLine(x + s / 2f, t + s * 0.58f, x + s / 2f, t + s * 0.36f, p);
            c.drawLine(x + s * 0.4f, t, x + s * 0.6f, t, p);
        }
    }

    private static String ellipsize(String s, TextPaint p, float width) {
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }

    // --- taps ----------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mDownX = e.getX();
            mDownY = e.getY();
        }
        return super.onTouchEvent(e);
    }

    /** the Clock app where the tap was: a tile's page, or the alarms */
    @Override
    public boolean performClick() {
        super.performClick();
        String action = AlarmClock.ACTION_SHOW_ALARMS;
        if (size() == LARGE) {
            if (mTiles[1].contains(mDownX, mDownY)) {
                action = "android.intent.action.SHOW_TIMERS"; // the Clock app handles it
            } else if (mTiles[2].contains(mDownX, mDownY)) {
                action = null; // the stopwatch: the Clock app opens on it
            }
        }
        try {
            if (action != null) {
                getContext().startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                getContext().startActivity(new Intent(Intent.ACTION_MAIN)
                        .setClassName("com.kiroos.clock", "com.kiroos.clock.ClockActivity")
                        .putExtra("tab", 3).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (ActivityNotFoundException | SecurityException e) {
            // no clock app
        }
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        invalidate();
    }
}
