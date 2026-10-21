package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.UserManager;

import org.json.JSONArray;
import org.json.JSONException;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Package discovery and on-device choices. No package is disabled or hidden by Android. */
final class AppRepository {
    static final class App {
        final ComponentName component;
        final String label;
        final ResolveInfo info;
        /** Non-null only for a real TV input (HDMI, AV, tuner) reported by the system. */
        final TvInputInfo input;
        final int inputState;

        App(ComponentName component, String label, ResolveInfo info) {
            this(component, label, info, null, TvInputManager.INPUT_STATE_CONNECTED);
        }

        App(ComponentName component, String label, ResolveInfo info, TvInputInfo input, int inputState) {
            this.component = component;
            this.label = label;
            this.info = info;
            this.input = input;
            this.inputState = inputState;
        }

        boolean isInput() { return input != null; }
        String key() { return input != null ? "input:" + input.getId() : component.flattenToString(); }
        String packageName() { return component.getPackageName(); }
    }

    private static final String PREFS = "home_choices";
    private static final String FAVS = "favorites";
    private static final String HIDDEN = "hidden_packages";
    private static final String REDUCED_MOTION = "reduced_motion";
    private static final String VISUAL_THEME = "visual_theme";
    private static final String SHOW_MEMORY = "show_memory";
    private static final String COLUMNS = "columns";
    private final Context context;
    private final SharedPreferences preferences;

    AppRepository(Context context) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 24) {
            this.context = application.createDeviceProtectedStorageContext();
            UserManager user = application.getSystemService(UserManager.class);
            if (user != null && user.isUserUnlocked()) this.context.moveSharedPreferencesFrom(application, PREFS);
        } else this.context = application;
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<App> scan() {
        PackageManager pm = context.getPackageManager();
        Map<String, App> found = new HashMap<>();
        find(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
        find(pm, Intent.CATEGORY_LAUNCHER, found);
        List<App> result = new ArrayList<>(found.values());
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(result, (a, b) -> {
            int compared = collator.compare(a.label, b.label);
            return compared != 0 ? compared : a.key().compareTo(b.key());
        });
        return result;
    }

    private void find(PackageManager pm, String category, Map<String, App> found) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            if (info.activityInfo == null) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            if (component.getPackageName().equals(context.getPackageName())) continue;
            String key = component.getPackageName();
            if (found.containsKey(key)) continue;
            CharSequence text = info.loadLabel(pm);
            String label = text == null ? component.getPackageName() : text.toString();
            App app = new App(component, label, info);
            // One tile per installed package; the TV activity was queried first.
            found.put(key, app);
        }
    }

    /**
     * HDMI, composite and tuner inputs exposed by the TV framework. Custom names set by the user
     * in the TV settings take precedence. Runs on the scanner thread: each call is a Binder IPC.
     */
    List<App> scanInputs() {
        TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        List<App> result = new ArrayList<>();
        if (manager == null) return result;
        List<TvInputInfo> available;
        try { available = manager.getTvInputList(); }
        catch (RuntimeException unavailable) { return result; }
        for (TvInputInfo info : available) {
            if (!info.isPassthroughInput() && info.getType() != TvInputInfo.TYPE_TUNER) continue;
            if (info.getServiceInfo() == null) continue;
            CharSequence text = null;
            if (Build.VERSION.SDK_INT >= 24) {
                if (info.isHidden(context)) continue;
                text = info.loadCustomLabel(context);
            }
            if (text == null || text.toString().trim().isEmpty()) text = info.loadLabel(context);
            String label = text == null || text.toString().trim().isEmpty() ? info.getId() : text.toString();
            int state = TvInputManager.INPUT_STATE_CONNECTED;
            if (Build.VERSION.SDK_INT >= 24) {
                try { state = manager.getInputState(info.getId()); }
                catch (RuntimeException ignored) { /* Some firmwares reject state queries. */ }
            }
            result.add(new App(new ComponentName(info.getServiceInfo().packageName, info.getServiceInfo().name),
                    label, null, info, state));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(result, (a, b) -> {
            boolean aTuner = a.input.getType() == TvInputInfo.TYPE_TUNER;
            boolean bTuner = b.input.getType() == TvInputInfo.TYPE_TUNER;
            if (aTuner != bTuner) return aTuner ? 1 : -1;
            int byType = Integer.compare(order(a.input.getType()), order(b.input.getType()));
            if (byType != 0) return byType;
            int compared = NaturalOrder.compare(a.label, b.label, collator);
            return compared != 0 ? compared : a.key().compareTo(b.key());
        });
        return result;
    }

    private static int order(int type) {
        if (type == TvInputInfo.TYPE_HDMI) return 0;
        if (type == TvInputInfo.TYPE_DISPLAY_PORT) return 1;
        if (type == TvInputInfo.TYPE_COMPONENT) return 2;
        if (type == TvInputInfo.TYPE_COMPOSITE || type == TvInputInfo.TYPE_SVIDEO
                || type == TvInputInfo.TYPE_SCART) return 3;
        if (type == TvInputInfo.TYPE_VGA || type == TvInputInfo.TYPE_DVI) return 4;
        return 5;
    }

    List<String> favorites(List<App> all) {
        if (preferences.contains(FAVS)) {
            List<String> chosen = new ArrayList<>();
            for (String key : decode(preferences.getString(FAVS, "[]"))) {
                ComponentName saved = ComponentName.unflattenFromString(key);
                for (App app : all) {
                    if (saved != null && saved.getPackageName().equals(app.packageName()) && !chosen.contains(app.key())) {
                        chosen.add(app.key());
                        break;
                    }
                }
            }
            return chosen;
        }
        List<String> result = new ArrayList<>();
        for (String pkg : context.getResources().getStringArray(R.array.dock_apps)) {
            for (App app : all) {
                if (app.packageName().equals(pkg) && !result.contains(app.key())) {
                    result.add(app.key());
                    break;
                }
            }
        }
        // Fill missing default slots with installed apps; user choices above remain exact.
        for (App app : all) {
            if (result.size() >= 4) break;
            if (!result.contains(app.key())) result.add(app.key());
        }
        return result;
    }

    void saveFavorites(List<String> favorites) {
        preferences.edit().putString(FAVS, new JSONArray(favorites).toString()).apply();
    }

    void resetFavorites() { preferences.edit().remove(FAVS).apply(); }

    boolean reducedMotion() { return preferences.getBoolean(REDUCED_MOTION, false); }

    void setReducedMotion(boolean enabled) {
        preferences.edit().putBoolean(REDUCED_MOTION, enabled).apply();
    }

    /** 0 adapts to the screen width; 4 to 7 are explicit choices made with the remote. */
    int columns() {
        int value = preferences.getInt(COLUMNS, 0);
        return value >= 4 && value <= 7 ? value : 0;
    }

    void setColumns(int columns) {
        preferences.edit().putInt(COLUMNS, columns >= 4 && columns <= 7 ? columns : 0).apply();
    }

    boolean showMemory() { return preferences.getBoolean(SHOW_MEMORY, true); }

    void setShowMemory(boolean enabled) {
        preferences.edit().putBoolean(SHOW_MEMORY, enabled).apply();
    }

    int visualTheme() { return preferences.getInt(VISUAL_THEME, 0); }

    void setVisualTheme(int theme) {
        preferences.edit().putInt(VISUAL_THEME, theme).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(preferences.getStringSet(HIDDEN, Collections.emptySet()));
    }

    void setHidden(String pkg, boolean hidden) {
        Set<String> changed = hidden();
        if (hidden) changed.add(pkg); else changed.remove(pkg);
        preferences.edit().putStringSet(HIDDEN, changed).apply();
    }

    private static List<String> decode(String serialized) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(serialized);
            for (int i = 0; i < array.length(); i++) {
                String key = array.getString(i);
                if (ComponentName.unflattenFromString(key) != null && !out.contains(key)) out.add(key);
            }
        } catch (JSONException ignored) {
            // Corrupt preferences should not prevent Home from opening.
        }
        return out;
    }
}
