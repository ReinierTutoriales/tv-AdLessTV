package com.adlesstv.launcher;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.widget.FrameLayout;

/** Optional system screensaver. Only updates positions while the dream is visible. */
public final class ClockDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ClockFace face;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.rgb(5, 11, 18));
        face = new ClockFace(this, frame, handler);
        setContentView(frame);
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); if (face != null) face.start(); }
    @Override public void onDreamingStopped() { if (face != null) face.stop(); super.onDreamingStopped(); }
    @Override public void onDetachedFromWindow() {
        if (face != null) face.stop();
        face = null;
        super.onDetachedFromWindow();
    }
}
