package com.adlesstv.launcher;

import android.app.Activity;
import android.app.ActivityManager;
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
import android.text.format.Formatter;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.util.TypedValue;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.TextView;
import android.widget.Toast;

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

/** A compact toolbar and one adaptive, scrolling catalog. No recurring background work. */
public final class MainActivity extends Activity implements AppAdapter.Listener {
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<AppRepository.App> all = new ArrayList<>();
    private final List<AppRepository.App> pinned = new ArrayList<>();
    private final List<AppRepository.App> tvInputs = new ArrayList<>();
    // Event-driven: the framework calls back when an input appears, disappears or changes state.
    private final TvInputManager.TvInputCallback inputChanges = new TvInputManager.TvInputCallback() {
        @Override public void onInputAdded(String inputId) { inputsChanged(); }
        @Override public void onInputRemoved(String inputId) { inputsChanged(); }
        @Override public void onInputUpdated(String inputId) { inputsChanged(); }
        @Override public void onInputStateChanged(String inputId, int state) { inputsChanged(); }
    };
    private TvInputManager inputManager;
    private TextView memory;
    private int heroAccent = Integer.MIN_VALUE;
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
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
    private View catalogContainer;
    private View idleSpacer;
    private RecyclerView dock;
    private GridLayoutManager layout;
    private TextView empty;
    private View network;
    private View inputs;
    private View settings;
    private View heroOpen;
    private View heroInfo;
    private TextView heroName;
    private TextView heroHint;
    private View heroActions;
    private View hero;
    private int heroMinHeight;
    private AppRepository.App selectedApp;
    private String lastFocused;
    private boolean resumed;
    private boolean dirty = true;
    private boolean scanning;
    private boolean destroyed;
    private int pendingGridPosition = RecyclerView.NO_POSITION;
    private int pendingDockPosition = RecyclerView.NO_POSITION;
    private OnBackInvokedCallback backCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (state != null) lastFocused = state.getString("focused_app");
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
        catalogContainer = findViewById(R.id.catalog_container);
        idleSpacer = findViewById(R.id.idle_spacer);
        dock = findViewById(R.id.dock);
        empty = findViewById(R.id.empty);
        inputs = findViewById(R.id.action_inputs);
        settings = findViewById(R.id.action_settings);
        network = findViewById(R.id.action_network);
        heroOpen = findViewById(R.id.hero_open);
        heroInfo = findViewById(R.id.hero_info);
        heroName = findViewById(R.id.hero_name);
        heroHint = findViewById(R.id.hero_hint);
        heroActions = findViewById(R.id.hero_actions);
        hero = findViewById(R.id.hero);
        heroMinHeight = hero.getMinimumHeight();
        memory = findViewById(R.id.memory);
        if (getResources().getDisplayMetrics().heightPixels < AppAdapter.dp(this, 500)) {
            findViewById(R.id.date).setVisibility(View.GONE);
            memory.setVisibility(View.GONE);
            memory = null;
            heroHint.setVisibility(View.GONE);
            heroHint = null;
            findViewById(R.id.top_bar).setMinimumHeight(AppAdapter.dp(this, 52));
            heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        }
        heroOpen.setOnClickListener(v -> { if (selectedApp != null) openApp(selectedApp); });
        heroInfo.setOnClickListener(v -> {
            if (selectedApp != null && !selectedApp.isInput()) openAppDetails(selectedApp);
        });
        heroOpen.setOnKeyListener((v, key, event) -> heroKey(key, event));
        heroInfo.setOnKeyListener((v, key, event) -> heroKey(key, event));
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::returnToDock;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        adapter = new AppAdapter(this, this, artwork, false);
        adapter.setReducedMotion(repository.reducedMotion());
        dockAdapter = new AppAdapter(this, new AppAdapter.Listener() {
            @Override public void focused(AppRepository.App app) { MainActivity.this.focused(app); }
            @Override public void opened(AppRepository.App app) { openApp(app); }
            @Override public void options(AppRepository.App app) { showOptions(app); }
            @Override public boolean navigate(int position, int key) { return navigateDock(position, key); }
        }, artwork, true);
        dockAdapter.setReducedMotion(repository.reducedMotion());
        VisualTheme.apply(this, repository.visualTheme(), root, findViewById(R.id.hero), dock);
        dock.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        dock.setAdapter(dockAdapter);
        dock.setItemAnimator(null);
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
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                applyColumns(right - left - grid.getPaddingLeft() - grid.getPaddingRight()));
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
                    heroOpen.requestFocus();
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
        inputManager = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (inputManager != null) {
            try { inputManager.registerCallback(inputChanges, main); }
            catch (RuntimeException unavailable) { inputManager = null; }
        }
        if (Build.VERSION.SDK_INT >= 24) {
            IntentFilter unlock = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(userUnlocked, unlock, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(userUnlocked, unlock);
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

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        updateMemory();
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
        super.onSaveInstanceState(state);
    }

    @SuppressWarnings("deprecation") // RUNNING_* levels are still delivered before API 34.
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (artwork == null) return;
        boolean critical = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
        // Opening an app hides Home: keep half the artwork so returning does not redraw every tile.
        if (critical || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) artwork.trim(critical);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        unregisterReceiver(packageChanges);
        if (inputManager != null) inputManager.unregisterCallback(inputChanges);
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
            List<AppRepository.App> inputs;
            try { next = repository.scan(); inputs = repository.scanInputs(); }
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
                tvInputs.clear();
                tvInputs.addAll(inputs);
                updateInputsVisibility();
                render();
                if (dirty) refresh();
            });
        });
    }

    private void render() {
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
        dock.setVisibility(pinned.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.replace(Collections.emptyList(), rest, tvInputs);
        selectedApp = null;
        int inDock = dockAdapter.indexOf(lastFocused);
        int inGrid = adapter.indexOf(lastFocused);
        if (inDock >= 0) selectedApp = dockAdapter.at(inDock);
        else if (inGrid >= 0) selectedApp = adapter.at(inGrid);
        else if (dockAdapter.firstApp() >= 0) selectedApp = dockAdapter.at(dockAdapter.firstApp());
        else if (adapter.firstApp() >= 0) selectedApp = adapter.at(adapter.firstApp());
        updateHero();
        empty.setText(R.string.no_apps);
        boolean noApps = adapter.firstApp() < 0 && dockAdapter.firstApp() < 0;
        empty.setVisibility(noApps ? View.VISIBLE : View.GONE);
        if (noApps) {
            setCatalogMode(false);
            catalogContainer.animate().cancel();
            catalogContainer.setAlpha(1f);
            catalogContainer.setTranslationY(0f);
            idleSpacer.setVisibility(View.GONE);
            catalogContainer.setVisibility(View.VISIBLE);
            settings.requestFocus();
            return;
        }
        // Preserve the catalog state when the package list refreshes.
        if (catalogContainer.getVisibility() == View.VISIBLE && inGrid >= 0) {
            focusGrid(inGrid);
        } else if (inDock >= 0 || dockAdapter.firstApp() >= 0) {
            focusDock(inDock >= 0 ? inDock : dockAdapter.firstApp());
        } else {
            focusGrid(inGrid >= 0 ? inGrid : adapter.firstApp());
        }
    }

    /**
     * The hero buttons are reachable only from the dock (Up). While the catalog is open they
     * would just take rows away from the grid, which matters at 720 x 480 with large text.
     */
    private void setCatalogMode(boolean open) {
        int visibility = open ? View.GONE : View.VISIBLE;
        if (heroActions.getVisibility() != visibility) heroActions.setVisibility(visibility);
        if (heroHint != null && heroHint.getVisibility() != visibility) heroHint.setVisibility(visibility);
        int minimum = open ? 0 : heroMinHeight;
        if (hero.getMinimumHeight() != minimum) hero.setMinimumHeight(minimum);
        // The grid slides up over the dock; Up from its first row brings the dock back.
        int dockVisibility = open || dockAdapter.firstApp() < 0 ? View.GONE : View.VISIBLE;
        if (dock.getVisibility() != dockVisibility) dock.setVisibility(dockVisibility);
    }

    /** Width-adaptive by default; a chosen count is honoured while tiles stay at least 88 dp wide. */
    private void applyColumns(int available) {
        if (available <= 0) return;
        int automatic = Math.max(2, Math.min(6, available / AppAdapter.dp(this, 120)));
        int chosen = repository.columns();
        int widest = Math.max(2, available / AppAdapter.dp(this, 88));
        int columns = chosen > 0 ? Math.min(chosen, widest) : automatic;
        if (columns == layout.getSpanCount()) return;
        layout.setSpanCount(columns);
        adapter.setColumns(columns);
        int selected = adapter.indexOf(lastFocused);
        if (selected >= 0 && catalogContainer.getVisibility() == View.VISIBLE) focusGrid(selected);
    }

    private void showColumns() {
        String[] labels = {getString(R.string.columns_auto), "4", "5", "6", "7"};
        int current = repository.columns();
        new AlertDialog.Builder(this).setTitle(R.string.columns)
                .setSingleChoiceItems(labels, current == 0 ? 0 : current - 3, (dialog, choice) -> {
                    repository.setColumns(choice == 0 ? 0 : choice + 3);
                    applyColumns(grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight());
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void focusDock(int position) {
        setCatalogMode(false);
        catalogContainer.animate().cancel();
        catalogContainer.setTranslationY(0f);
        catalogContainer.setAlpha(1f);
        catalogContainer.setVisibility(View.GONE);
        idleSpacer.setVisibility(View.VISIBLE);
        pendingGridPosition = RecyclerView.NO_POSITION;
        if (position < 0 || position >= dockAdapter.getItemCount()) {
            pendingDockPosition = RecyclerView.NO_POSITION;
            if (!heroOpen.requestFocus()) settings.requestFocus();
            return;
        }
        pendingGridPosition = RecyclerView.NO_POSITION;
        pendingDockPosition = position;
        RecyclerView.ViewHolder holder = dock.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) {
            pendingDockPosition = RecyclerView.NO_POSITION;
            return;
        }
        dock.scrollToPosition(position);
        dock.post(this::applyPendingFocus);
    }

    private void focusGrid(int position) {
        if (position < 0 || position >= adapter.getItemCount() || adapter.isHeading(position)) {
            pendingGridPosition = RecyclerView.NO_POSITION;
            if (dockAdapter.firstApp() >= 0) focusDock(dockAdapter.firstApp());
            else settings.requestFocus();
            return;
        }
        // Reveal the catalog only when a valid app can receive focus.
        // A newly visible RecyclerView may not have laid out its children yet;
        // applyPendingFocus completes the handoff after layout.
        boolean openingCatalog = catalogContainer.getVisibility() != View.VISIBLE;
        // Without a dock, Up from the grid lands on the hero buttons: keep them visible then.
        setCatalogMode(dockAdapter.firstApp() >= 0);
        catalogContainer.animate().cancel();
        idleSpacer.setVisibility(View.GONE);
        catalogContainer.setVisibility(View.VISIBLE);
        if (openingCatalog && !repository.reducedMotion()) {
            catalogContainer.setAlpha(0f);
            catalogContainer.setTranslationY(AppAdapter.dp(this, 24));
            catalogContainer.animate().alpha(1f).translationY(0f).setDuration(200).start();
        } else {
            catalogContainer.setAlpha(1f);
            catalogContainer.setTranslationY(0f);
        }
        pendingDockPosition = RecyclerView.NO_POSITION;
        pendingGridPosition = position;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) {
            pendingGridPosition = RecyclerView.NO_POSITION;
            return;
        }
        grid.scrollToPosition(position == adapter.firstApp() ? 0 : position);
        grid.post(this::applyPendingFocus);
    }

    private void applyPendingFocus() {
        if (!resumed) return;
        if (pendingDockPosition >= 0) {
            RecyclerView.ViewHolder tile = dock.findViewHolderForAdapterPosition(pendingDockPosition);
            if (tile != null && tile.itemView.requestFocus()) pendingDockPosition = RecyclerView.NO_POSITION;
            return;
        }
        if (pendingGridPosition < 0) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pendingGridPosition);
        if (holder != null && holder.itemView.requestFocus()) pendingGridPosition = RecyclerView.NO_POSITION;
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
            else heroOpen.requestFocus();
        }
        else if (next != from) focusGrid(next);
        return true;
    }

    private boolean navigateDock(int position, int key) {
        if (key == KeyEvent.KEYCODE_DPAD_UP) { heroOpen.requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (adapter.firstApp() >= 0) focusGrid(adapter.firstApp());
            return true;
        }
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        int step = key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
        if (dock.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) step = -step;
        int next = position + step;
        if (next >= 0 && next < dockAdapter.getItemCount()) focusDock(next);
        return true;
    }

    private void openApp(AppRepository.App app) {
        lastFocused = app.key();
        if (app.isInput()) { openInput(app.input); return; }
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
        if (app.isInput()) { showInputs(); return; }
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
        List<Integer> actions = new ArrayList<>();
        actions.add(R.string.default_home);
        actions.add(R.string.original_home);
        actions.add(R.string.show_hidden);
        actions.add(R.string.reset_favorites);
        actions.add(R.string.visual_theme);
        actions.add(R.string.columns);
        actions.add(repository.reducedMotion() ? R.string.motion_enable : R.string.motion_reduce);
        actions.add(repository.showMemory() ? R.string.memory_hide : R.string.memory_show);
        actions.add(R.string.clock_dream);
        actions.add(R.string.settings);
        String[] labels = new String[actions.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = getString(actions.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.manage).setItems(labels, (dialog, choice) -> {
            int action = actions.get(choice);
            if (action == R.string.default_home) requestHome();
            else if (action == R.string.original_home) showOriginalHomes();
            else if (action == R.string.show_hidden) showHidden();
            else if (action == R.string.reset_favorites) new AlertDialog.Builder(this).setMessage(R.string.confirm_reset)
                    .setPositiveButton(android.R.string.ok, (d, which) -> { repository.resetFavorites(); render(); })
                    .setNegativeButton(android.R.string.cancel, null).show();
            else if (action == R.string.visual_theme) showVisualThemes();
            else if (action == R.string.columns) showColumns();
            else if (action == R.string.motion_enable || action == R.string.motion_reduce) {
                boolean reduced = !repository.reducedMotion();
                repository.setReducedMotion(reduced);
                adapter.setReducedMotion(reduced);
                dockAdapter.setReducedMotion(reduced);
            } else if (action == R.string.memory_hide || action == R.string.memory_show) {
                repository.setShowMemory(!repository.showMemory());
                updateMemory();
            } else if (action == R.string.clock_dream) openSettings("android.settings.DREAM_SETTINGS");
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
                            heroAccent = Integer.MIN_VALUE;
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
        if (tvInputs.isEmpty()) { toast(R.string.no_inputs); openSettings(Settings.ACTION_SETTINGS); return; }
        List<AppRepository.App> inputsFound = new ArrayList<>(tvInputs);
        String[] labels = new String[inputsFound.size()];
        for (int i = 0; i < labels.length; i++) {
            AppRepository.App input = inputsFound.get(i);
            labels[i] = getString(R.string.input_description, input.label,
                    getString(InputStates.label(input.inputState)));
        }
        new AlertDialog.Builder(this).setTitle(R.string.inputs).setItems(labels,
                (dialog, index) -> openInput(inputsFound.get(index).input)).show();
    }

    private void openInput(TvInputInfo choice) {
        if (choice.isPassthroughInput()) {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(
                    TvContract.buildChannelUriForPassthroughInput(choice.getId()),
                    "vnd.android.cursor.item/channel");
            try { startActivity(view); } catch (RuntimeException e) { toast(R.string.action_unavailable); }
            return;
        }
        Intent live = getPackageManager().getLaunchIntentForPackage(choice.getServiceInfo().packageName);
        if (live == null) live = getPackageManager().getLeanbackLaunchIntentForPackage(
                choice.getServiceInfo().packageName);
        if (live != null) {
            try { startActivity(live); return; }
            catch (RuntimeException ignored) { /* Fall back to the settings below. */ }
        }
        openSettings(Settings.ACTION_SETTINGS);
    }

    private void inputsChanged() {
        dirty = true;
        if (resumed) refresh();
    }

    private void updateInputsVisibility() {
        boolean hasInput = !tvInputs.isEmpty();
        if (!hasInput && inputs.hasFocus()) network.requestFocus();
        inputs.setVisibility(hasInput ? View.VISIBLE : View.GONE);
    }

    /** One Binder read per resume; Home never polls. Shows the system figure, nothing estimated. */
    private void updateMemory() {
        if (memory == null) return;
        if (!repository.showMemory()) { memory.setVisibility(View.GONE); return; }
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) { memory.setVisibility(View.GONE); return; }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        memory.setText(getString(R.string.memory_free, Formatter.formatShortFileSize(this, info.availMem),
                Formatter.formatShortFileSize(this, info.totalMem)));
        memory.setTextColor(info.lowMemory ? 0xFFFFC27A : 0xFFA2A9B6);
        memory.setVisibility(View.VISIBLE);
    }

    /**
     * Escape hatch without a computer: open another enabled launcher. Before Android 10 this also
     * clears AdLessTV as preferred Home, so the next Home press asks which launcher to use.
     */
    private void showOriginalHomes() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> found = getPackageManager().queryIntentActivities(query, 0);
        List<ResolveInfo> others = new ArrayList<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName)) continue;
            if (info.activityInfo.name.endsWith(".FallbackHome")) continue;
            others.add(info);
        }
        if (others.isEmpty()) {
            new AlertDialog.Builder(this).setMessage(R.string.original_home_missing)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        String[] labels = new String[others.size()];
        for (int i = 0; i < labels.length; i++) {
            CharSequence label = others.get(i).loadLabel(getPackageManager());
            labels[i] = label == null ? others.get(i).activityInfo.packageName : label.toString();
        }
        new AlertDialog.Builder(this).setTitle(R.string.original_home).setItems(labels, (dialog, index) -> {
            ResolveInfo choice = others.get(index);
            releaseHome();
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClassName(choice.activityInfo.packageName, choice.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                toast(R.string.original_home_opened);
            } catch (RuntimeException e) { toast(R.string.action_unavailable); }
        }).show();
    }

    @SuppressWarnings("deprecation")
    private void releaseHome() {
        // An app may clear only its own preferred activities; Android 10+ manages HOME as a role.
        if (Build.VERSION.SDK_INT < 29) getPackageManager().clearPackagePreferredActivities(getPackageName());
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
        heroOpen.setEnabled(selectedApp != null);
        heroInfo.setEnabled(selectedApp != null && !selectedApp.isInput());
        heroInfo.setFocusable(heroInfo.isEnabled());
        if (heroHint != null) heroHint.setText(selectedApp != null && selectedApp.isInput()
                ? getString(InputStates.label(selectedApp.inputState)) : getString(R.string.long_press_hint));
        int accent = selectedApp == null ? 0 : artwork.accent(selectedApp);
        if (accent == heroAccent) return;
        heroAccent = accent;
        VisualTheme.applyAccent(this, repository.visualTheme(), findViewById(R.id.hero), accent);
    }

    private boolean heroKey(int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP) { firstTopAction().requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int selected = dockAdapter.indexOf(lastFocused);
            if (dockAdapter.firstApp() >= 0) focusDock(selected >= 0 ? selected : dockAdapter.firstApp());
            else focusGrid(adapter.indexOf(lastFocused) >= 0 ? adapter.indexOf(lastFocused) : adapter.firstApp());
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            boolean towardsInfo = key == KeyEvent.KEYCODE_DPAD_RIGHT;
            if (getWindow().getDecorView().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL)
                towardsInfo = !towardsInfo;
            (towardsInfo && heroInfo.isEnabled() ? heroInfo : heroOpen).requestFocus();
            return true;
        }
        return false;
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
    private void returnToDock() {
        pendingGridPosition = RecyclerView.NO_POSITION;
        if (catalogContainer.getVisibility() == View.VISIBLE && dockAdapter.firstApp() >= 0) {
            int previous = dockAdapter.indexOf(lastFocused);
            focusDock(previous >= 0 ? previous : dockAdapter.firstApp());
        } else if (dockAdapter.firstApp() >= 0) {
            focusDock(dockAdapter.firstApp());
        } else {
            setCatalogMode(false);
            catalogContainer.setVisibility(View.GONE);
            idleSpacer.setVisibility(View.VISIBLE);
            settings.requestFocus();
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        returnToDock();
    }
}
