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
    private int revision;
    private Network reportedNetwork;

    NetworkStatus(Context context, Handler main, Listener listener) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.listener = listener;
    }

    void start() {
        if (Build.VERSION.SDK_INT < 26 || callback != null) return;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return;
        final int generation = ++revision;
        callback = Build.VERSION.SDK_INT >= 31
                ? new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { report(generation, n, c); }
                    @Override public void onLost(Network n) { lost(generation, n); }
                }
                : new ConnectivityManager.NetworkCallback() {
                    @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { report(generation, n, c); }
                    @Override public void onLost(Network n) { lost(generation, n); }
                };
        try {
            manager.registerDefaultNetworkCallback(callback, main);
        } catch (RuntimeException unavailable) {
            callback = null;
        }
        if (callback != null && manager.getActiveNetwork() == null) post(generation, NONE, "");
    }

    void stop() {
        revision++;
        reportedNetwork = null;
        if (callback == null) return;
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        try { if (manager != null) manager.unregisterNetworkCallback(callback); }
        catch (RuntimeException ignored) { /* Already gone. */ }
        callback = null;
    }

    private void lost(int generation, Network network) {
        if (generation != revision || !network.equals(reportedNetwork)) return;
        reportedNetwork = null;
        post(generation, NONE, "");
    }

    private void report(int generation, Network network, NetworkCapabilities capabilities) {
        if (generation != revision || callback == null) return;
        reportedNetwork = network;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { post(generation, ETHERNET, ""); return; }
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { post(generation, OTHER, ""); return; }
        String ssid = "";
        if (Build.VERSION.SDK_INT >= 31 && capabilities.getTransportInfo() instanceof WifiInfo) {
            ssid = ((WifiInfo) capabilities.getTransportInfo()).getSSID();
        } else {
            ssid = legacySsid();
        }
        post(generation, WIFI, clean(ssid));
    }

    @SuppressWarnings("deprecation")
    private String legacySsid() {
        WifiManager wifi = context.getSystemService(WifiManager.class);
        try {
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            return info == null ? "" : info.getSSID();
        }
        catch (SecurityException denied) { return ""; }
    }

    static String clean(String ssid) {
        if (ssid == null) return "";
        String name = ssid.trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
        return "<unknown ssid>".equalsIgnoreCase(name) || "0x".equals(name) ? "" : name;
    }

    private void post(int generation, int type, String name) {
        main.post(() -> {
            if (callback != null && generation == revision) listener.changed(type, name);
        });
    }
}
