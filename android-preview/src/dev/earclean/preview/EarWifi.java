package dev.earclean.preview;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class EarWifi {
    static final class Device {
        final String ssid;
        final String bssid;

        Device(String ssid, String bssid) {
            this.ssid = ssid;
            this.bssid = bssid;
        }

        String key() {
            return bssid + "\n" + ssid;
        }

        static Device read(String key) {
            int separator = key.indexOf('\n');
            if (separator < 0) return null;
            return new Device(key.substring(separator + 1), key.substring(0, separator));
        }
    }

    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final SharedPreferences preferences;
    private final Handler ui;
    private final Runnable unavailable;
    private final Runnable retry = this::request;
    private Device selected;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network network;
    private boolean confirmed;

    EarWifi(Context context, Handler ui, Runnable unavailable) {
        wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        connectivity = context.getSystemService(ConnectivityManager.class);
        preferences = context.getSharedPreferences("ear-wifi", Context.MODE_PRIVATE);
        this.ui = ui;
        this.unavailable = unavailable;
    }

    static String[] permissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES};
        }
        return new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION};
    }

    Device last() {
        return Device.read(preferences.getString("last", ""));
    }

    List<Device> devices() {
        Map<String, Device> devices = new LinkedHashMap<>();
        Device last = last();
        if (last != null) devices.put(last.bssid, last);
        for (String key : preferences.getStringSet("known", Collections.emptySet())) {
            Device device = Device.read(key);
            if (device != null) devices.putIfAbsent(device.bssid, device);
        }
        for (ScanResult result : wifi.getScanResults()) {
            if (result.SSID != null && result.BSSID != null
                    && result.SSID.toLowerCase(Locale.ROOT).startsWith("earclean-")) {
                devices.put(result.BSSID, new Device(result.SSID, result.BSSID));
            }
        }
        return new ArrayList<>(devices.values());
    }

    void scan() {
        wifi.startScan();
    }

    void start(Device device) {
        stop();
        selected = device;
        confirmed = preferences.getStringSet("known", Collections.emptySet())
                .contains(device.key());
        request();
    }

    void confirm(Network source) {
        if (selected == null || !source.equals(network)) return;
        confirmed = true;
        String key = selected.key();
        if (key.equals(preferences.getString("last", ""))) return;
        Set<String> known = new HashSet<>(
                preferences.getStringSet("known", Collections.emptySet()));
        known.add(key);
        preferences.edit().putStringSet("known", known).putString("last", key).apply();
    }

    Network network() {
        return network;
    }

    void stop() {
        ui.removeCallbacks(retry);
        release();
        selected = null;
        confirmed = false;
    }

    private void release() {
        network = null;
        if (callback == null) return;
        ConnectivityManager.NetworkCallback previous = callback;
        callback = null;
        connectivity.unregisterNetworkCallback(previous);
    }

    private void request() {
        if (selected == null || Build.VERSION.SDK_INT < 29) return;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(new WifiNetworkSpecifier.Builder()
                        .setSsid(selected.ssid).setBssid(MacAddress.fromString(selected.bssid)).build())
                .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network available) {
                if (callback == this) network = available;
            }

            @Override
            public void onLost(Network lost) {
                if (callback != this || !lost.equals(network)) return;
                release();
                ui.postDelayed(retry, 1000);
            }

            @Override
            public void onUnavailable() {
                if (callback != this) return;
                // Android already released the timed-out request.
                callback = null;
                network = null;
                unavailable.run();
                if (confirmed) ui.postDelayed(retry, 5000);
            }
        };
        try {
            connectivity.requestNetwork(request, callback, ui, 20000);
        } catch (SecurityException | IllegalArgumentException error) {
            callback = null;
            unavailable.run();
        }
    }
}
