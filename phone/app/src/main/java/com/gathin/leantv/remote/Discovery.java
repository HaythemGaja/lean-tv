package com.gathin.leantv.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Finds TVs the way CleanYourTV does, because the old scan found the TV and then could not connect to it.
 *
 * <p>The old scan browsed two service types and connected to whatever port it was told: on a TV with Wireless
 * debugging on, that port is the {@code _adb-tls-connect} one, a random high port where the TV speaks TLS 1.3
 * with a pairing code. Plain Adb sent to that port is answered with silence or a closed socket, so the search
 * found the TV and the connect failed every time. This remote speaks plain Adb only, so it connects to one
 * place and one place only: port 5555 on the TV, and only after a probe says the port is open.
 *
 * <p>Five service types are browsed, as in CleanYourTV's NsdDiscovery: the Android TV remote and Cast services
 * only say a TV is there, {@code _adb} says plain debugging is on, and the two {@code _adb-tls-*} services say
 * the TV offers Wireless debugging and nothing else. A host is learned once, from whichever service names it
 * first, and reported after its 5555 probe finishes, so the list the scan ends with already says which TVs
 * connect and which ones only offer TLS. Resolves run one at a time, because NsdManager refuses concurrent
 * resolves on older releases, and only IPv4 addresses count: the phone's own addresses are never a TV.
 *
 * <p>A scan runs for a few seconds and stops by itself. The multicast lock is taken for that window only,
 * because some routers drop the replies without it.
 */
final class Discovery {
    /** A TV the scan saw, with what this remote can do with it. */
    static final class Tv {
        final String name;
        final String host;
        final boolean tlsSeen;
        final boolean port5555Open;

        Tv(String name, String host, boolean tlsSeen, boolean port5555Open) {
            this.name = name == null ? "" : name;
            this.host = host;
            this.tlsSeen = tlsSeen;
            this.port5555Open = port5555Open;
        }

        /** Plain Adb connects to this TV: 5555 is open, so the plain handshake will be answered. */
        boolean connectable() {
            return port5555Open;
        }

        /** Only Wireless debugging (TLS) was seen and 5555 is closed: nothing here can connect to it. */
        boolean tlsOnly() {
            return !port5555Open && tlsSeen;
        }

        /** Whether the scan's picker may offer this TV at all: one that connects, or one whose
         * guidance is the useful thing. A device that only casts is not a remote target. */
        boolean offerable() {
            return connectable() || tlsOnly();
        }

        String label() {
            return name.isEmpty() ? host : name + " (" + host + ")";
        }
    }

    interface Listener {
        /** A TV, first seen or with its 5555 probe back; the same host may be reported more than once. */
        void found(Tv tv);

        /** The scan is over; {@code problem} is null unless discovery could not run at all. */
        void finished(String problem);
    }

    /** Plain Adb's port, and the only port this remote ever connects to. */
    static final int ADB_PORT = 5555;

    private static final long WINDOW_MS = 6000;
    private static final int PROBE_TIMEOUT_MS = 2000;

    /** Service type and what it tells us: a plain Adb service, a TLS one, or only that a TV is there. */
    private static final String[][] SERVICES = {
            {"_androidtvremote2._tcp.", "other"},
            {"_googlecast._tcp.", "other"},
            {"_adb._tcp.", "adb"},
            {"_adb-tls-pairing._tcp.", "tls"},
            {"_adb-tls-connect._tcp.", "tls"},
    };

    private final NsdManager nsd;
    private final WifiManager.MulticastLock multicast;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probes = Executors.newCachedThreadPool();
    private final Set<String> ownAddresses = ownAddresses();
    private final Map<String, MutableTv> byHost = new HashMap<>();
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();
    private final List<NsdManager.DiscoveryListener> browsed = new ArrayList<>();
    private final Runnable stopTask = this::stop;
    private Listener listener;
    private boolean resolving;
    private int resolveGen;
    private int probesInFlight;
    private boolean over;

    /** One service waiting to be resolved, and what kind of service it is. */
    private static final class Pending {
        final NsdServiceInfo info;
        final String kind;

        Pending(NsdServiceInfo info, String kind) {
            this.info = info;
            this.kind = kind;
        }
    }

    /** What the scan knows about one host so far; reported as an immutable {@link Tv} once probed. */
    private static final class MutableTv {
        String name = "";
        final String host;
        boolean adbSeen;
        boolean tlsSeen;
        boolean probed;
        boolean port5555Open;

        MutableTv(String host) {
            this.host = host;
        }

        Tv snapshot() {
            return new Tv(name, host, tlsSeen, port5555Open);
        }
    }

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
        for (String[] service : SERVICES) browse(service[0], service[1]);
        handler.postDelayed(stopTask, WINDOW_MS);
    }

    private void browse(String type, String kind) {
        NsdManager.DiscoveryListener browse = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) { }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (queue) {
                    queue.add(new Pending(info, kind));
                }
                pumpQueue();
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

    /** One resolve at a time: NsdManager refuses concurrent resolves on older releases. */
    private void pumpQueue() {
        if (over || resolving) return;
        Pending next;
        synchronized (queue) {
            next = queue.poll();
        }
        if (next == null) return;
        resolving = true;
        final int gen = ++resolveGen;
        // A resolve that never calls back must not wedge the queue for the rest of the scan:
        // CleanYourTV bounds its resolve the same way, at five seconds.
        handler.postDelayed(() -> {
            if (resolving && resolveGen == gen && !over) {
                resolving = false;
                pumpQueue();
            }
        }, 5000);
        try {
            nsd.resolveService(next.info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo service, int errorCode) {
                    resolving = false;
                    pumpQueue();
                }

                @Override
                @SuppressWarnings("deprecation")   // getHost has no replacement on minSdk 26
                public void onServiceResolved(NsdServiceInfo service) {
                    resolving = false;
                    learn(service, next.kind);
                    pumpQueue();
                }
            });
        } catch (RuntimeException ignored) {
            // A service that vanished between the reply and the lookup is not an error.
            resolving = false;
            pumpQueue();
        }
    }

    /** The entry for a host, made if this is the first service seen from it. */
    private MutableTv knownFor(String host) {
        synchronized (byHost) {
            MutableTv known = byHost.get(host);
            if (known == null) {
                known = new MutableTv(host);
                byHost.put(host, known);
            }
            return known;
        }
    }

    /** What one resolved service says about its host: learn it, probe 5555 once, then report it. */
    private void learn(NsdServiceInfo service, String kind) {
        InetAddress address = service.getHost();
        if (!(address instanceof Inet4Address)) return;   // IPv6 is not a TV this remote can reach
        String host = address.getHostAddress();
        if (host == null || isOwn(host, ownAddresses)) return;
        MutableTv known = knownFor(host);
        synchronized (known) {
            String name = service.getServiceName();
            if (known.name.isEmpty() && name != null && !name.isEmpty()) known.name = name;
            if ("adb".equals(kind)) known.adbSeen = true;
            if ("tls".equals(kind)) known.tlsSeen = true;
            // Report on resolve, as CleanYourTV does: the TV is in the list even if its probe is
            // still running, and the probe's report refreshes the same host in place.
            report(known.snapshot());
            if (known.probed) return;
            known.probed = true;
            probesInFlight++;
        }
        probes.execute(() -> {
            boolean open = probe5555(host);
            synchronized (known) {
                known.port5555Open = open;
            }
            handler.post(() -> {
                probesInFlight--;
                report(known.snapshot());
            });
        });
    }

    /** A literal IPv4 address only: never a name lookup, so a probe cannot leave the LAN by DNS. */
    private static boolean probe5555(String host) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, ADB_PORT), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** The phone's own IPv4 addresses: its own debug bridge may announce itself, as on an emulator. */
    static Set<String> ownAddresses() {
        Set<String> own = new HashSet<>();
        own.add("127.0.0.1");
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress address : Collections.list(iface.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.getHostAddress() != null) {
                        own.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // No interface list: loopback alone is still excluded.
        }
        return own;
    }

    /** Whether a host is the phone itself, by its own addresses or loopback. */
    static boolean isOwn(String host, Set<String> own) {
        return host == null || own.contains(host) || host.startsWith("127.") || "0.0.0.0".equals(host);
    }

    /** Reports always land on the main thread, whichever thread learned or probed the TV. */
    private void report(Tv tv) {
        handler.post(() -> {
            if (listener != null && !over) listener.found(tv);
        });
    }

    void stop() {
        handler.removeCallbacks(stopTask);
        for (NsdManager.DiscoveryListener browse : browsed) {
            try {
                nsd.stopServiceDiscovery(browse);
            } catch (RuntimeException ignored) {
                // Already stopped, or the service crashed: either way there is nothing left to do.
            }
        }
        browsed.clear();
        if (multicast != null && multicast.isHeld()) multicast.release();
        // A TV resolved near the end of the window starts a probe that would finish after the scan
        // ends: give in-flight probes a moment to land, so a late TV is not silently lost. The wait
        // is bounded, and a probe that never comes back still ends the scan.
        if (probesInFlight > 0) handler.postDelayed(() -> finish(null), 2500);
        else finish(null);
    }

    private void finish(String problem) {
        if (over) return;
        over = true;
        Listener target = listener;
        listener = null;
        if (target != null) target.finished(problem);
    }
}
