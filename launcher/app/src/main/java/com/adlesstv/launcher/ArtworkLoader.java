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
    private final Context context;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<ImageView, Future<?>> requests = new IdentityHashMap<>();
    private final LruCache<String, Bitmap> cache;
    private volatile boolean closed;
    private volatile int revision;

    ArtworkLoader(Context context) {
        this.context = context.getApplicationContext();
        ActivityManager memory = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int bytes = memory != null && memory.isLowRamDevice() ? 2 * 1024 * 1024 : 4 * 1024 * 1024;
        cache = new LruCache<String, Bitmap>(bytes) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
        };
    }

    void bind(ImageView view, AppRepository.App app) {
        cancel(view);
        final int generation = revision;
        final String key = app.key() + ":" + generation;
        view.setTag(key);
        Bitmap ready = cache.get(key);
        view.setImageBitmap(ready);
        if (ready != null || closed) return;
        Future<?> request = workers.submit(() -> {
            if (Thread.currentThread().isInterrupted() || closed || revision != generation) return;
            Bitmap bitmap = render(app);
            if (Thread.currentThread().isInterrupted() || closed || revision != generation) return;
            main.post(() -> {
                if (!closed && revision == generation && key.equals(view.getTag())) {
                    cache.put(key, bitmap);
                    view.setImageBitmap(bitmap);
                    requests.remove(view);
                }
            });
        });
        requests.put(view, request);
    }

    private Bitmap render(AppRepository.App app) {
        // Every pixel is painted opaque; 565 halves the backing memory on small sticks.
        Bitmap bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.RGB_565);
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
        float scale = Math.min((banner ? 320f : 100f) / width, (banner ? 180f : 100f) / height);
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Rect old = new Rect(art.getBounds());
        art.setBounds((320 - w) / 2, (180 - h) / 2, (320 + w) / 2, (180 + h) / 2);
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
