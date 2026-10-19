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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
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
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network network) { main.post(MainActivity.this::updateNetwork); }
        @Override public void onLost(@NonNull Network network) { main.post(MainActivity.this::updateNetwork); }
    };
    private AppRepository repository;
    private AppAdapter adapter;
    private ArtworkLoader artwork;
    private RecyclerView grid;
    private GridLayoutManager layout;
    private TextView empty;
    private View network;
    private View inputs;
    private View settings;
    private String lastFocused;
    private boolean resumed;
    private boolean callbackRegistered;
    private boolean dirty = true;
    private boolean scanning;
    private boolean destroyed;
    private int pendingGridPosition = RecyclerView.NO_POSITION;
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
        artwork = new ArtworkLoader(this);
        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        inputs = findViewById(R.id.action_inputs);
        settings = findViewById(R.id.action_settings);
        network = findViewById(R.id.action_network);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = () -> settings.requestFocus();
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        adapter = new AppAdapter(this, this, artwork);
        layout = new GridLayoutManager(this, 4);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.isHeading(position) ? layout.getSpanCount() : 1;
            }
        });
        grid.setLayoutManager(layout);
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.getViewTreeObserver().addOnGlobalLayoutListener(this::applyPendingFocus);
        grid.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - grid.getPaddingLeft() - grid.getPaddingRight();
            int columns = Math.max(2, Math.min(6, available / AppAdapter.dp(this, 172)));
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
                    actions[Math.max(0, Math.min(actions.length - 1, index + step))].requestFocus(); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int step = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                    actions[Math.max(0, Math.min(actions.length - 1, index + step))].requestFocus(); return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    int selected = adapter.indexOf(lastFocused);
                    focusGrid(selected >= 0 ? selected : adapter.firstApp());
                    return true;
                }
                return false;
            });
            actions[i].setOnFocusChangeListener((v, focused) -> {
                if (focused) pendingGridPosition = RecyclerView.NO_POSITION;
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
        updateNetwork();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && !callbackRegistered) {
            try {
                if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkCallback);
                else cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networkCallback);
                callbackRegistered = true;
            } catch (RuntimeException ignored) { /* Connectivity is only a local status hint. */ }
        }
        if (dirty) refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        pendingGridPosition = RecyclerView.NO_POSITION;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && callbackRegistered) {
            cm.unregisterNetworkCallback(networkCallback);
            callbackRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putString("focused_app", lastFocused);
        super.onSaveInstanceState(state);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && artwork != null) artwork.trim();
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
        pendingGridPosition = RecyclerView.NO_POSITION;
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
        adapter.replace(pinned, rest);
        empty.setText(R.string.no_apps);
        empty.setVisibility(adapter.firstApp() < 0 ? View.VISIBLE : View.GONE);
        int selected = adapter.indexOf(lastFocused);
        focusGrid(selected >= 0 ? selected : adapter.firstApp());
    }

    private void focusGrid(int position) {
        if (position < 0 || position >= adapter.getItemCount() || adapter.isHeading(position)) {
            settings.requestFocus(); return;
        }
        pendingGridPosition = position;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null && holder.itemView.requestFocus()) return;
        grid.scrollToPosition(position == adapter.firstApp() ? 0 : position);
        grid.post(this::applyPendingFocus);
    }

    private void applyPendingFocus() {
        if (!resumed || pendingGridPosition < 0) return;
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(pendingGridPosition);
        if (holder != null) holder.itemView.requestFocus();
    }

    @Override public void focused(AppRepository.App app) {
        pendingGridPosition = RecyclerView.NO_POSITION;
        lastFocused = app.key();
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
        if (next == GridNavigation.TOP_BAR) inputs.requestFocus();
        else if (next != from) focusGrid(next);
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
                try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.packageName()))); }
                catch (RuntimeException ignored) { toast(R.string.action_unavailable); }
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
                getString(R.string.default_home), getString(R.string.clock_dream), getString(R.string.settings)};
        new AlertDialog.Builder(this).setTitle(R.string.manage).setItems(labels, (dialog, choice) -> {
            if (choice == 0) showHidden();
            else if (choice == 1) new AlertDialog.Builder(this).setMessage(R.string.confirm_reset)
                    .setPositiveButton(android.R.string.ok, (d, which) -> { repository.resetFavorites(); render(); })
                    .setNegativeButton(android.R.string.cancel, null).show();
            else if (choice == 2) requestHome();
            else if (choice == 3) openSettings("android.settings.DREAM_SETTINGS");
            else openSettings(Settings.ACTION_SETTINGS);
        }).show();
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
            CharSequence label = input.loadLabel(this);
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

    private void updateNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean connected = cm != null && cm.getActiveNetwork() != null;
        network.setAlpha(connected ? 1f : 0.55f);
        network.setContentDescription(getString(R.string.network) + ": "
                + getString(connected ? R.string.connected : R.string.offline));
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
        settings.requestFocus();
    }
}
