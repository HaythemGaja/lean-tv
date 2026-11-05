package com.gathin.leantv.remote;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The TVs this phone has connected to, newest first: a household with a living-room set and a bedroom set picks
 * one from a list instead of typing an address again.
 *
 * <p>Kept in the app's preferences as one line per TV, {@code host:port|name}, so a value that does not decode
 * loses nothing but the list.
 */
final class Remotes {
    static final class Tv {
        final String name;
        final String host;
        final int port;

        Tv(String name, String host, int port) {
            this.name = name == null ? "" : name.trim();
            this.host = host == null ? "" : host.trim();
            this.port = port;
        }

        String address() {
            return host + ":" + port;
        }

        String label() {
            return name.isEmpty() ? address() : name + " (" + address() + ")";
        }

        String key() {
            return address();
        }
    }

    private static final String KEY = "saved_tvs";
    private static final int MAX = 12;

    private Remotes() { }

    static List<Tv> all(Context ctx) {
        return decode(ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(KEY, ""));
    }

    /** Adds a TV, moving one already saved at the same address to the front with the newer name. */
    static void save(Context ctx, String name, String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) return;
        Tv tv = new Tv(name, host, port);
        List<Tv> kept = new ArrayList<>();
        kept.add(tv);
        for (Tv other : all(ctx)) if (!other.key().equals(tv.key()) && kept.size() < MAX) kept.add(other);
        store(ctx, kept);
    }

    static void forget(Context ctx, Tv tv) {
        List<Tv> kept = new ArrayList<>();
        for (Tv other : all(ctx)) if (!other.key().equals(tv.key())) kept.add(other);
        store(ctx, kept);
    }

    static void forgetAll(Context ctx) {
        store(ctx, new ArrayList<>());
    }

    private static void store(Context ctx, List<Tv> tvs) {
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, encode(tvs)).apply();
    }

    static String encode(List<Tv> tvs) {
        StringBuilder out = new StringBuilder();
        for (Tv tv : tvs) out.append(tv.host).append(':').append(tv.port).append('|').append(tv.name).append('\n');
        return out.toString();
    }

    static List<Tv> decode(String text) {
        List<Tv> tvs = new ArrayList<>();
        if (text == null) return tvs;
        for (String line : text.split("\n")) {
            String entry = line.trim();
            if (entry.isEmpty()) continue;
            int bar = entry.indexOf('|');
            String address = bar < 0 ? entry : entry.substring(0, bar);
            String name = bar < 0 ? "" : entry.substring(bar + 1);
            int colon = address.lastIndexOf(':');
            if (colon <= 0) continue;
            try {
                tvs.add(new Tv(name, address.substring(0, colon), Integer.parseInt(address.substring(colon + 1))));
            } catch (NumberFormatException ignored) {
                // A line that is not a TV is not worth keeping.
            }
        }
        return tvs;
    }
}
