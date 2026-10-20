package com.adlesstv.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
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
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<ImageView, Future<?>> requests = new IdentityHashMap<>();
    private final LruCache<String, Artwork> cache;
    private volatile boolean closed;
    private volatile int revision;

    ArtworkLoader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        ActivityManager memory = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int bytes = memory != null && memory.isLowRamDevice() ? 2 * 1024 * 1024 : 4 * 1024 * 1024;
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
        Drawable art = null;
        boolean banner = false;
        try {
            art = app.info.activityInfo.loadBanner(context.getPackageManager());
            banner = art != null;
            if (art == null) art = app.info.loadIcon(context.getPackageManager());
        } catch (RuntimeException ignored) { /* Package may have been removed during this request. */ }
        if (art == null) art = context.getPackageManager().getDefaultActivityIcon();
        int width = Math.max(1, art.getIntrinsicWidth()), height = Math.max(1, art.getIntrinsicHeight());
        float scale = Math.min((banner ? WIDTH : 100f) / width, (banner ? HEIGHT : 100f) / height);
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Rect old = new Rect(art.getBounds());
        art.setBounds((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2);
        art.draw(canvas);
        art.setBounds(old);
        return bitmap;
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
    void trim() { cache.evictAll(); }

    void close() {
        closed = true;
        for (Future<?> request : requests.values()) request.cancel(true);
        requests.clear();
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
