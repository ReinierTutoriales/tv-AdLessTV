package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ResourceRegressionTest {
    @Test public void completedArtworkBindingsAreClearedAndCanReload() throws Exception {
        android.app.Instrumentation test = InstrumentationRegistry.getInstrumentation();
        Context context = test.getTargetContext();
        ResolveInfo resolve = new ResolveInfo();
        resolve.activityInfo = context.getPackageManager().getActivityInfo(
                new ComponentName(context, MainActivity.class), 0);
        AppRepository.App app = new AppRepository.App(new ComponentName(context, MainActivity.class),
                "AdLessTV", resolve);
        ArtworkLoader[] loader = new ArtworkLoader[1];
        ImageView[] image = new ImageView[1];
        test.runOnMainSync(() -> {
            loader[0] = new ArtworkLoader(context, ignored -> { });
            image[0] = new ImageView(context);
            loader[0].bind(image[0], app);
        });
        try {
            awaitArtwork(test, image[0]);
            test.runOnMainSync(() -> {
                loader[0].release();
                assertNull("A completed request must release its bound bitmap", image[0].getDrawable());
                loader[0].bind(image[0], app);
            });
            awaitArtwork(test, image[0]);
        } finally {
            test.runOnMainSync(loader[0]::close);
        }
    }

    private static void awaitArtwork(android.app.Instrumentation test, ImageView image) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            test.runOnMainSync(() -> ready.set(image.getDrawable() != null));
            if (ready.get()) return;
            SystemClock.sleep(25);
        }
        fail("Local artwork did not load");
    }

    @Test public void whitespaceDoesNotDuplicateThemeEntries() throws Exception {
        HomeConfig config = new HomeConfig();
        config.apply(new JSONObject("{\"dock_apps\":[\" com.example.app \",\"com.example.app\",\"  \"],"
                + "\"hidden_apps\":[\" com.example.hidden \",\"com.example.hidden\"]}"));
        assertEquals(1, config.dockApps.size());
        assertEquals("com.example.app", config.dockApps.get(0));
        assertEquals(1, config.hiddenApps.size());
    }

    @Test public void wallpaperRejectsInvalidScreenDimensions() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("wallpaper-", ".jpg", context.getCacheDir());
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.RGB_565);
        try {
            try (FileOutputStream stream = new FileOutputStream(file)) {
                assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 85, stream));
            }
            assertNull(Wallpaper.decode(file, 0, 0, false));
            assertNull(Wallpaper.decode(file, -1, 32, false));
            Bitmap valid = Wallpaper.decode(file, 16, 16, false);
            assertNotNull(valid);
            valid.recycle();
        } finally {
            source.recycle();
            assertTrue(file.delete());
        }
    }
}
