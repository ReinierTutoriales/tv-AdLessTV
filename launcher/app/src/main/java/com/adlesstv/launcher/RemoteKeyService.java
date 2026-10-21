package com.adlesstv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Optional, off until the user enables it: brings back remote buttons only the stock launcher
 * answered (Input, an app button, the settings gear). It reads no screen content, receives no
 * accessibility events and passes every other key through untouched.
 */
public final class RemoteKeyService extends AccessibilityService {
    static final String ACTION_INPUTS = "com.adlesstv.launcher.action.SHOW_INPUTS";
    private final Set<Integer> inputKeys = new HashSet<>();
    private final Set<Integer> settingsKeys = new HashSet<>();
    private final Map<Integer, String> appKeys = new HashMap<>();
    private final Set<Integer> consumedDown = new HashSet<>();
    private long configStamp = -1;

    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = 0;
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        reload();
    }

    private void reload() {
        File file = HomeConfig.themeFile(this, HomeConfig.CONFIG);
        long stamp = file == null ? 0 : file.lastModified();
        if (stamp == configStamp) return;
        configStamp = stamp;
        HomeConfig config = HomeConfig.load(this);
        inputKeys.clear();
        // KEYCODE_TV usually opens Live TV, so it is only taken over when a theme lists it (170).
        inputKeys.add(KeyEvent.KEYCODE_TV_INPUT);
        inputKeys.addAll(config.inputKeyCodes);
        settingsKeys.clear();
        settingsKeys.addAll(config.settingsKeyCodes);
        appKeys.clear();
        for (String entry : config.appKeys) {
            int split = entry.lastIndexOf(':');
            if (split <= 0 || split == entry.length() - 1) continue;
            int code = KeyCodes.parse(entry.substring(0, split).trim());
            if (code > 0) appKeys.put(code, entry.substring(split + 1).trim());
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) reload();
            if (!inputKeys.contains(code) && !settingsKeys.contains(code) && !appKeys.containsKey(code)) return false;
            if (event.getRepeatCount() == 0 && act(code)) consumedDown.add(code);
            return consumedDown.contains(code);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) return consumedDown.remove(code);
        return false;
    }

    private boolean act(int code) {
        Intent intent;
        if (appKeys.containsKey(code)) {
            String pkg = appKeys.get(code);
            intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (intent == null) intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) return false;
        } else if (settingsKeys.contains(code)) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        } else {
            intent = new Intent(ACTION_INPUTS).setClass(this, MainActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(intent); return true; }
        catch (RuntimeException unavailable) { return false; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* No events requested. */ }
    @Override public void onInterrupt() { consumedDown.clear(); }
}
