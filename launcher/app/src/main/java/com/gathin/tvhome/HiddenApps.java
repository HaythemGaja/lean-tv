package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Apps hidden on this TV from the tile menu, without uninstalling anything: they leave Home, the dock, their
 * folder and the App Library, and Launcher settings brings one back again, behind the arrow pattern when one is
 * set.
 *
 * <p>Separate from config.xml's {@code hidden_apps}, which is a theme or build decision for a whole TV: this is
 * the owner's own choice, made on the remote and kept in the launcher's preferences. The package stays
 * installed, so hiding can never lose an app, and the list survives a restart without a file of its own.
 */
final class HiddenApps {
    private static final String KEY = "apps_hidden_here";

    private HiddenApps() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** The packages hidden here, as a copy: one read for a whole grid reload. */
    static Set<String> all(Context ctx) {
        Set<String> saved = prefs(ctx).getStringSet(KEY, null);
        return saved == null ? new HashSet<>() : new HashSet<>(saved);
    }

    static boolean isHidden(Context ctx, String pkg) {
        return pkg != null && all(ctx).contains(pkg);
    }

    static void set(Context ctx, String pkg, boolean hidden) {
        if (pkg == null) return;
        Set<String> saved = all(ctx);
        boolean changed = hidden ? saved.add(pkg) : saved.remove(pkg);
        if (changed) prefs(ctx).edit().putStringSet(KEY, saved).apply();
    }

    static int count(Context ctx) {
        return all(ctx).size();
    }
}
