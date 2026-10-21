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
import java.util.HashMap;
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
    interface AccentTarget { void loaded(int color); }

    private static final class Artwork {
        final Bitmap bitmap;
        final int accent;
        Artwork(Bitmap bitmap) { this.bitmap = bitmap; accent = sampleAccent(bitmap); }
    }

    private final Context context;
    private final Listener listener;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 15L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final class Load {
        final Map<ImageView, AccentTarget> targets = new IdentityHashMap<>();
        Future<?> future;
    }
    // These maps are owned by the main thread. Dock and library share a load per component.
    private final Map<ImageView, Load> requests = new IdentityHashMap<>();
    private final Map<String, Load> loads = new HashMap<>();
    private final LruCache<String, Artwork> cache;
    private volatile boolean closed;
    private volatile int revision;

    ArtworkLoader(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        workers.allowCoreThreadTimeOut(true);
        this.listener = listener;
        ActivityManager memory = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        int bytes = memory != null && memory.isLowRamDevice() ? 2 * 1024 * 1024 : 4 * 1024 * 1024;
        cache = new LruCache<String, Artwork>(bytes) {
            @Override protected int sizeOf(String key, Artwork value) { return value.bitmap.getAllocationByteCount(); }
        };
    }

    void bind(ImageView view, AppRepository.App app, AccentTarget target) {
        cancel(view);
        final int generation = revision;
        final String key = app.key() + ":" + generation;
        view.setTag(key);
        Artwork ready = cache.get(key);
        view.setImageBitmap(ready == null ? null : ready.bitmap);
        target.loaded(ready == null ? 0 : ready.accent);
        if (ready != null || closed) return;
        Load loading = loads.get(key);
        if (loading != null) {
            loading.targets.put(view, target);
            requests.put(view, loading);
            return;
        }
        Load load = new Load();
        load.targets.put(view, target);
        loads.put(key, load);
        requests.put(view, load);
        load.future = workers.submit(() -> {
            if (Thread.currentThread().isInterrupted() || closed || revision != generation) return;
            Artwork result = new Artwork(render(app));
            if (Thread.currentThread().isInterrupted() || closed || revision != generation) return;
            main.post(() -> {
                if (closed || revision != generation || loads.get(key) != load) return;
                loads.remove(key);
                cache.put(key, result);
                for (Map.Entry<ImageView, AccentTarget> binding : load.targets.entrySet()) {
                    ImageView image = binding.getKey();
                    if (requests.get(image) != load || !key.equals(image.getTag())) continue;
                    requests.remove(image);
                    image.setImageBitmap(result.bitmap);
                    binding.getValue().loaded(result.accent);
                }
                load.targets.clear();
                listener.loaded(app);
            });
        });
    }

    int accent(AppRepository.App app) {
        Artwork ready = cache.get(app.key() + ":" + revision);
        return ready == null ? 0 : ready.accent;
    }

    Bitmap bitmap(AppRepository.App app) {
        Artwork ready = cache.get(app.key() + ":" + revision);
        return ready == null ? null : ready.bitmap;
    }

    private static int sampleAccent(Bitmap bitmap) {
        int accent = 0, best = 0;
        for (int y = 8; y < bitmap.getHeight() - 8; y += 8) {
            for (int x = 8; x < bitmap.getWidth() - 8; x += 8) {
                int pixel = bitmap.getPixel(x, y);
                if (Color.alpha(pixel) < 200) continue;
                int r = Color.red(pixel), g = Color.green(pixel), b = Color.blue(pixel);
                int high = Math.max(r, Math.max(g, b)), low = Math.min(r, Math.min(g, b));
                int score = high - low;
                if (high < 70 || score < 35 || score <= best) continue;
                best = score;
                accent = Color.rgb(r, g, b);
            }
        }
        return accent;
    }

    private Bitmap render(AppRepository.App app) {
        // Android TV supplies 16:9 artwork in the installed APK. Prefer it over a phone icon.
        // The output is opaque: RGB_565 is sufficient and costs 72 KiB per card.
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFF161B25);
        Drawable art = null;
        boolean banner = false;
        try {
            art = app.info.activityInfo.loadBanner(context.getPackageManager());
            if (art == null) art = app.info.activityInfo.applicationInfo.loadBanner(context.getPackageManager());
            if (art != null) {
                float ratio = (float) art.getIntrinsicWidth() / Math.max(1, art.getIntrinsicHeight());
                // Malformed/square vendor banners should use the icon fallback, never be stretched.
                banner = ratio >= 1.45f && ratio <= 2.1f;
            }
            if (!banner) art = app.info.loadIcon(context.getPackageManager());
        } catch (RuntimeException ignored) { /* Package may have changed during this request. */ }
        if (art == null) art = context.getPackageManager().getDefaultActivityIcon();
        Drawable.ConstantState constant = art.getConstantState();
        if (constant != null) art = constant.newDrawable(context.getResources()).mutate();
        int width = Math.max(1, art.getIntrinsicWidth()), height = Math.max(1, art.getIntrinsicHeight());
        float scale = banner ? Math.min((float) WIDTH / width, (float) HEIGHT / height)
                : Math.min(76f / width, 76f / height);
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        Rect old = new Rect(art.getBounds());
        art.setBounds((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2);
        art.draw(canvas);
        art.setBounds(old);
        return bitmap;
    }

    void cancel(ImageView view) {
        Load previous = requests.remove(view);
        if (previous != null) {
            previous.targets.remove(view);
            if (previous.targets.isEmpty()) {
                loads.values().remove(previous);
                previous.future.cancel(true);
                workers.remove((Runnable) previous.future);
            }
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
        for (ImageView view : new java.util.ArrayList<>(requests.keySet())) cancel(view);
        loads.clear();
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
