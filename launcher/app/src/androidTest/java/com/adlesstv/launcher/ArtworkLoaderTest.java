package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.SystemClock;
import android.widget.ImageView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArtworkLoaderTest {
    @Test public void concurrentBindingsShareOneBannerAndCancelledViewsStayEmpty() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = context.getPackageManager().getActivityInfo(new ComponentName(context, MainActivity.class), 0);
        // Exercise a real resource from the installed APK, rather than a mocked bitmap.
        info.activityInfo.banner = R.drawable.tv_banner;
        AppRepository.App app = new AppRepository.App(new ComponentName(context, MainActivity.class), "Fixture", info);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<ArtworkLoader> loader = new AtomicReference<>();
        AtomicReference<ImageView[]> views = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            loader.set(new ArtworkLoader(context, loaded -> completions.incrementAndGet()));
            views.set(new ImageView[]{new ImageView(context), new ImageView(context), new ImageView(context)});
            for (ImageView view : views.get()) loader.get().bind(view, app, color -> {});
            loader.get().cancel(views.get()[2]);
        });
        try {
            long deadline = SystemClock.uptimeMillis() + 10_000;
            while (completions.get() == 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25);
            instrumentation.runOnMainSync(() -> {
                assertEquals("One decode for multiple bindings", 1, completions.get());
                ImageView[] bound = views.get();
                Bitmap first = ((BitmapDrawable) bound[0].getDrawable()).getBitmap();
                Bitmap second = ((BitmapDrawable) bound[1].getDrawable()).getBitmap();
                assertSame(first, second);
                assertEquals(256, first.getWidth());
                assertEquals(144, first.getHeight());
                assertEquals(Bitmap.Config.RGB_565, first.getConfig());
                assertTrue(first.getAllocationByteCount() <= 74_000);
                assertTrue("Cancelled view must not receive decoded art", bound[2].getDrawable() == null
                        || ((BitmapDrawable) bound[2].getDrawable()).getBitmap() == null);
                assertNull(bound[2].getTag());
                loader.get().release();
                assertNull(loader.get().bitmap(app));
                assertNull("Completed bindings must release their bitmap too", bound[0].getDrawable());
                assertNull(bound[1].getDrawable());
            });
        } finally {
            instrumentation.runOnMainSync(() -> loader.get().close());
        }
    }
}
