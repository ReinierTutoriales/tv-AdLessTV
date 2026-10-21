package com.adlesstv.launcher;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;

import java.util.Locale;

/** Time and date that change corner once a minute, so a still screen never burns into the panel. */
final class ClockFace {
    private static final int[] CORNERS = {Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END,
            Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START};
    private final Handler handler;
    private final LinearLayout face;
    private int slot;
    private final Runnable shift = new Runnable() {
        @Override public void run() {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) face.getLayoutParams();
            params.gravity = CORNERS[slot++ % CORNERS.length];
            face.setLayoutParams(params);
            face.setGravity((params.gravity & Gravity.END) == Gravity.END ? Gravity.END : Gravity.START);
            handler.postDelayed(this, 60_000);
        }
    };

    ClockFace(Context context, FrameLayout parent, Handler handler) {
        this.handler = handler;
        face = new LinearLayout(context);
        face.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(context);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.rgb(220, 235, 242));
        time.setTextSize(54);
        time.setIncludeFontPadding(false);
        TextClock date = new TextClock(context);
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        date.setTextColor(Color.rgb(150, 168, 180));
        date.setTextSize(20);
        face.addView(time);
        face.addView(date);
        int margin = Math.round(64 * context.getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.setMargins(margin, margin, margin, margin);
        parent.addView(face, params);
    }

    void start() { handler.removeCallbacks(shift); shift.run(); }
    void stop() { handler.removeCallbacks(shift); }
}
