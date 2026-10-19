package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.ParcelFileDescriptor;
import android.graphics.Rect;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Real Android layout and input dispatch. The long catalog is explicitly a test fixture. */
@RunWith(AndroidJUnit4.class)
public class HomeUiTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void installedAppsAreUniqueAndFitTheViewport() throws Exception {
        Context context = instrumentation.getTargetContext();
        List<AppRepository.App> installed = new AppRepository(context).scan();
        Set<String> packages = new HashSet<>();
        for (AppRepository.App app : installed) assertTrue("Repeated package", packages.add(app.packageName()));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForCatalog(scenario);
            waitUntil(scenario, activity -> {
                RecyclerView grid = activity.findViewById(R.id.grid);
                View focused = activity.getCurrentFocus();
                return focused != null && grid.findContainingItemView(focused) != null;
            });
            scenario.onActivity(activity -> checkViewport(activity));
            scenario.onActivity(activity -> {
                TvInputManager manager = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);
                boolean hasInput = false;
                if (manager != null) for (TvInputInfo input : manager.getTvInputList()) {
                    if (input.isPassthroughInput() || input.getType() == TvInputInfo.TYPE_TUNER)
                        hasInput = true;
                }
                assertEquals("Only show a real TV input action", hasInput,
                        activity.findViewById(R.id.action_inputs).getVisibility() == View.VISIBLE);
                RecyclerView grid = activity.findViewById(R.id.grid);
                View tile = grid.findContainingItemView(activity.getCurrentFocus());
                assertNotNull("An installed app must have focus", tile);
                AppAdapter adapter = (AppAdapter) grid.getAdapter();
                String label = adapter.at(grid.getChildAdapterPosition(tile)).label;
                assertEquals("The hero must describe the focused app", label,
                        ((android.widget.TextView) activity.findViewById(R.id.hero_name)).getText().toString());
                assertTrue(activity.findViewById(R.id.hero_open).isEnabled());
            });
            screenshot("installed-apps.png");
        }
    }

    @Test public void realDpadDispatchTraversesSectionsAndKeepsFocusedTileVisible() throws Exception {
        Context context = instrumentation.getTargetContext();
        ActivityInfo info = context.getPackageManager().getActivityInfo(new ComponentName(context, MainActivity.class), 0);
        List<AppRepository.App> fixtures = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            ResolveInfo resolve = new ResolveInfo();
            resolve.activityInfo = info;
            fixtures.add(new AppRepository.App(new ComponentName("com.adlesstv.fixture" + i, ".App"),
                    "Aplicación de prueba " + (i + 1) + " · nombre largo para comprobar límites", resolve));
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class));
             FixedCatalog ignored = new FixedCatalog(scenario)) {
            waitForCatalog(scenario);
            scenario.onActivity(activity -> {
                RecyclerView grid = activity.findViewById(R.id.grid);
                AppAdapter adapter = (AppAdapter) grid.getAdapter();
                adapter.replace(fixtures.subList(0, 6), fixtures.subList(6, fixtures.size()));
                grid.scrollToPosition(1);
            });
            waitUntil(scenario, activity -> {
                RecyclerView grid = activity.findViewById(R.id.grid);
                RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(1);
                return first != null && first.itemView.requestFocus();
            });
            screenshot("fixture-grid.png");
            int[] keys = {22, 22, 22, 22, 20, 20, 20, 20, 20, 20, 20, 20,
                    20, 20, 20, 20, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 21, 21};
            int step = 0;
            for (int key : keys) {
                AtomicInteger expected = new AtomicInteger();
                scenario.onActivity(activity -> {
                    RecyclerView grid = activity.findViewById(R.id.grid);
                    View focus = activity.getCurrentFocus();
                    View tile = focus == null ? null : grid.findContainingItemView(focus);
                    AppAdapter adapter = (AppAdapter) grid.getAdapter();
                    if (tile == null) { expected.set(-1); return; }
                    GridNavigation.Direction direction = key == KeyEvent.KEYCODE_DPAD_DOWN ? GridNavigation.Direction.DOWN
                            : key == KeyEvent.KEYCODE_DPAD_UP ? GridNavigation.Direction.UP
                            : key == KeyEvent.KEYCODE_DPAD_LEFT ? GridNavigation.Direction.LEFT : GridNavigation.Direction.RIGHT;
                    expected.set(adapter.move(grid.getChildAdapterPosition(tile), direction));
                });
                waitUntil(scenario, activity -> activity.hasWindowFocus());
                android.util.Log.i("AdLessTV-test", "D-pad step=" + step + " key=" + key + " expected=" + expected.get());
                instrumentation.sendKeyDownUpSync(key);
                waitUntil(scenario, activity -> {
                    RecyclerView grid = activity.findViewById(R.id.grid);
                    View focused = activity.getCurrentFocus();
                    if (grid.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return false;
                    if (expected.get() == -1) {
                        int firstAction = activity.findViewById(R.id.action_inputs).getVisibility() == View.VISIBLE
                                ? R.id.action_inputs : R.id.action_network;
                        return focused != null && focused.getId() == firstAction;
                    }
                    View tile = focused == null ? null : grid.findContainingItemView(focused);
                    return tile != null && grid.getChildAdapterPosition(tile) == expected.get();
                });
                scenario.onActivity(activity -> checkViewport(activity));
                if (++step == 16) screenshot("fixture-last-row.png");
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            instrumentation.waitForIdleSync();
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MENU);
            instrumentation.waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo dialog = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertNotNull("Application menu must open", dialog);
            assertFalse("App info must be reachable from the menu", dialog.findAccessibilityNodeInfosByText(
                    context.getString(R.string.app_details)).isEmpty());
            screenshot("fixture-app-menu.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        }
    }

    /** Only the artificial-catalog test suspends package events; the installed-app test stays live. */
    private final class FixedCatalog implements AutoCloseable {
        private final ActivityScenario<MainActivity> scenario;
        private final android.content.BroadcastReceiver receiver;
        FixedCatalog(ActivityScenario<MainActivity> scenario) throws Exception {
            this.scenario = scenario;
            java.lang.reflect.Field changes = MainActivity.class.getDeclaredField("packageChanges");
            changes.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<android.content.BroadcastReceiver> saved =
                    new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    saved.set((android.content.BroadcastReceiver) changes.get(activity));
                    activity.unregisterReceiver(saved.get());
                } catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            receiver = saved.get();
            java.lang.reflect.Field scanning = MainActivity.class.getDeclaredField("scanning");
            scanning.setAccessible(true);
            waitUntil(scenario, activity -> {
                try { return !scanning.getBoolean(activity); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
        }
        @Override public void close() {
            scenario.onActivity(activity -> {
                android.content.IntentFilter filter = new android.content.IntentFilter();
                filter.addAction(Intent.ACTION_PACKAGE_ADDED);
                filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
                filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
                filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
                filter.addDataScheme("package");
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else activity.registerReceiver(receiver, filter);
            });
        }
    }

    private void checkViewport(MainActivity activity) {
        RecyclerView grid = activity.findViewById(R.id.grid);
        View root = activity.findViewById(R.id.root);
        assertTrue("The catalog must retain usable vertical space", grid.getHeight() > root.getHeight() / 3);
        Rect viewport = new Rect();
        assertTrue(grid.getGlobalVisibleRect(viewport));
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            assertTrue("Tile exceeds right column boundary", child.getRight() <= grid.getWidth());
            assertTrue("Tile exceeds left column boundary", child.getLeft() >= 0);
        }
        View focused = activity.getCurrentFocus();
        View tile = focused == null ? null : grid.findContainingItemView(focused);
        if (tile != null) {
            int[] origin = new int[2];
            tile.getLocationOnScreen(origin);
            assertTrue("Focused tile clipped above", origin[1] >= viewport.top);
            assertTrue("Focused tile clipped below", origin[1] + tile.getHeight() <= viewport.bottom + 1);
        }
    }

    private void waitForCatalog(ActivityScenario<MainActivity> scenario) {
        waitUntil(scenario, activity -> ((AppAdapter) ((RecyclerView) activity.findViewById(R.id.grid))
                .getAdapter()).firstApp() >= 0);
    }
    private interface Check { boolean ready(MainActivity activity); }
    private void waitUntil(ActivityScenario<MainActivity> scenario, Check check) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        AtomicBoolean ready = new AtomicBoolean();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> ready.set(check.ready(activity)));
            if (ready.get()) return;
            SystemClock.sleep(25);
        }
        try { screenshot("failure.png"); } catch (Exception ignored) { }
        scenario.onActivity(activity -> {
            RecyclerView grid = activity.findViewById(R.id.grid);
            View focused = activity.getCurrentFocus();
            View tile = focused == null ? null : grid.findContainingItemView(focused);
            fail("Focus or layout did not settle: focus=" + focused + ", position="
                    + (tile == null ? -1 : grid.getChildAdapterPosition(tile))
                    + ", scroll=" + grid.getScrollState());
        });
    }
    private void shell(String command) throws Exception {
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[4096];
            while (stream.read(buffer) != -1) { /* Wait for the shell command to finish. */ }
        }
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        String directory = "/sdcard/Download/AdLessTV-ui/";
        shell("mkdir -p " + directory);
        shell("screencap -p " + directory + name);
    }
}
