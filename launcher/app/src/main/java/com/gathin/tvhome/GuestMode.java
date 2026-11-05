package com.gathin.tvhome;

import android.content.Context;

/**
 * Guest mode: one switch that takes every tile the arrow pattern guards off the screen at once — locked apps and
 * locked folders, on Home and inside folders — until it is turned off again.
 *
 * <p>It unlocks nothing and hides nothing that is not locked, so it is the quick way to hand the remote to a
 * guest without opening each app's lock in turn. The pattern turns it on and off; with no pattern set there is
 * nothing for it to hide, so there is nothing to enter. The flag is one preference, kept while the TV runs, and
 * it does not change what the locks are, so turning it off puts exactly the same tiles back.
 */
final class GuestMode {
    private static final String KEY = "guest_mode";

    private GuestMode() { }

    static boolean on(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, false);
    }

    static void set(Context ctx, boolean on) {
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, on).apply();
    }
}
