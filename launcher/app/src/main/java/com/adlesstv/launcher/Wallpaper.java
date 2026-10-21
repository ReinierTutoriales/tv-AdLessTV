package com.adlesstv.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** Decodes a theme wallpaper bounded to the screen: never larger than needed, RGB_565 by default. */
final class Wallpaper {
    private static final long MAX_FILE = 10L * 1024 * 1024;
    private static final long MAX_PIXELS = 3840L * 2160;

    private Wallpaper() { }

    static Bitmap decode(File file, int screenWidth, int screenHeight, boolean trueColor) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_FILE) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        // Largest power-of-two step that still covers the screen for a centre crop.
        while (bounds.outWidth / (sample * 2) >= screenWidth && bounds.outHeight / (sample * 2) >= screenHeight) {
            sample *= 2;
        }
        if ((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > MAX_PIXELS) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = trueColor ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        try { return BitmapFactory.decodeFile(file.getPath(), options); }
        catch (OutOfMemoryError tooLarge) { return null; }
    }
}
