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
        /** Non-null only for a card another app declared; identifies package, activity and signer. */
        final String cardKey;
        final String caption;

        App(ComponentName component, String label, ResolveInfo info) {
            this(component, label, info, null, TvInputManager.INPUT_STATE_CONNECTED, null, null);
        }

        App(ComponentName component, String label, ResolveInfo info, TvInputInfo input, int inputState) {
            this(component, label, info, input, inputState, null, null);
        }

        App(ComponentName component, String label, ResolveInfo info, TvInputInfo input, int inputState,
            String cardKey, String caption) {
            this.component = component;
            this.label = label;
            this.info = info;
            this.input = input;
            this.inputState = inputState;
            this.cardKey = cardKey;
            this.caption = caption;
        }

        boolean isInput() { return input != null; }
        boolean isCard() { return cardKey != null; }
        String key() {
            if (input != null) return "input:" + input.getId();
            if (cardKey != null) return "card:" + component.flattenToString();
            return component.flattenToString();
        }
        String packageName() { return component.getPackageName(); }
    }

    private static final String PREFS = "home_choices";
    private static final String FAVS = "favorites";
    private static final String HIDDEN = "hidden_packages";
    private static final String REDUCED_MOTION = "reduced_motion";
    private static final String VISUAL_THEME = "visual_theme";
    private static final String SHOW_MEMORY = "show_memory";
    private static final String COLUMNS = "columns";
    private static final String SHOWN = "shown_packages";
    private static final String CARDS_ALLOWED = "cards_allowed";
    private static final String CARDS_DENIED = "cards_denied";
    static final String CARD_ACTION = "com.adlesstv.launcher.action.CARD";
    static final String CARD_CAPTION = "com.adlesstv.card.caption";
    private final Context context;
    private final SharedPreferences preferences;
    private HomeConfig config;

    AppRepository(Context context) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 24) {
            this.context = application.createDeviceProtectedStorageContext();
            UserManager user = application.getSystemService(UserManager.class);
            if (user != null && user.isUserUnlocked()) this.context.moveSharedPreferencesFrom(application, PREFS);
        } else this.context = application;
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    HomeConfig config() {
        if (config == null) config = HomeConfig.load(context);
        return config;
    }

    void reloadConfig() { config = HomeConfig.load(context); }

    /** A pushed theme resets the layout and motion choices made with the remote. */
    void followTheme() {
        preferences.edit().remove(COLUMNS).remove(REDUCED_MOTION).remove(SHOW_MEMORY).apply();
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
            String custom = config().appLabels.get(component.getPackageName());
            if (custom != null && !custom.isEmpty()) label = custom;
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

    /** Every card declared by an installed app; whether it shows is the user's decision. */
    // The caption meta-data may be a string or a resource id: the untyped Bundle.get() reads
    // either without the typed getters' class-cast warnings.
    @SuppressWarnings("deprecation")
    List<App> scanCards() {
        List<App> result = new ArrayList<>();
        if (!config().moduleCards) return result;
        PackageManager pm = context.getPackageManager();
        Intent query = new Intent(CARD_ACTION);
        List<ResolveInfo> found;
        try { found = pm.queryIntentActivities(query, PackageManager.GET_META_DATA); }
        catch (RuntimeException unavailable) { return result; }
        for (ResolveInfo info : found) {
            if (info.activityInfo == null || !info.activityInfo.exported) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName())) continue;
            String signer = signer(pm, pkg);
            if (signer == null) continue;
            ComponentName component = new ComponentName(pkg, info.activityInfo.name);
            CharSequence text = info.loadLabel(pm);
            String label = text == null ? pkg : text.toString();
            String caption = "";
            if (info.activityInfo.metaData != null) {
                Object value = info.activityInfo.metaData.get(CARD_CAPTION);
                if (value instanceof Integer) {
                    try { caption = pm.getResourcesForApplication(info.activityInfo.applicationInfo)
                            .getString((Integer) value); }
                    catch (PackageManager.NameNotFoundException | RuntimeException ignored) { caption = ""; }
                } else if (value instanceof String) caption = (String) value;
            }
            if (caption.length() > 80) caption = caption.substring(0, 80);
            result.add(new App(component, label, info, null, TvInputManager.INPUT_STATE_CONNECTED,
                    component.flattenToString() + "#" + signer, caption));
        }
        Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(result, (a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    @SuppressWarnings("deprecation")
    private static String signer(PackageManager pm, String pkg) {
        try {
            android.content.pm.Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) return null;
                signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory();
            } else {
                signatures = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures;
            }
            if (signatures == null || signatures.length == 0) return null;
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(signatures[signatures.length - 1].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (PackageManager.NameNotFoundException | java.security.NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    /** 1 = allowed, -1 = kept hidden, 0 = not asked yet. A new signer is a new, unasked card. */
    int cardDecision(String cardKey) {
        if (preferences.getStringSet(CARDS_ALLOWED, Collections.emptySet()).contains(cardKey)) return 1;
        if (preferences.getStringSet(CARDS_DENIED, Collections.emptySet()).contains(cardKey)) return -1;
        return 0;
    }

    void setCardDecision(String cardKey, boolean allowed) {
        Set<String> yes = new HashSet<>(preferences.getStringSet(CARDS_ALLOWED, Collections.emptySet()));
        Set<String> no = new HashSet<>(preferences.getStringSet(CARDS_DENIED, Collections.emptySet()));
        if (allowed) { yes.add(cardKey); no.remove(cardKey); } else { no.add(cardKey); yes.remove(cardKey); }
        preferences.edit().putStringSet(CARDS_ALLOWED, yes).putStringSet(CARDS_DENIED, no).apply();
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
        for (String pkg : config().dockApps) {
            for (App app : all) {
                if (app.packageName().equals(pkg) && !result.contains(app.key())) {
                    result.add(app.key());
                    break;
                }
            }
        }
        // Fill missing default slots with installed apps; user choices above remain exact.
        Set<String> hidden = hidden();
        if (config().fillDock) for (App app : all) {
            if (hidden.contains(app.packageName())) continue;
            if (result.size() >= 4) break;
            if (!result.contains(app.key())) result.add(app.key());
        }
        return result;
    }

    void saveFavorites(List<String> favorites) {
        preferences.edit().putString(FAVS, new JSONArray(favorites).toString()).apply();
    }

    void resetFavorites() { preferences.edit().remove(FAVS).apply(); }

    boolean reducedMotion() {
        if (preferences.contains(REDUCED_MOTION)) return preferences.getBoolean(REDUCED_MOTION, false);
        Boolean themed = config().reducedMotion;
        return themed != null && themed;
    }

    void setReducedMotion(boolean enabled) {
        preferences.edit().putBoolean(REDUCED_MOTION, enabled).apply();
    }

    /** 0 adapts to the screen width; 4 to 7 are explicit choices made with the remote. */
    int columns() {
        if (preferences.contains(COLUMNS)) {
            int value = preferences.getInt(COLUMNS, 0);
            return value >= 2 && value <= 8 ? value : 0;
        }
        return config().columns;
    }

    /** 0 = automatic; stored even when 0 so an explicit "Automatic" overrides a themed count. */
    void setColumns(int columns) {
        preferences.edit().putInt(COLUMNS, columns >= 2 && columns <= 8 ? columns : 0).apply();
    }

    boolean showMemory() {
        if (preferences.contains(SHOW_MEMORY)) return preferences.getBoolean(SHOW_MEMORY, true);
        Boolean themed = config().showStats;
        return themed == null || themed;
    }

    void setShowMemory(boolean enabled) {
        preferences.edit().putBoolean(SHOW_MEMORY, enabled).apply();
    }

    int visualTheme() { return preferences.getInt(VISUAL_THEME, 0); }

    void setVisualTheme(int theme) {
        preferences.edit().putInt(VISUAL_THEME, theme).apply();
    }

    /** Hidden by the user, plus the theme's hidden_apps unless the user chose to show them. */
    Set<String> hidden() {
        Set<String> result = new HashSet<>(preferences.getStringSet(HIDDEN, Collections.emptySet()));
        Set<String> shown = preferences.getStringSet(SHOWN, Collections.emptySet());
        for (String pkg : config().hiddenApps) if (!shown.contains(pkg)) result.add(pkg);
        return result;
    }

    void setHidden(String pkg, boolean hidden) {
        Set<String> chosen = new HashSet<>(preferences.getStringSet(HIDDEN, Collections.emptySet()));
        Set<String> shown = new HashSet<>(preferences.getStringSet(SHOWN, Collections.emptySet()));
        if (hidden) { chosen.add(pkg); shown.remove(pkg); }
        else { chosen.remove(pkg); if (config().hiddenApps.contains(pkg)) shown.add(pkg); }
        preferences.edit().putStringSet(HIDDEN, chosen).putStringSet(SHOWN, shown).apply();
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
