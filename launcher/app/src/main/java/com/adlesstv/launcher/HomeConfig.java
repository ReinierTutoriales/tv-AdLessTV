package com.adlesstv.launcher;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in defaults from config.xml with an optional theme overlay (config.json) pushed over ADB.
 * Every key is optional; a wrong type is ignored and an unreadable file is ignored as a whole, so
 * a theme can never stop Home from opening. Choices made on the TV take precedence (AppRepository).
 */
final class HomeConfig {
    static final String THEME_DIR = "theme";
    static final String CONFIG = "config.json";
    static final String WALLPAPER = "wallpaper.jpg";
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    List<String> dockApps = new ArrayList<>();
    boolean fillDock = true;
    Set<String> hiddenApps = new HashSet<>();
    Map<String, String> appLabels = new HashMap<>();
    String liveTvPackage = "";
    int columns;                 // 0 = automatic
    Boolean reducedMotion;       // null = not set by the theme
    Boolean showStats;
    boolean showNetworkPill = true;
    boolean glassHighlight = true;
    boolean moduleCards = true;
    boolean greeting = true;
    String greetingName = "";
    int backgroundColor;         // 0 = palette default
    boolean wallpaper = true;
    int wallpaperDim;            // 0..90 percent
    boolean wallpaperTrueColor;
    int idleClockMinutes;        // 0 = off
    List<String> appKeys = new ArrayList<>();
    List<Integer> inputKeyCodes = new ArrayList<>();
    List<Integer> settingsKeyCodes = new ArrayList<>();
    boolean loadedTheme;

    static HomeConfig load(Context context) {
        HomeConfig config = new HomeConfig();
        android.content.res.Resources res = context.getResources();
        Collections.addAll(config.dockApps, res.getStringArray(R.array.dock_apps));
        Collections.addAll(config.hiddenApps, res.getStringArray(R.array.hidden_apps));
        config.liveTvPackage = res.getString(R.string.live_tv_package);
        for (String entry : res.getStringArray(R.array.app_keys)) config.appKeys.add(entry);
        File file = themeFile(context, CONFIG);
        if (file != null) {
            try { config.apply(new JSONObject(read(file))); config.loadedTheme = true; }
            catch (IOException | JSONException | RuntimeException ignored) { /* Keep built-in defaults. */ }
        }
        return config;
    }

    /** Theme files live in the launcher's own storage; the ADB shell may write them two ways. */
    static File themeFile(Context context, String name) {
        File internal = new File(new File(deviceStorage(context), THEME_DIR), name);
        if (internal.isFile()) return internal;
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File pushed = new File(external, name);
            if (pushed.isFile()) return pushed;
        }
        return null;
    }

    static File deviceStorage(Context context) {
        Context app = context.getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= 24) app = app.createDeviceProtectedStorageContext();
        return app.getFilesDir();
    }

    private static String read(File file) throws IOException {
        if (file.length() > MAX_CONFIG_BYTES) throw new IOException("Theme too large");
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    void apply(JSONObject json) {
        List<String> dock = strings(json, "dock_apps");
        if (dock != null) dockApps = dock;
        if (json.opt("fill_dock") instanceof Boolean) fillDock = json.optBoolean("fill_dock");
        List<String> hidden = strings(json, "hidden_apps");
        if (hidden != null) hiddenApps = new HashSet<>(hidden);
        List<String> labels = strings(json, "app_labels");
        if (labels != null) for (String entry : labels) {
            int split = entry.indexOf('=');
            if (split > 0 && split < entry.length() - 1)
                appLabels.put(entry.substring(0, split).trim(), entry.substring(split + 1).trim());
        }
        if (json.opt("live_tv_package") instanceof String) liveTvPackage = json.optString("live_tv_package");
        Object cols = json.opt("columns");
        if (cols instanceof Number) {
            int value = ((Number) cols).intValue();
            if (value >= 2 && value <= 8) columns = value;
        }
        if (json.opt("reduced_motion") instanceof Boolean) reducedMotion = json.optBoolean("reduced_motion");
        if (json.opt("animations") instanceof Boolean && !json.optBoolean("animations")) reducedMotion = true;
        if (json.opt("show_stats") instanceof Boolean) showStats = json.optBoolean("show_stats");
        if (json.opt("show_network_pill") instanceof Boolean) showNetworkPill = json.optBoolean("show_network_pill");
        if (json.opt("glass_highlight") instanceof Boolean) glassHighlight = json.optBoolean("glass_highlight");
        if (json.opt("module_cards") instanceof Boolean) moduleCards = json.optBoolean("module_cards");
        if (json.opt("greeting_enabled") instanceof Boolean) greeting = json.optBoolean("greeting_enabled");
        if (json.opt("greeting_name") instanceof String) greetingName = json.optString("greeting_name").trim();
        if (json.opt("background_color") instanceof String) {
            try { backgroundColor = Color.parseColor(json.optString("background_color")) | 0xFF000000; }
            catch (IllegalArgumentException ignored) { /* Keep the palette colour. */ }
        }
        if (json.opt("wallpaper") instanceof Boolean) wallpaper = json.optBoolean("wallpaper");
        Object dim = json.opt("wallpaper_dim");
        if (dim instanceof Number) wallpaperDim = Math.max(0, Math.min(90, ((Number) dim).intValue()));
        if (json.opt("wallpaper_true_color") instanceof Boolean) wallpaperTrueColor = json.optBoolean("wallpaper_true_color");
        Object idle = json.opt("home_idle_clock_minutes");
        if (idle instanceof Number) idleClockMinutes = Math.max(0, Math.min(1440, ((Number) idle).intValue()));
        List<String> keys = strings(json, "app_keys");
        if (keys != null) appKeys = keys;
        List<Integer> input = integers(json, "input_key_keycodes");
        if (input != null) inputKeyCodes = input;
        List<Integer> settings = integers(json, "settings_key_keycodes");
        if (settings != null) settingsKeyCodes = settings;
    }

    private static List<String> strings(JSONObject json, String key) {
        JSONArray array = json.optJSONArray(key);
        if (array == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof String && !((String) value).trim().isEmpty() && !out.contains(value))
                out.add(((String) value).trim());
        }
        return out;
    }

    private static List<Integer> integers(JSONObject json, String key) {
        JSONArray array = json.optJSONArray(key);
        if (array == null) return null;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof Number && ((Number) value).intValue() > 0) out.add(((Number) value).intValue());
        }
        return out;
    }

    String label(AppRepository.App app) {
        String custom = appLabels.get(app.packageName());
        return custom == null || custom.isEmpty() ? app.label : custom;
    }
}
