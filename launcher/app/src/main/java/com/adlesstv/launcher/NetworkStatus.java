package com.adlesstv.launcher;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;

/**
 * Wi-Fi/Ethernet state for the network pill, delivered by system callbacks while Home is visible
 * (registered in onResume, removed in onPause). The network name needs the location permission,
 * which Android requires for SSIDs; without it the pill shows the connection type only.
 */
final class NetworkStatus {
    interface Listener { void changed(int type, String name); }
    static final int NONE = 0, WIFI = 1, ETHERNET = 2, OTHER = 3;

    private final Context context;
    private final Handler main;
    private final Listener listener;
    private ConnectivityManager.NetworkCallback callback;

    NetworkStatus(Context context, Handler main, Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
    }

    void start() {
        if (Build.VERSION.SDK_INT < 26 || callback != null) return;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        callback = Build.VERSION.SDK_INT >= 31
                ? new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { report(c); }
                    @Override public void onLost(Network n) { post(NONE, ""); }
                }
                : new ConnectivityManager.NetworkCallback() {
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { report(c); }
                    @Override public void onLost(Network n) { post(NONE, ""); }
                };
        try {
            manager.registerDefaultNetworkCallback(callback, main);
        } catch (RuntimeException unavailable) {
            callback = null;
        }
        if (callback != null && manager.getActiveNetwork() == null) post(NONE, "");
    }

    void stop() {
        if (callback == null) return;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        try { if (manager != null) manager.unregisterNetworkCallback(callback); }
        catch (RuntimeException ignored) { /* Already gone. */ }
        callback = null;
    }

    private void report(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { post(ETHERNET, ""); return; }
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { post(OTHER, ""); return; }
        String ssid = "";
        if (Build.VERSION.SDK_INT >= 31 && capabilities.getTransportInfo() instanceof WifiInfo) {
            ssid = ((WifiInfo) capabilities.getTransportInfo()).getSSID();
        } else {
            ssid = legacySsid();
        }
        post(WIFI, clean(ssid));
    }

    @SuppressWarnings("deprecation")
    private String legacySsid() {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        try { return wifi == null || wifi.getConnectionInfo() == null ? "" : wifi.getConnectionInfo().getSSID(); }
        catch (SecurityException denied) { return ""; }
    }

    static String clean(String ssid) {
        if (ssid == null) return "";
        String name = ssid.trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
        return "<unknown ssid>".equalsIgnoreCase(name) || "0x".equals(name) ? "" : name;
    }

    private void post(int type, String name) { main.post(() -> listener.changed(type, name)); }
}
