package com.adlesstv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Insets;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.util.TypedValue;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A large local top shelf and a separate adaptive app library. No recurring background work. */
public final class MainActivity extends Activity implements AppAdapter.Listener {
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppRepository.App> all = new ArrayList<>();
    private final List<AppRepository.App> pinned = new ArrayList<>();
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            dirty = true;
            artwork.invalidate();
            if (resumed) refresh();
        }
    };
    private final BroadcastReceiver userUnlocked = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            repository = new AppRepository(MainActivity.this);
            dirty = true;
            if (resumed) refresh();
        }
    };
    private AppRepository repository;
    private AppAdapter adapter;
    private AppAdapter dockAdapter;
    private ArtworkLoader artwork;
    private RecyclerView grid;
    private RecyclerView dock;
    private GridLayoutManager layout;
    private TextView empty;
    private View network;
    private View inputs;
    private View settings;
    private TextView heroName;
    private AppRepository.App selectedApp;
    private String lastFocused;
    private String lastDockFocused;
    private boolean resumed;
    private boolean started;
    private boolean catalogReady;
    private boolean artworkReleased;
    private boolean restoreFocusAfterRelease;
    private boolean dirty = true;
    private boolean scanning;
    private boolean destroyed;
    private boolean libraryVisible;
    private boolean compact;
    private int pendingGridPosition = RecyclerView.NO_POSITION;
    private int pendingDockPosition = RecyclerView.NO_POSITION;
    private OnBackInvokedCallback backCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (state != null) {
            lastFocused = state.getString("focused_app");
            lastDockFocused = state.getString("focused_dock_app");
        }
        View root = findViewById(R.id.root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(AppAdapter.dp(this, 48) + bars.left,
                        AppAdapter.dp(this, 27) + bars.top,
                        AppAdapter.dp(this, 48) + bars.right,
                        AppAdapter.dp(this, 27) + bars.bottom);
                return insets;
            });
        }
        repository = new AppRepository(this);
        artwork = new ArtworkLoader(this, app -> {
            if (selectedApp != null && selectedApp.key().equals(app.key())) updateHero();
        });
        grid = findViewById(R.id.grid);
        dock = findViewById(R.id.dock);
        empty = findViewById(R.id.empty);
        inputs = findViewById(R.id.action_inputs);
        settings = findViewById(R.id.action_settings);
        network = findViewById(R.id.action_network);
        heroName = findViewById(R.id.hero_name);
        compact = getResources().getDisplayMetrics().heightPixels < AppAdapter.dp(this, 500);
        if (compact) {
            findViewById(R.id.date).setVisibility(View.GONE);
            heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> {
                pendingGridPosition = RecyclerView.NO_POSITION;
                returnToShelf();
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        adapter = new AppAdapter(this, this, artwork, false);
        adapter.setReducedMotion(repository.reducedMotion());
        dockAdapter = new AppAdapter(this, new AppAdapter.Listener() {
            @Override public void focused(AppRepository.App app) {
                lastDockFocused = app.key();
                MainActivity.this.focused(app);
            }
            @Override public void opened(AppRepository.App app) { openApp(app); }
            @Override public void options(AppRepository.App app) { showOptions(app); }
            @Override public boolean navigate(int position, int key) { return navigateDock(position, key); }
        }, artwork, true);
        dockAdapter.setReducedMotion(repository.reducedMotion());
        VisualTheme.apply(this, repository.visualTheme(), root, findViewById(R.id.hero), dock);
        dock.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        dock.setAdapter(dockAdapter);
        dock.setItemAnimator(null);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateDockGeometry());
        dock.getViewTreeObserver().addOnGlobalLayoutListener(this::applyPendingFocus);
        layout = new GridLayoutManager(this, 4);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.isHeading(position) ? layout.getSpanCount() : 1;
            }
        });
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        grid.getViewTreeObserver().addOnGlobalLayoutListener(this::applyPendingFocus);
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - grid.getPaddingLeft() - grid.getPaddingRight();
            int columns = Math.max(2, Math.min(6, available / AppAdapter.dp(this, 120)));
            if (columns != layout.getSpanCount()) {
                layout.setSpanCount(columns);
                adapter.setColumns(columns);
                int selected = adapter.indexOf(lastFocused);
                if (selected >= 0) focusGrid(selected);
            }
        });
        inputs.setOnClickListener(v -> showInputs());
        settings.setOnClickListener(v -> showSettings());
        network.setOnClickListener(v -> openSettings(Settings.ACTION_WIFI_SETTINGS));
        View[] actions = {inputs, network, settings};
        for (int i = 0; i < actions.length; i++) {
            final int index = i;
            actions[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    int step = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1;
                    focusVisibleAction(actions, index, step); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int step = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                    focusVisibleAction(actions, index, step); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (libraryVisible) {
                        int selected = adapter.indexOf(lastFocused);
                        focusGrid(selected >= 0 ? selected : adapter.firstApp());
                    } else returnToShelf();
                    return true;
                }
                return false;
            });
        }
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageChanges, packages, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageChanges, packages);
        if (Build.VERSION.SDK_INT >= 24) {
            IntentFilter unlock = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(userUnlocked, unlock, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(userUnlocked, unlock);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)) {
            // singleTask reuses this Activity: HOME must close the library, not leave its old scroll state.
            returnToShelf();
        }
    }

    private void updateDockGeometry() {
        View root = findViewById(R.id.root);
        int available = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int count = dockAdapter.getItemCount();
        if (available <= 0 || count == 0) return;
        int slots = Math.min(4, count);
        int width = Math.min(AppAdapter.dp(this, compact ? 148 : 190),
                (available - AppAdapter.dp(this, 24)) / slots - AppAdapter.dp(this, 10));
        dockAdapter.setDockWidth(Math.max(1, width));
        int bandWidth = Math.min(available, slots * (width + AppAdapter.dp(this, 10)) + AppAdapter.dp(this, 24));
        if (dock.getLayoutParams().width != bandWidth) {
            dock.getLayoutParams().width = bandWidth;
            dock.requestLayout();
        }
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
    }

    @Override protected void onStop() {
        started = false;
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (artworkReleased) {
            grid.setAdapter(adapter);
            dock.setAdapter(dockAdapter);
            artworkReleased = false;
            if (pendingGridPosition >= 0 || pendingDockPosition >= 0) main.post(this::applyPendingFocus);
            else if (restoreFocusAfterRelease) focusLastApp();
            restoreFocusAfterRelease = false;
        }
        updateInputsVisibility();
        if (dirty) refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        pendingGridPosition = RecyclerView.NO_POSITION;
        pendingDockPosition = RecyclerView.NO_POSITION;
        super.onPause();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("focused_app", lastFocused);
        state.putString("focused_dock_app", lastDockFocused);
        super.onSaveInstanceState(state);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (artwork == null) return;
        boolean pressure = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        if (pressure || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) artwork.trim();
        // Eviction alone cannot free bitmaps still owned by attached/recycled tile views.
        // Retain the visible UI and ordinary app-return path; release hidden views under pressure.
        if (!pressure || started || artworkReleased) return;
        restoreFocusAfterRelease = grid.hasFocus() || dock.hasFocus();
        pendingGridPosition = RecyclerView.NO_POSITION;
        pendingDockPosition = RecyclerView.NO_POSITION;
        artworkReleased = true;
        grid.setAdapter(null);
        dock.setAdapter(null);
        grid.getRecycledViewPool().clear();
        dock.getRecycledViewPool().clear();
        artwork.invalidate();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterReceiver(packageChanges);
        if (Build.VERSION.SDK_INT >= 24) unregisterReceiver(userUnlocked);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        scanner.shutdownNow();
        artwork.close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void refresh() {
        if (scanning || destroyed || !resumed) return;
        scanning = true;
        dirty = false;
        scanner.execute(() -> {
            List<AppRepository.App> next;
            try { next = repository.scan(); }
            catch (RuntimeException failure) {
                main.post(() -> {
                    if (destroyed) return;
                    scanning = false;
                    dirty = true;
                    if (all.isEmpty()) { empty.setText(R.string.scan_failed); empty.setVisibility(View.VISIBLE); }
                });
                return;
            }
            main.post(() -> {
                if (destroyed) return;
                scanning = false;
                if (!resumed) { dirty = true; return; }
                all.clear();
                all.addAll(next);
                render();
                if (dirty) refresh();
            });
        });
    }

    private void render() {
        final String returnKey = lastFocused;
        final boolean restoreFocus = !catalogReady || grid.hasFocus() || dock.hasFocus()
                || pendingGridPosition >= 0 || pendingDockPosition >= 0;
        catalogReady = true;
        pendingGridPosition = RecyclerView.NO_POSITION;
        pendingDockPosition = RecyclerView.NO_POSITION;
        Map<String, AppRepository.App> byKey = new HashMap<>();
        for (AppRepository.App app : all) byKey.put(app.key(), app);
        Set<String> hidden = repository.hidden();
        pinned.clear();
        Set<String> pinnedKeys = new HashSet<>();
        for (String key : repository.favorites(all)) {
            AppRepository.App app = byKey.get(key);
            if (app != null && !hidden.contains(app.packageName()) && pinnedKeys.add(key)) pinned.add(app);
        }
        List<AppRepository.App> rest = new ArrayList<>();
        for (AppRepository.App app : all) {
            if (!hidden.contains(app.packageName()) && !pinnedKeys.contains(app.key())) rest.add(app);
        }
        dockAdapter.replace(pinned, Collections.emptyList());
        updateDockGeometry();
        dock.setVisibility(libraryVisible || pinned.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.replace(Collections.emptyList(), rest);
        findViewById(R.id.library_hint).setVisibility(rest.isEmpty() ? View.GONE : View.VISIBLE);
        if (pinned.isEmpty()) showLibrary(true);
        selectedApp = null;
        int inDock = dockAdapter.indexOf(returnKey);
        int inGrid = adapter.indexOf(returnKey);
        if (inDock >= 0) selectedApp = dockAdapter.at(inDock);
        else if (inGrid >= 0) selectedApp = adapter.at(inGrid);
        else if (dockAdapter.firstApp() >= 0) selectedApp = dockAdapter.at(dockAdapter.firstApp());
        else if (adapter.firstApp() >= 0) selectedApp = adapter.at(adapter.firstApp());
        updateHero();
        empty.setText(R.string.no_apps);
        empty.setVisibility(adapter.firstApp() < 0 && dockAdapter.firstApp() < 0 ? View.VISIBLE : View.GONE);
        if (!restoreFocus) return;
        if (inDock >= 0 || (inGrid < 0 && dockAdapter.firstApp() >= 0))
            focusDock(inDock >= 0 ? inDock : dockAdapter.firstApp());
        else focusGrid(inGrid >= 0 ? inGrid : adapter.firstApp());
    }

    private void focusLastApp() {
        int inDock = dockAdapter.indexOf(lastFocused);
        int inGrid = adapter.indexOf(lastFocused);
        if (inDock >= 0 || (inGrid < 0 && dockAdapter.firstApp() >= 0))
            focusDock(inDock >= 0 ? inDock : dockAdapter.firstApp());
        else focusGrid(inGrid >= 0 ? inGrid : adapter.firstApp());
    }

    private void focusDock(int position) {
        if (position < 0 || position >= dockAdapter.getItemCount()) {
            firstTopAction().requestFocus(); return;
        }
        showLibrary(false);
        pendingGridPosition = RecyclerView.NO_POSITION;
        pendingDockPosition = position;
        RecyclerView.ViewHolder holder = dock.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) return;
        dock.scrollToPosition(position);
        dock.post(this::applyPendingFocus);
    }

    private void focusGrid(int position) {
        if (position < 0 || position >= adapter.getItemCount() || adapter.isHeading(position)) {
            if (dockAdapter.firstApp() >= 0) focusDock(dockAdapter.firstApp());
            else settings.requestFocus();
            return;
        }
        showLibrary(true);
        pendingDockPosition = RecyclerView.NO_POSITION;
        pendingGridPosition = position;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) return;
        grid.scrollToPosition(position == adapter.firstApp() ? 0 : position);
        grid.post(this::applyPendingFocus);
    }

    private void applyPendingFocus() {
        if (!resumed) return;
        if (pendingDockPosition >= 0) {
            RecyclerView.ViewHolder tile = dock.findViewHolderForAdapterPosition(pendingDockPosition);
            if (tile != null) tile.itemView.requestFocus();
            return;
        }
        if (pendingGridPosition < 0) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pendingGridPosition);
        if (holder != null) holder.itemView.requestFocus();
    }

    @Override public void focused(AppRepository.App app) {
        pendingGridPosition = RecyclerView.NO_POSITION;
        pendingDockPosition = RecyclerView.NO_POSITION;
        lastFocused = app.key();
        selectedApp = app;
        updateHero();
    }
    @Override public void opened(AppRepository.App app) { openApp(app); }
    @Override public void options(AppRepository.App app) { showOptions(app); }

    @Override public boolean navigate(int position, int key) {
        GridNavigation.Direction direction;
        if (key == KeyEvent.KEYCODE_DPAD_UP) direction = GridNavigation.Direction.UP;
        else if (key == KeyEvent.KEYCODE_DPAD_DOWN) direction = GridNavigation.Direction.DOWN;
        else if (key == KeyEvent.KEYCODE_DPAD_LEFT) direction = grid.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? GridNavigation.Direction.RIGHT : GridNavigation.Direction.LEFT;
        else if (key == KeyEvent.KEYCODE_DPAD_RIGHT) direction = grid.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? GridNavigation.Direction.LEFT : GridNavigation.Direction.RIGHT;
        else return false;
        int from = pendingGridPosition >= 0 ? pendingGridPosition : position;
        int next = adapter.move(from, direction);
        if (next == GridNavigation.TOP_BAR) {
            pendingGridPosition = RecyclerView.NO_POSITION;
            if (dockAdapter.firstApp() >= 0) focusDock(dockAdapter.firstApp());
            else firstTopAction().requestFocus();
        }
        else if (next != from) focusGrid(next);
        return true;
    }

    private boolean navigateDock(int position, int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP) { firstTopAction().requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (adapter.firstApp() >= 0) focusGrid(adapter.firstApp());
            return true;
        }
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (dock.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) step = -step;
        int next = (pendingDockPosition >= 0 ? pendingDockPosition : position) + step;
        if (next >= 0 && next < dockAdapter.getItemCount()) focusDock(next);
        return true;
    }

    private void openApp(AppRepository.App app) {
        lastFocused = app.key();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(app.component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException e) {
            try {
                Intent fallback = getPackageManager().getLaunchIntentForPackage(app.packageName());
                if (fallback == null) throw new ActivityNotFoundException();
                startActivity(fallback);
            } catch (RuntimeException ignored) { toast(R.string.launch_failed); }
        }
    }

    private void showOptions(AppRepository.App app) {
        int pinnedIndex = -1;
        for (int i = 0; i < pinned.size(); i++) if (pinned.get(i).key().equals(app.key())) pinnedIndex = i;
        final int index = pinnedIndex;
        List<Integer> actions = new ArrayList<>();
        actions.add(index >= 0 ? R.string.unpin : R.string.pin);
        if (index > 0) actions.add(R.string.move_left);
        if (index >= 0 && index < pinned.size() - 1) actions.add(R.string.move_right);
        actions.add(R.string.app_details);
        actions.add(R.string.hide);
        String[] labels = new String[actions.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(actions.get(i));
        new AlertDialog.Builder(this).setTitle(app.label).setItems(labels, (dialog, choice) -> {
            int action = actions.get(choice);
            List<String> selection = new ArrayList<>(repository.favorites(all));
            if (action == R.string.pin || action == R.string.unpin) {
                if (index >= 0) selection.remove(app.key()); else selection.add(app.key());
                repository.saveFavorites(selection);
            } else if (action == R.string.hide) repository.setHidden(app.packageName(), true);
            else if (action == R.string.app_details) {
                openAppDetails(app);
                return;
            } else {
                int adjacent = action == R.string.move_left ? index - 1 : index + 1;
                Collections.swap(selection, selection.indexOf(app.key()),
                        selection.indexOf(pinned.get(adjacent).key()));
                repository.saveFavorites(selection);
            }
            render();
        }).show();
    }

    private void showSettings() {
        String[] labels = {getString(R.string.show_hidden), getString(R.string.reset_favorites),
                getString(repository.reducedMotion() ? R.string.motion_enable : R.string.motion_reduce),
                getString(R.string.visual_theme), getString(R.string.default_home),
                getString(R.string.clock_dream), getString(R.string.settings)};
        new AlertDialog.Builder(this).setTitle(R.string.manage).setItems(labels, (dialog, choice) -> {
            if (choice == 0) showHidden();
            else if (choice == 1) new AlertDialog.Builder(this).setMessage(R.string.confirm_reset)
                    .setPositiveButton(android.R.string.ok, (d, which) -> { repository.resetFavorites(); render(); })
                    .setNegativeButton(android.R.string.cancel, null).show();
            else if (choice == 2) {
                boolean reduced = !repository.reducedMotion();
                repository.setReducedMotion(reduced);
                adapter.setReducedMotion(reduced);
                dockAdapter.setReducedMotion(reduced);
            }
            else if (choice == 3) showVisualThemes();
            else if (choice == 4) requestHome();
            else if (choice == 5) openSettings("android.settings.DREAM_SETTINGS");
            else openSettings(Settings.ACTION_SETTINGS);
        }).show();
    }

    private void showVisualThemes() {
        String[] themes = {getString(R.string.theme_night), getString(R.string.theme_ocean),
                getString(R.string.theme_warm)};
        int current = repository.visualTheme();
        new AlertDialog.Builder(this).setTitle(R.string.visual_theme)
                .setSingleChoiceItems(themes, current >= 0 && current < themes.length ? current : 0,
                        (dialog, choice) -> {
                            repository.setVisualTheme(choice);
                            VisualTheme.apply(this, choice, findViewById(R.id.root),
                                    findViewById(R.id.hero), dock);
                            updateHero();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void requestHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, 0);
        boolean selected = resolved != null && resolved.activityInfo != null
                && getPackageName().equals(resolved.activityInfo.packageName)
                && MainActivity.class.getName().equals(resolved.activityInfo.name);
        if (selected) { toast(R.string.already_home); return; }
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager role = getSystemService(RoleManager.class);
            if (role != null && role.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (role.isRoleHeld(RoleManager.ROLE_HOME)) {
                    new AlertDialog.Builder(this).setMessage(R.string.home_role_not_selected)
                            .setPositiveButton(R.string.open_home_settings,
                                    (dialog, which) -> openSettings(Settings.ACTION_HOME_SETTINGS))
                            .setNegativeButton(android.R.string.cancel, null).show();
                    return;
                }
                try { startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME)); return; }
                catch (RuntimeException ignored) { /* Some TV firmwares only expose system settings. */ }
            }
        }
        openSettings(Settings.ACTION_HOME_SETTINGS);
    }

    private void showHidden() {
        Set<String> hidden = repository.hidden();
        List<AppRepository.App> hiddenApps = new ArrayList<>();
        for (AppRepository.App app : all) if (hidden.contains(app.packageName())) hiddenApps.add(app);
        if (hiddenApps.isEmpty()) { toast(R.string.no_hidden); return; }
        String[] labels = new String[hiddenApps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hiddenApps.get(i).label;
        new AlertDialog.Builder(this).setTitle(R.string.show_hidden).setItems(labels, (dialog, index) -> {
            repository.setHidden(hiddenApps.get(index).packageName(), false);
            render();
        }).show();
    }

    private void showInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> available = tim == null ? new ArrayList<>() : tim.getTvInputList();
        List<TvInputInfo> inputsFound = new ArrayList<>();
        for (TvInputInfo info : available) {
            if (info.isPassthroughInput() || info.getType() == TvInputInfo.TYPE_TUNER) inputsFound.add(info);
        }
        if (inputsFound.isEmpty()) { toast(R.string.no_inputs); openSettings(Settings.ACTION_SETTINGS); return; }
        String[] labels = new String[inputsFound.size()];
        for (int i = 0; i < labels.length; i++) {
            TvInputInfo input = inputsFound.get(i);
            CharSequence label = Build.VERSION.SDK_INT >= 24 ? input.loadCustomLabel(this) : null;
            if (label == null || label.length() == 0) label = input.loadLabel(this);
            labels[i] = label == null ? input.getId() : label.toString();
        }
        new AlertDialog.Builder(this).setTitle(R.string.inputs).setItems(labels, (dialog, index) -> {
            TvInputInfo choice = inputsFound.get(index);
            if (choice.isPassthroughInput()) {
                Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                        TvContract.buildChannelUriForPassthroughInput(choice.getId()),
                        "vnd.android.cursor.item/channel");
                try { startActivity(view); } catch (RuntimeException e) { toast(R.string.action_unavailable); }
            } else {
                Intent live = getPackageManager().getLaunchIntentForPackage(
                        choice.getServiceInfo().packageName);
                if (live != null) {
                    try { startActivity(live); }
                    catch (RuntimeException e) { openSettings(Settings.ACTION_SETTINGS); }
                } else openSettings(Settings.ACTION_SETTINGS);
            }
        }).show();
    }

    private void updateInputsVisibility() {
        TvInputManager manager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean hasInput = false;
        if (manager != null) {
            for (TvInputInfo info : manager.getTvInputList()) {
                if (info.isPassthroughInput() || info.getType() == TvInputInfo.TYPE_TUNER) {
                    hasInput = true;
                    break;
                }
            }
        }
        if (!hasInput && inputs.hasFocus()) network.requestFocus();
        inputs.setVisibility(hasInput ? View.VISIBLE : View.GONE);
    }

    private View firstTopAction() { return inputs.getVisibility() == View.VISIBLE ? inputs : network; }

    private static void focusVisibleAction(View[] actions, int index, int step) {
        for (int next = index + step; next >= 0 && next < actions.length; next += step) {
            if (actions[next].getVisibility() == View.VISIBLE) {
                actions[next].requestFocus();
                return;
            }
        }
    }

    private void updateHero() {
        heroName.setText(selectedApp == null ? getString(R.string.app_name) : selectedApp.label);
        VisualTheme.applyAccent(this, repository.visualTheme(), findViewById(R.id.root),
                selectedApp == null ? 0 : artwork.accent(selectedApp));
    }

    /** Down opens the library; returning to the dock restores the large top shelf. */
    void showLibrary(boolean show) {
        if (libraryVisible == show) return;
        libraryVisible = show;
        ((TextView) findViewById(R.id.library_hint)).setText(show
                ? R.string.long_press_hint : R.string.library_hint);
        findViewById(R.id.library).setVisibility(show ? View.VISIBLE : View.GONE);
        dock.setVisibility(show || dockAdapter.firstApp() < 0 ? View.GONE : View.VISIBLE);
        View hero = findViewById(R.id.hero);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) hero.getLayoutParams();
        params.height = show ? AppAdapter.dp(this, compact ? 48 : 56) : 0;
        params.weight = show ? 0 : 1;
        hero.setLayoutParams(params);
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, show ? 26 : compact ? 44 : 56);

    }

    private void returnToShelf() {
        int selected = dockAdapter.indexOf(lastDockFocused);
        if (dockAdapter.firstApp() >= 0) focusDock(selected >= 0 ? selected : dockAdapter.firstApp());
        else settings.requestFocus();
    }

    private void openAppDetails(AppRepository.App app) {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + app.packageName()))); }
        catch (RuntimeException ignored) { toast(R.string.action_unavailable); }
    }

    private void openSettings(String action) {
        try { startActivity(new Intent(action)); }
        catch (RuntimeException e) {
            if (!Settings.ACTION_SETTINGS.equals(action)) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); return; }
                catch (RuntimeException ignored) { /* Show an accessible message below. */ }
            }
            toast(R.string.action_unavailable);
        }
    }

    private void toast(int resource) { Toast.makeText(this, resource, Toast.LENGTH_SHORT).show(); }

    // Android 13+ uses OnBackInvokedCallback; older TV versions still dispatch here.
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        pendingGridPosition = RecyclerView.NO_POSITION;
        returnToShelf();
    }
}
