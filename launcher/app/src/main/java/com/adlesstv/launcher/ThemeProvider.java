package com.adlesstv.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets `adb shell content write --uri content://com.adlesstv.launcher.theme/config.json` install a
 * theme on firmware that locks the shell out of Android/data. Only the ADB shell (or root) may
 * call it, and only the two theme file names are accepted: no other app can change Home.
 */
public final class ThemeProvider extends ContentProvider {
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() { return true; }

    private static void requireShell() {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0 && caller != Process.myUid())
            throw new SecurityException("Only the ADB shell may change the theme");
    }

    private File target(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!HomeConfig.CONFIG.equals(name) && !HomeConfig.WALLPAPER.equals(name))
            throw new FileNotFoundException("Unsupported theme file");
        File dir = new File(HomeConfig.deviceStorage(getContext()), HomeConfig.THEME_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("Theme folder unavailable");
        return new File(dir, name);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        requireShell();
        File file = target(uri);
        if (mode.contains("w")) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        requireShell();
        try { return target(uri).delete() ? 1 : 0; }
        catch (FileNotFoundException missing) { return 0; }
    }

    @Override public String getType(Uri uri) {
        return HomeConfig.WALLPAPER.equals(uri.getLastPathSegment()) ? "image/jpeg" : "application/json";
    }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { return 0; }
}
