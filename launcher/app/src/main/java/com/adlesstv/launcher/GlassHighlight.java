package com.adlesstv.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * One floating highlight that glides to the focused tile. It only moves on focus or scroll events
 * (no animation loop) and reuses a single view; with reduced motion it jumps instead of gliding.
 */
final class GlassHighlight implements ViewTreeObserver.OnGlobalFocusChangeListener,
        ViewTreeObserver.OnScrollChangedListener {
    private static final float FOCUS_SCALE = 1.035f;
    private final View glass;
    private final View frame;
    private final int[] frameOrigin = new int[2];
    private final int[] tileOrigin = new int[2];
    private View target;
    private boolean enabled;
    private boolean reducedMotion;

    GlassHighlight(View frame, View glass) {
        this.frame = frame;
        this.glass = glass;
    }

    void attach() {
        frame.getViewTreeObserver().addOnGlobalFocusChangeListener(this);
        frame.getViewTreeObserver().addOnScrollChangedListener(this);
    }

    void detach() {
        ViewTreeObserver observer = frame.getViewTreeObserver();
        if (!observer.isAlive()) return;
        observer.removeOnGlobalFocusChangeListener(this);
        observer.removeOnScrollChangedListener(this);
    }

    void setEnabled(boolean enabled, boolean reducedMotion) {
        this.enabled = enabled;
        this.reducedMotion = reducedMotion;
        if (!enabled) hide();
        else onGlobalFocusChanged(null, frame.findFocus());
    }

    boolean enabled() { return enabled; }

    /** Re-measure after a container animation (e.g. the catalog sliding up) has settled. */
    void refresh() {
        if (!enabled) return;
        if (target != null && glass.getVisibility() == View.VISIBLE) place(false);
        else onGlobalFocusChanged(null, frame.findFocus());
    }

    @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
        if (!enabled) return;
        target = newFocus instanceof AppAdapter.Tile ? newFocus : null;
        if (target == null) { hide(); return; }
        // Measure after the tile has been laid out and its focus scale has started.
        target.post(() -> place(!reducedMotion && glass.getVisibility() == View.VISIBLE));
    }

    @Override public void onScrollChanged() {
        if (enabled && target != null && glass.getVisibility() == View.VISIBLE) place(false);
    }

    private void hide() {
        glass.animate().cancel();
        glass.setVisibility(View.GONE);
    }

    private void place(boolean animate) {
        View tile = target;
        if (tile == null || !tile.isAttachedToWindow() || !tile.isShown() || tile.getWidth() == 0) { hide(); return; }
        frame.getLocationInWindow(frameOrigin);
        tile.getLocationInWindow(tileOrigin);
        // getLocationInWindow includes the current animated scale; use the final, settled bounds.
        float scale = reducedMotion ? 1f : FOCUS_SCALE;
        int width = Math.round(tile.getWidth() * scale), height = Math.round(tile.getHeight() * scale);
        float centerX = tileOrigin[0] - frameOrigin[0] + tile.getWidth() * tile.getScaleX() / 2f;
        float centerY = tileOrigin[1] - frameOrigin[1] + tile.getHeight() * tile.getScaleY() / 2f;
        float x = centerX - width / 2f, y = centerY - height / 2f;
        ViewGroup.LayoutParams params = glass.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            if (params instanceof FrameLayout.LayoutParams) ((FrameLayout.LayoutParams) params).setMargins(0, 0, 0, 0);
            glass.setLayoutParams(params);
        }
        glass.animate().cancel();
        if (animate) {
            glass.animate().translationX(x).translationY(y).setDuration(140).start();
        } else {
            glass.setTranslationX(x);
            glass.setTranslationY(y);
        }
        glass.setVisibility(View.VISIBLE);
    }
}
