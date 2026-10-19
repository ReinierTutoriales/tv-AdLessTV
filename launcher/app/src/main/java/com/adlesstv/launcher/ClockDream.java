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
    private TextClock clock;
    private int slot;
    private final Runnable shift = new Runnable() {
        @Override public void run() {
            if (frame != null && clock != null) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) clock.getLayoutParams();
                int[] positions = {Gravity.TOP | Gravity.START, Gravity.TOP | Gravity.END,
                        Gravity.BOTTOM | Gravity.END, Gravity.BOTTOM | Gravity.START};
                params.gravity = positions[slot++ % positions.length];
                clock.setLayoutParams(params);
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
        clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.rgb(220, 235, 242));
        clock.setTextSize(54);
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
