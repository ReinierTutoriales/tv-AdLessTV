package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.ParcelFileDescriptor;
import android.graphics.Rect;
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

    @Test public void packageRefreshKeepsToolbarFocus() throws Exception {
        Context context = instrumentation.getTargetContext();
        java.lang.reflect.Field changes = MainActivity.class.getDeclaredField("packageChanges");
        java.lang.reflect.Field scanning = MainActivity.class.getDeclaredField("scanning");
        changes.setAccessible(true);
        scanning.setAccessible(true);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForCatalog(scenario);
            scenario.onActivity(activity -> {
                // Keep a catalog pane open while a toolbar action owns focus.
                activity.findViewById(R.id.catalog_container).setVisibility(View.VISIBLE);
                activity.findViewById(R.id.dock).setVisibility(View.GONE);
            });
            scenario.onActivity(activity -> {
                assertTrue(activity.findViewById(R.id.action_settings).requestFocus());
                try {
                    ((android.content.BroadcastReceiver) changes.get(activity)).onReceive(activity,
                            new Intent(Intent.ACTION_PACKAGE_CHANGED,
                                    android.net.Uri.parse("package:" + context.getPackageName())));
                } catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            waitUntil(scenario, activity -> {
                try { return !scanning.getBoolean(activity); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            scenario.onActivity(activity -> {
                assertEquals(activity.findViewById(R.id.action_settings), activity.getCurrentFocus());
                assertEquals("An input/package refresh must not reveal the dock over an open catalog",
                        View.GONE, activity.findViewById(R.id.dock).getVisibility());
            });
        }
    }

    @Test public void homeResetsOnlyWhenAlreadyVisibleAndGlassSettlesAfterTheSlide() throws Exception {
        Context context = instrumentation.getTargetContext();
        ActivityInfo info = context.getPackageManager().getActivityInfo(new ComponentName(context, MainActivity.class), 0);
        List<AppRepository.App> fixtures = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            ResolveInfo resolve = new ResolveInfo();
            resolve.activityInfo = info;
            fixtures.add(new AppRepository.App(new ComponentName("com.adlesstv.home" + i, ".App"), "Inicio " + (i + 1), resolve));
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class));
             FixedCatalog ignored = new FixedCatalog(scenario)) {
            waitForCatalog(scenario);
            scenario.onActivity(activity -> {
                RecyclerView dock = activity.findViewById(R.id.dock);
                ((AppAdapter) dock.getAdapter()).replace(fixtures.subList(0, 4), java.util.Collections.emptyList());
                dock.setVisibility(View.VISIBLE);
                ((AppAdapter) ((RecyclerView) activity.findViewById(R.id.grid)).getAdapter())
                        .replace(java.util.Collections.emptyList(), fixtures.subList(4, fixtures.size()));
            });
            waitUntil(scenario, activity -> {
                RecyclerView.ViewHolder first = ((RecyclerView) activity.findViewById(R.id.dock)).findViewHolderForAdapterPosition(0);
                return first != null && (first.itemView.isFocused() || first.itemView.requestFocus());
            });
            waitUntil(scenario, activity -> activity.hasWindowFocus());
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil(scenario, activity -> gridFocus(activity) == 1);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
            waitUntil(scenario, activity -> gridFocus(activity) == 2);
            // Once the slide-up has ended, the highlight must sit on the focused tile.
            waitUntil(scenario, activity -> {
                View catalog = activity.findViewById(R.id.catalog_container);
                View glass = activity.findViewById(R.id.glass);
                View focus = activity.getCurrentFocus();
                if (catalog.getTranslationY() != 0f || glass.getVisibility() != View.VISIBLE || focus == null) return false;
                Rect tile = new Rect(), highlight = new Rect();
                focus.getGlobalVisibleRect(tile);
                glass.getGlobalVisibleRect(highlight);
                return Math.abs(tile.centerY() - highlight.centerY()) <= AppAdapter.dp(activity, 3);
            });
            // Returning from another app with Home keeps the tile that was focused. onStop sets this
            // flag; the test harness cannot resume a stopped singleTask HOME activity, so set it.
            java.lang.reflect.Field left = MainActivity.class.getDeclaredField("leftHome");
            left.setAccessible(true);
            scenario.onActivity(activity -> {
                try { left.setBoolean(activity, true); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
                activity.onHomePressed();
            });
            instrumentation.waitForIdleSync();
            scenario.onActivity(activity -> assertEquals("Coming back from an app must keep the grid tile",
                    2, gridFocus(activity)));
            scenario.onActivity(activity -> {
                try { left.setBoolean(activity, false); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            // Home while Home is already visible goes back to the dock.
            scenario.onActivity(MainActivity::onHomePressed);
            waitUntil(scenario, activity -> {
                RecyclerView dock = activity.findViewById(R.id.dock);
                View focus = activity.getCurrentFocus();
                return focus != null && dock.findContainingItemView(focus) != null;
            });
        }
    }

    private static int gridFocus(MainActivity activity) {
        RecyclerView grid = activity.findViewById(R.id.grid);
        View focus = activity.getCurrentFocus();
        View tile = focus == null ? null : grid.findContainingItemView(focus);
        return tile == null ? -1 : grid.getChildAdapterPosition(tile);
    }

    @Test public void visualThemeIsLocalAndSurvivesActivityRecreation() {
        Context context = instrumentation.getTargetContext();
        AppRepository preferences = new AppRepository(context);
        int previous = preferences.visualTheme();
        try {
            preferences.setVisualTheme(1);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(
                    new Intent(context, MainActivity.class))) {
                scenario.onActivity(activity -> assertEquals(0xFF0A1018,
                        ((android.graphics.drawable.ColorDrawable) activity.findViewById(R.id.root)
                                .getBackground()).getColor()));
                scenario.recreate();
                scenario.onActivity(activity -> assertEquals(0xFF0A1018,
                        ((android.graphics.drawable.ColorDrawable) activity.findViewById(R.id.root)
                                .getBackground()).getColor()));
            }
        } finally {
            preferences.setVisualTheme(previous);
        }
    }

    @Test public void memoryLineIsRealAndTheOriginalLauncherIsReachable() throws Exception {
        Context context = instrumentation.getTargetContext();
        AppRepository preferences = new AppRepository(context);
        boolean previous = preferences.showMemory();
        try {
            preferences.setShowMemory(true);
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
                waitForCatalog(scenario);
                scenario.onActivity(activity -> {
                    android.widget.TextView memory = activity.findViewById(R.id.memory);
                    if (activity.getResources().getDisplayMetrics().heightPixels < AppAdapter.dp(activity, 500)) {
                        assertEquals(View.GONE, memory.getVisibility());
                        return;
                    }
                    assertEquals(View.VISIBLE, memory.getVisibility());
                    android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
                    ((android.app.ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
                    assertTrue("Memory text must show the system total", memory.getText().toString().contains(
                            android.text.format.Formatter.formatShortFileSize(activity, info.totalMem)));
                });
                scenario.onActivity(activity -> activity.findViewById(R.id.action_settings).performClick());
                instrumentation.waitForIdleSync();
                android.view.accessibility.AccessibilityNodeInfo dialog =
                        instrumentation.getUiAutomation().getRootInActiveWindow();
                assertNotNull("Settings menu must open", dialog);
                assertFalse("The original launcher must be reachable without a computer",
                        dialog.findAccessibilityNodeInfosByText(context.getString(R.string.original_home)).isEmpty());
                screenshot("settings-menu.png");
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                preferences.setShowMemory(false);
                scenario.recreate();
                scenario.onActivity(activity -> assertEquals(View.GONE,
                        activity.findViewById(R.id.memory).getVisibility()));
            }
        } finally {
            preferences.setShowMemory(previous);
        }
    }

    @Test public void pushedThemeOverlaysDefaultsAndGlassFollowsFocus() throws Exception {
        Context context = instrumentation.getTargetContext();
        java.io.File dir = new java.io.File(HomeConfig.deviceStorage(context), HomeConfig.THEME_DIR);
        java.io.File config = new java.io.File(dir, HomeConfig.CONFIG);
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(config)) {
            out.write(("{\"background_color\": \"#112233\", \"greeting_name\": \"Prueba\","
                    + " \"columns\": \"no es un número\", \"unknown_key\": true}").getBytes("UTF-8"));
        }
        AppRepository preferences = new AppRepository(context);
        preferences.followTheme();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForCatalog(scenario);
            scenario.onActivity(activity -> {
                assertEquals("Theme colour replaces the palette", 0xFF112233,
                        ((android.graphics.drawable.ColorDrawable) activity.findViewById(R.id.root)
                                .getBackground()).getColor());
                assertEquals("A wrongly typed key is ignored", 0, new AppRepository(activity).columns());
                android.widget.TextView greeting = activity.findViewById(R.id.greeting);
                if (activity.getResources().getDisplayMetrics().heightPixels >= AppAdapter.dp(activity, 500)) {
                    assertTrue(greeting.getText().toString().contains("Prueba"));
                }
            });
            waitUntil(scenario, activity -> {
                View focus = activity.getCurrentFocus();
                View glass = activity.findViewById(R.id.glass);
                if (!(focus instanceof AppAdapter.Tile) || glass.getVisibility() != View.VISIBLE) return false;
                Rect tile = new Rect(), highlight = new Rect();
                focus.getGlobalVisibleRect(tile);
                glass.getGlobalVisibleRect(highlight);
                return Math.abs(tile.centerX() - highlight.centerX()) <= AppAdapter.dp(activity, 3)
                        && Math.abs(tile.centerY() - highlight.centerY()) <= AppAdapter.dp(activity, 3);
            });
            screenshot("theme-glass.png");
        } finally {
            assertTrue(config.delete());
            preferences.followTheme();
        }
    }

    @Test public void themeProviderRefusesFilesOutsideTheTheme() {
        Context context = instrumentation.getTargetContext();
        try {
            context.getContentResolver().openFileDescriptor(
                    android.net.Uri.parse("content://com.adlesstv.launcher.theme/../shared_prefs/x.xml"), "w");
            fail("Only config.json and wallpaper.jpg may be written");
        } catch (java.io.FileNotFoundException | SecurityException expected) { /* Refused. */ }
    }

    @Test public void chosenColumnsApplyWhenTheCatalogOpens() throws Exception {
        Context context = instrumentation.getTargetContext();
        AppRepository preferences = new AppRepository(context);
        int previous = preferences.columns();
        try {
            preferences.setColumns(4);
            ActivityInfo info = context.getPackageManager().getActivityInfo(
                    new ComponentName(context, MainActivity.class), 0);
            List<AppRepository.App> fixtures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                ResolveInfo resolve = new ResolveInfo();
                resolve.activityInfo = info;
                fixtures.add(new AppRepository.App(new ComponentName("com.adlesstv.columns" + i, ".App"),
                        "Columna " + (i + 1), resolve));
            }
            try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class));
                 FixedCatalog ignored = new FixedCatalog(scenario)) {
                waitForCatalog(scenario);
                // Installed apps may all fit in the dock; a fixture catalog makes the grid reachable.
                scenario.onActivity(activity -> {
                    RecyclerView dock = activity.findViewById(R.id.dock);
                    ((AppAdapter) dock.getAdapter()).replace(fixtures.subList(0, 4), java.util.Collections.emptyList());
                    dock.setVisibility(View.VISIBLE);
                    ((AppAdapter) ((RecyclerView) activity.findViewById(R.id.grid)).getAdapter())
                            .replace(java.util.Collections.emptyList(), fixtures.subList(4, fixtures.size()));
                });
                waitUntil(scenario, activity -> {
                    RecyclerView dock = activity.findViewById(R.id.dock);
                    RecyclerView.ViewHolder first = dock.findViewHolderForAdapterPosition(0);
                    return first != null && (first.itemView.isFocused() || first.itemView.requestFocus());
                });
                waitUntil(scenario, activity -> activity.hasWindowFocus());
                AtomicBoolean open = new AtomicBoolean();
                for (int attempt = 0; attempt < 3 && !open.get(); attempt++) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
                    instrumentation.waitForIdleSync();
                    scenario.onActivity(activity -> open.set(
                            activity.findViewById(R.id.catalog_container).getVisibility() == View.VISIBLE));
                }
                waitUntil(scenario, activity -> activity.findViewById(R.id.catalog_container).getVisibility() == View.VISIBLE
                        && activity.findViewById(R.id.grid).getWidth() > 0);
                scenario.onActivity(activity -> {
                    RecyclerView grid = activity.findViewById(R.id.grid);
                    int span = ((androidx.recyclerview.widget.GridLayoutManager) grid.getLayoutManager()).getSpanCount();
                    int available = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
                    assertEquals(Math.min(4, Math.max(2, available / AppAdapter.dp(activity, 88))), span);
                    assertEquals("Catalog mode hides the dock", View.GONE,
                            activity.findViewById(R.id.dock).getVisibility());
                });
            }
        } finally {
            preferences.setColumns(previous);
        }
    }

    @Test public void installedAppsAreUniqueAndFitTheViewport() throws Exception {
        Context context = instrumentation.getTargetContext();
        List<AppRepository.App> installed = new AppRepository(context).scan();
        Set<String> packages = new HashSet<>();
        for (AppRepository.App app : installed) assertTrue("Repeated package", packages.add(app.packageName()));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            waitForCatalog(scenario);
            waitUntil(scenario, activity -> {
                RecyclerView grid = activity.findViewById(R.id.grid);
                RecyclerView dock = activity.findViewById(R.id.dock);
                View focused = activity.getCurrentFocus();
                return focused != null && (grid.findContainingItemView(focused) != null
                        || dock.findContainingItemView(focused) != null);
            });
            scenario.onActivity(activity -> checkViewport(activity));
            scenario.onActivity(activity -> {
                // Same source as Home: visible, non-hidden passthrough or tuner inputs.
                boolean hasInput = !new AppRepository(activity).scanInputs().isEmpty();
                assertEquals("Only show a real TV input action", hasInput,
                        activity.findViewById(R.id.action_inputs).getVisibility() == View.VISIBLE);
                RecyclerView grid = activity.findViewById(R.id.grid);
                RecyclerView dock = activity.findViewById(R.id.dock);
                RecyclerView owner = dock.findContainingItemView(activity.getCurrentFocus()) != null ? dock : grid;
                View tile = owner.findContainingItemView(activity.getCurrentFocus());
                assertNotNull("An installed app must have focus", tile);
                AppAdapter adapter = (AppAdapter) owner.getAdapter();
                String label = adapter.at(owner.getChildAdapterPosition(tile)).label;
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
                RecyclerView dock = activity.findViewById(R.id.dock);
                ((AppAdapter) dock.getAdapter()).replace(fixtures.subList(0, 6), java.util.Collections.emptyList());
                dock.setVisibility(View.VISIBLE);
                adapter.replace(java.util.Collections.emptyList(), fixtures.subList(6, fixtures.size()));
            });
            // The catalog stays hidden while the dock has focus; Down from the dock reveals it.
            waitUntil(scenario, activity -> {
                RecyclerView dock = activity.findViewById(R.id.dock);
                RecyclerView.ViewHolder first = dock.findViewHolderForAdapterPosition(0);
                return first != null && (first.itemView.isFocused() || first.itemView.requestFocus());
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil(scenario, activity -> {
                RecyclerView grid = activity.findViewById(R.id.grid);
                View focused = activity.getCurrentFocus();
                View tile = focused == null ? null : grid.findContainingItemView(focused);
                return grid.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                        && tile != null && grid.getChildAdapterPosition(tile) == 1;
            });
            screenshot("fixture-grid.png");
            int[] keys = {22, 22, 22, 22, 20, 20, 20, 20, 20, 20, 20, 20,
                    19, 19, 19, 19, 19, 21, 21};
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
                        RecyclerView dock = activity.findViewById(R.id.dock);
                        View dockTile = focused == null ? null : dock.findContainingItemView(focused);
                        return dockTile != null && dock.getChildAdapterPosition(dockTile) == 0;
                    }
                    View tile = focused == null ? null : grid.findContainingItemView(focused);
                    if (tile == null || grid.getChildAdapterPosition(tile) != expected.get()) return false;
                    Rect viewport = new Rect();
                    int[] origin = new int[2];
                    tile.getLocationOnScreen(origin);
                    return grid.getGlobalVisibleRect(viewport) && origin[1] >= viewport.top
                            && origin[1] + tile.getHeight() <= viewport.bottom + 1;
                });
                scenario.onActivity(activity -> checkViewport(activity));
                if (++step == 16) screenshot("fixture-last-row.png");
            }
            AtomicBoolean stillInGrid = new AtomicBoolean();
            scenario.onActivity(activity -> {
                RecyclerView grid = activity.findViewById(R.id.grid);
                stillInGrid.set(grid.findContainingItemView(activity.getCurrentFocus()) != null);
            });
            if (stillInGrid.get()) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            waitUntil(scenario, activity -> {
                RecyclerView dock = activity.findViewById(R.id.dock);
                View focus = activity.getCurrentFocus();
                View tile = focus == null ? null : dock.findContainingItemView(focus);
                return tile != null && dock.getChildAdapterPosition(tile) == 0;
            });
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
            waitUntil(scenario, activity -> activity.getCurrentFocus() == activity.findViewById(R.id.hero_open));
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            waitUntil(scenario, activity -> {
                RecyclerView dock = activity.findViewById(R.id.dock);
                View focus = activity.getCurrentFocus();
                View tile = focus == null ? null : dock.findContainingItemView(focus);
                return tile != null && dock.getChildAdapterPosition(tile) == 0;
            });
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
        View focused = activity.getCurrentFocus();
        RecyclerView dock = activity.findViewById(R.id.dock);
        View dockTile = focused == null ? null : dock.findContainingItemView(focused);
        if (dockTile != null) {
            Rect dockViewport = new Rect();
            assertTrue(dock.getGlobalVisibleRect(dockViewport));
            Rect bounds = new Rect();
            assertTrue(dockTile.getGlobalVisibleRect(bounds));
            assertTrue("Focused dock tile clipped", dockViewport.contains(bounds));
        }
        // By design the catalog is revealed only when it receives focus (Down from the dock).
        if (activity.findViewById(R.id.catalog_container).getVisibility() != View.VISIBLE) {
            assertNotNull("With the catalog hidden, focus must be on the dock", dockTile);
            return;
        }
        assertTrue("The catalog must retain usable vertical space", grid.getHeight() > root.getHeight() / 4);
        Rect viewport = new Rect();
        assertTrue(grid.getGlobalVisibleRect(viewport));
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            assertTrue("Tile exceeds right column boundary", child.getRight() <= grid.getWidth());
            assertTrue("Tile exceeds left column boundary", child.getLeft() >= 0);
        }
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
                .getAdapter()).firstApp() >= 0 || ((AppAdapter) ((RecyclerView) activity.findViewById(R.id.dock))
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
            Rect tileBounds = new Rect(), glassBounds = new Rect();
            if (focused != null) focused.getGlobalVisibleRect(tileBounds);
            activity.findViewById(R.id.glass).getGlobalVisibleRect(glassBounds);
            fail("Focus or layout did not settle: focus=" + focused + ", position="
                    + (tile == null ? -1 : grid.getChildAdapterPosition(tile))
                    + ", scroll=" + grid.getScrollState() + ", tile=" + tileBounds
                    + ", glass=" + glassBounds);
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
        String directory = "/data/local/tmp/AdLessTV-ui/";
        shell("mkdir -p " + directory);
        shell("screencap -p " + directory + name);
    }
}
