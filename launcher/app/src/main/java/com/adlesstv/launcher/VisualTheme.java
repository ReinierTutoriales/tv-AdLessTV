package com.adlesstv.launcher;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;

/** One opaque base and a native radial accent; no background bitmap or blur. */
final class VisualTheme {
    static final int COUNT = 3;
    private static final int[] BACKGROUND = {0xFF090B10, 0xFF0A1018, 0xFF120E10};
    private static final int[] ACCENT = {0xFF454B63, 0xFF36728E, 0xFF9A6555};

    private VisualTheme() { }

    static void apply(Context context, int choice, View root, View hero, View dock) {
        hero.setBackground(null);
        dock.setBackground(null);
        applyAccent(context, choice, root, 0);
    }

    static void applyAccent(Context context, int choice, View root, int accent) {
        int index = choice >= 0 && choice < COUNT ? choice : 0;
        LayerDrawable background;
        if (root.getBackground() instanceof LayerDrawable) {
            background = (LayerDrawable) root.getBackground();
        } else {
            GradientDrawable glow = new GradientDrawable();
            glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glow.setGradientCenter(0.86f, 0.30f);
            glow.setGradientRadius(AppAdapter.dp(context, 570));
            background = new LayerDrawable(new Drawable[]{new ColorDrawable(), glow});
            root.setBackground(background);
        }
        ((ColorDrawable) background.getDrawable(0)).setColor(BACKGROUND[index]);
        int hue = accent == 0 ? ACCENT[index] : accent;
        ((GradientDrawable) background.getDrawable(1)).setColors(
                new int[]{0x50000000 | (hue & 0xFFFFFF), hue & 0xFFFFFF});
    }
}
