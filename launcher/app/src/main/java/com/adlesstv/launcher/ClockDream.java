package com.adlesstv.launcher;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextClock;

/** Optional system screensaver. Only updates positions while the dream is visible. */
public final class ClockDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout frame;
    private android.widget.LinearLayout clock;
    private int slot;
    private final Runnable shift = new Runnable() {
        @Override public void run() {
            if (frame != null && clock != null) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clock.getLayoutParams();
                int[] positions = {Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END,
                        Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START};
                params.gravity = positions[slot++ % positions.length];
                clock.setLayoutParams(params);
                clock.setGravity((params.gravity & Gravity.END) == Gravity.END ? Gravity.END : Gravity.START);
                handler.postDelayed(this, 60_000);
            }
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(5, 11, 18));
        clock = new android.widget.LinearLayout(this);
        clock.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm a");
        time.setFormat24Hour("HH:mm");
        time.setTextColor(Color.rgb(220, 235, 242));
        time.setTextSize(54);
        time.setIncludeFontPadding(false);
        TextClock date = new TextClock(this);
        // Localized "Wednesday, October 21" from the system pattern for this locale.
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(
                java.util.Locale.getDefault(), "EEEEMMMMd");
        date.setFormat12Hour(pattern);
        date.setFormat24Hour(pattern);
        date.setTextColor(Color.rgb(150, 168, 180));
        date.setTextSize(20);
        clock.addView(time);
        clock.addView(date);
        int margin = Math.round(64 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.setMargins(margin, margin, margin, margin);
        frame.addView(clock, params);
        setContentView(frame);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); shift.run(); }
    @Override public void onDreamingStopped() { handler.removeCallbacks(shift); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() {
        handler.removeCallbacks(shift);
        frame = null;
        clock = null;
        super.onDetachedFromWindow();
    }
}
