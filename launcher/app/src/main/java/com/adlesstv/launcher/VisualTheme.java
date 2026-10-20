package com.adlesstv.launcher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** Three local, opaque palettes. Drawables are allocated only when changing the theme. */
final class VisualTheme {
    static final int COUNT = 3;

    private static final int[] BACKGROUND = {0xFF090B10, 0xFF0A1018, 0xFF120E10};
    private static final int[] DOCK = {0xFF171B24, 0xFF122330, 0xFF2A1D20};
    private static final int[][] HERO = {
            {0xFF282A38, 0xFF171E2B, 0xFF10151E},
            {0xFF20374B, 0xFF152633, 0xFF0D1824},
            {0xFF48302F, 0xFF2B1D24, 0xFF1A141A}
    };

    private VisualTheme() { }

    static void apply(Context context, int choice, View root, View hero, View dock) {
        int index = choice >= 0 && choice < COUNT ? choice : 0;
        root.setBackgroundColor(BACKGROUND[index]);
        int radius = AppAdapter.dp(context, 22);
        GradientDrawable heroBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, HERO[index]);
        heroBackground.setCornerRadius(radius);
        hero.setBackground(heroBackground);
        GradientDrawable dockBackground = new GradientDrawable();
        dockBackground.setColor(DOCK[index]);
        dockBackground.setCornerRadius(radius);
        dock.setBackground(dockBackground);
    }
}
