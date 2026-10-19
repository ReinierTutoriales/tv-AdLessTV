package com.adlesstv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

        App(ComponentName component, String label, ResolveInfo info) {
            this.component = component;
            this.label = label;
            this.info = info;
        }

        String key() { return component.flattenToString(); }
        String packageName() { return component.getPackageName(); }
    }

    private static final String PREFS = "home_choices";
    private static final String FAVS = "favorites";
    private static final String HIDDEN = "hidden_packages";
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
        // A useful dock even when none of the configured apps is installed.
        if (result.isEmpty()) {
            for (App app : all) {
                if (result.size() == 4) break;
                result.add(app.key());
            }
        }
        return result;
    }

    void saveFavorites(List<String> favorites) {
        preferences.edit().putString(FAVS, new JSONArray(favorites).toString()).apply();
    }

    void resetFavorites() { preferences.edit().remove(FAVS).apply(); }

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
