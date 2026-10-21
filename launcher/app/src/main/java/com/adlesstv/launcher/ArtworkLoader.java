package com.adlesstv.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

/** Local artwork only: bounded bitmap cache, cancellable worker loads, no PackageManager I/O in bind. */
final class ArtworkLoader {
    private static final int WIDTH = 256;
    private static final int HEIGHT = 144;
    interface Listener { void loaded(AppRepository.App app); }

    private static final class Artwork {
        final Bitmap bitmap;
        final int accent;
        Artwork(Bitmap bitmap) { this.bitmap = bitmap; accent = sampleAccent(bitmap); }
    }

    private final Context context;
    private final Listener listener;
    private final ThreadPoolExecutor workers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<ImageView, Future<?>> requests = new IdentityHashMap<>();
    private final LruCache<String, Artwork> cache;
    private volatile boolean closed;
    private volatile int revision;

    ArtworkLoader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        ActivityManager memory = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = memory != null && memory.isLowRamDevice();
        int bytes = lowRam ? 2 * 1024 * 1024 : 4 * 1024 * 1024;
        // One decoder on low-RAM sticks keeps peak heap flat; two hide latency elsewhere.
        int threads = lowRam || Runtime.getRuntime().availableProcessors() <= 2 ? 1 : 2;
        workers = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        cache = new LruCache<String, Artwork>(bytes) {
            @Override protected int sizeOf(String key, Artwork value) { return value.bitmap.getAllocationByteCount(); }
        };
    }

    void bind(ImageView view, AppRepository.App app) {
        cancel(view);
        final int generation = revision;
        final String key = app.key() + ":" + generation;
        view.setTag(key);
        Artwork ready = cache.get(key);
        view.setImageBitmap(ready == null ? null : ready.bitmap);
        if (ready != null || closed) return;
        Future<?> request = workers.submit(() -> {
            if (Thread.currentThread().isInterrupted() || closed || revision != generation) return;
            Artwork result = new Artwork(render(app));
            if (Thread.currentThread().isInterrupted() || closed || revision != generation) return;
            main.post(() -> {
                if (!closed && revision == generation && key.equals(view.getTag())) {
                    cache.put(key, result);
                    view.setImageBitmap(result.bitmap);
                    requests.remove(view);
                    listener.loaded(app);
                }
            });
        });
        requests.put(view, request);
    }

    int accent(AppRepository.App app) {
        Artwork ready = cache.get(app.key() + ":" + revision);
        return ready == null ? 0 : ready.accent;
    }

    private static int sampleAccent(Bitmap bitmap) {
        int accent = 0, best = 0;
        for (int y = 15; y < bitmap.getHeight() - 15; y += 18) {
            for (int x = 16; x < bitmap.getWidth() - 16; x += 20) {
                int pixel = bitmap.getPixel(x, y);
                int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                int high = Math.max(r, Math.max(g, b)), low = Math.min(r, Math.min(g, b));
                int score = high - low;
                if (high < 70 || high > 225 || score < 35 || score <= best) continue;
                best = score;
                accent = Color.rgb(r, g, b);
            }
        }
        return accent;
    }

    private Bitmap render(AppRepository.App app) {
        // Every pixel is painted opaque; 565 halves the backing memory on small sticks.
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(28, 32, 40));
        PackageManager pm = context.getPackageManager();
        Drawable art = null;
        boolean banner = false;
        try {
            if (app.isInput()) {
                if (Build.VERSION.SDK_INT >= 24) art = app.input.loadIcon(context);
                if (art == null) art = context.getDrawable(R.drawable.ic_inputs);
            } else {
                ActivityInfo activity = app.info.activityInfo;
                art = sharp(pm, activity.applicationInfo, activity.getBannerResource(), BANNER_DENSITY);
                if (art == null) art = activity.loadBanner(pm);
                banner = art != null;
                if (art == null) art = sharp(pm, activity.applicationInfo, activity.getIconResource(), ICON_DENSITY);
                if (art == null) art = app.info.loadIcon(pm);
            }
        } catch (RuntimeException ignored) { /* Package may have been removed during this request. */ }
        if (art == null) art = pm.getDefaultActivityIcon();
        int width = Math.max(1, art.getIntrinsicWidth()), height = Math.max(1, art.getIntrinsicHeight());
        float box = app.isInput() ? 72f : 100f;
        float scale = Math.min((banner ? WIDTH : box) / width, (banner ? HEIGHT : box) / height);
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Rect old = new Rect(art.getBounds());
        art.setBounds((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2);
        art.draw(canvas);
        art.setBounds(old);
        return bitmap;
    }

    // A 160 x 90 dp banner is 160 x 90 px on an mdpi TV: upscaling that to 256 x 144 blurs it.
    // Decode at a density whose pixels cover the tile instead, then let Canvas scale down.
    private static final int BANNER_DENSITY = DisplayMetrics.DENSITY_XHIGH;
    private static final int ICON_DENSITY = DisplayMetrics.DENSITY_XXHIGH;

    private Drawable sharp(PackageManager pm, ApplicationInfo application, int resource, int density) {
        if (resource == 0 || application == null) return null;
        int wanted = Math.max(density, context.getResources().getDisplayMetrics().densityDpi);
        try {
            Resources resources = pm.getResourcesForApplication(application);
            return resources.getDrawableForDensity(resource, wanted, null);
        } catch (PackageManager.NameNotFoundException | RuntimeException missing) {
            return null; // Fall back to the platform loader, which also handles odd resource tables.
        }
    }

    void cancel(ImageView view) {
        Future<?> previous = requests.remove(view);
        if (previous != null) {
            previous.cancel(true);
            workers.remove((Runnable) previous);
        }
        view.setTag(null);
    }

    int revision() { return revision; }
    void invalidate() {
        revision++;
        for (ImageView view : new java.util.ArrayList<>(requests.keySet())) cancel(view);
        cache.evictAll();
    }
    /** Keep half the tiles when Home is merely hidden; drop everything when the system is short. */
    void trim(boolean critical) {
        if (critical) cache.evictAll();
        else cache.trimToSize(cache.maxSize() / 2);
    }

    void close() {
        closed = true;
        for (Future<?> request : requests.values()) request.cancel(true);
        requests.clear();
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
