package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ResourceRegressionTest {
    @Test public void staleArtworkCallbackCannotReplaceAReboundTile() throws Exception {
        android.app.Instrumentation test = InstrumentationRegistry.getInstrumentation();
        Context context = test.getTargetContext();
        ResolveInfo resolve = new ResolveInfo();
        resolve.activityInfo = context.getPackageManager().getActivityInfo(
                new ComponentName(context, MainActivity.class), 0);
        AppRepository.App app = new AppRepository.App(new ComponentName(context, MainActivity.class),
                "AdLessTV", resolve);
        ArtworkLoader[] loader = new ArtworkLoader[1];
        ImageView[] image = new ImageView[1];
        AtomicBoolean staleImageShown = new AtomicBoolean(true);
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        Field requestsField = ArtworkLoader.class.getDeclaredField("requests");
        requestsField.setAccessible(true);
        Field workersField = ArtworkLoader.class.getDeclaredField("workers");
        workersField.setAccessible(true);
        try {
            // Keep the main thread occupied until the first worker has queued its callback.
            test.runOnMainSync(() -> {
                loader[0] = new ArtworkLoader(context, ignored -> { });
                image[0] = new ImageView(context);
                loader[0].bind(image[0], app);
                try {
                    @SuppressWarnings("unchecked")
                    Map<ImageView, Future<?>> requests =
                            (Map<ImageView, Future<?>>) requestsField.get(loader[0]);
                    Future<?> first = requests.get(image[0]);
                    long deadline = SystemClock.uptimeMillis() + 10_000;
                    while (!first.isDone() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10);
                    assertTrue("First render did not finish", first.isDone());
                    ThreadPoolExecutor workers = (ThreadPoolExecutor) workersField.get(loader[0]);
                    CountDownLatch occupied = new CountDownLatch(workers.getCorePoolSize());
                    for (int i = 0; i < workers.getCorePoolSize(); i++) workers.execute(() -> {
                        occupied.countDown();
                        try { releaseWorkers.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    });
                    assertTrue("Workers did not pause", occupied.await(10, TimeUnit.SECONDS));
                    loader[0].bind(image[0], app);
                    new Handler(Looper.getMainLooper()).post(() -> {
                        staleImageShown.set(hasArtwork(image[0]));
                        checked.countDown();
                    });
                } catch (IllegalAccessException | InterruptedException e) { throw new AssertionError(e); }
            });
            assertTrue(checked.await(10, TimeUnit.SECONDS));
            assertFalse("A stale callback must not bind artwork from the previous request", staleImageShown.get());
            releaseWorkers.countDown();
            awaitArtwork(test, image[0]);
        } finally {
            releaseWorkers.countDown();
            if (loader[0] != null) test.runOnMainSync(loader[0]::close);
        }
    }

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
            test.runOnMainSync(() -> ready.set(hasArtwork(image)));
            if (ready.get()) return;
            SystemClock.sleep(25);
        }
        fail("Local artwork did not load");
    }

    private static boolean hasArtwork(ImageView image) {
        Drawable drawable = image.getDrawable();
        return drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null;
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
