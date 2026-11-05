package com.gathin.leantv.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds TVs that announce an Adb service on the home network, so nobody has to read an IP address off the TV and
 * type it into a phone.
 *
 * <p>Two service types are browsed: {@code _adb._tcp} for a TV with network debugging on port 5555, and
 * {@code _adb-tls-connect._tcp} for Android 11's Wireless debugging, which picks a port of its own. Wireless
 * debugging still needs its pairing code entered once on the TV; this only finds the set.
 *
 * <p>A scan runs for a few seconds and stops by itself. The multicast lock is taken for that window only, because
 * some routers drop the replies without it.
 */
final class Discovery {
    interface Listener {
        void found(String name, String host, int port);

        /** The scan is over; {@code problem} is null unless discovery could not run at all. */
        void finished(String problem);
    }

    private static final String[] SERVICES = {"_adb._tcp.", "_adb-tls-connect._tcp."};
    private static final long WINDOW_MS = 6000;

    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seen = new HashSet<>();
    private final List<NsdManager.DiscoveryListener> browsed = new ArrayList<>();
    private Listener listener;
    private boolean over;

    private Discovery(Context ctx) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.multicast = wifi == null ? null : wifi.createMulticastLock("leantv-discovery");
    }

    static Discovery start(Context ctx, Listener listener) {
        Discovery discovery = new Discovery(ctx);
        discovery.begin(listener);
        return discovery;
    }

    private void begin(Listener listener) {
        this.listener = listener;
        if (nsd == null) {
            finish("This phone cannot look for services on the network");
            return;
        }
        if (multicast != null) multicast.acquire();
        for (String service : SERVICES) browse(service);
        handler.postDelayed(this::stop, WINDOW_MS);
    }

    @SuppressWarnings("deprecation")   // resolveService has no replacement on minSdk 26
    private void browse(String type) {
        NsdManager.DiscoveryListener browse = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) { }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                resolve(info);
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) { }

            @Override
            public void onDiscoveryStopped(String serviceType) { }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                finish("Discovery failed (code " + errorCode + ")");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) { }
        };
        browsed.add(browse);
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, browse);
        } catch (RuntimeException e) {
            finish("Discovery failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")   // getHost and resolveService, as above
    private void resolve(NsdServiceInfo info) {
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo service, int errorCode) { }

                @Override
                public void onServiceResolved(NsdServiceInfo service) {
                    if (service.getHost() == null) return;
                    String host = service.getHost().getHostAddress();
                    int port = service.getPort();
                    if (port <= 0) return;
                    synchronized (seen) {
                        if (!seen.add(host + ":" + port)) return;
                    }
                    String name = service.getServiceName() == null ? "" : service.getServiceName();
                    handler.post(() -> {
                        if (listener != null && !over) listener.found(name, host, port);
                    });
                }
            });
        } catch (RuntimeException ignored) {
            // A service that vanished between the reply and the lookup is not an error.
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        for (NsdManager.DiscoveryListener browse : browsed) {
            try {
                nsd.stopServiceDiscovery(browse);
            } catch (RuntimeException ignored) {
                // Already stopped, or the service crashed: either way there is nothing left to do.
            }
        }
        browsed.clear();
        if (multicast != null && multicast.isHeld()) multicast.release();
        finish(null);
    }

    private void finish(String problem) {
        if (over) return;
        over = true;
        Listener target = listener;
        listener = null;
        if (target != null) target.finished(problem);
    }
}
