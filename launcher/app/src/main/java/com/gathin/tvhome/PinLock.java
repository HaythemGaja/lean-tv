package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * The invisible arrow pattern that keeps an app, a folder or the settings off until it is drawn with the D-pad,
 * off by default (config.xml {@code pin_lock}, or a theme's {@code "pin_lock": true}).
 *
 * <p>A pattern is 4 to 8 presses of Up, Down, Left or Right, held as a string of {@code U}, {@code D}, {@code L}
 * and {@code R}: the four steps a remote can draw, and nothing to type. Nothing on screen shows the steps as
 * they are pressed, so the pattern is entered blind and its length is not on display.
 *
 * <p>This is a household lock, not a security boundary: it stops a child or a guest opening a tile, and it is
 * useless against anyone with ADB on the TV, who can clear the launcher's data. A folder's own lock lives in
 * {@link Folders}, so there is one place that says whether a folder is locked. The pattern itself is never
 * stored: a random salt and SHA-256 of salt and pattern are, and {@link #verify} compares them in constant
 * time. A wrong pattern only says so and starts again; there is no wait between tries.
 *
 * <p>Two conveniences keep the pattern from nagging the person who knows it, and both are off by default.
 * A grace period ({@link #graceMinutes}) skips the pattern for a few minutes after a successful entry, so
 * opening one locked thing after another asks only once. A PIN-free window (start and end hour, on picked
 * days of the week) skips the pattern entirely while it is on, for school hours or the night, say. Both
 * are read through {@link #needsPin}: every place that asks for the pattern asks that first, and every
 * successful entry calls {@link #recordUnlock} so the grace window starts from the latest entry.
 */
final class PinLock {
    /** Up, Down, Left, Right: the only steps a D-pad draws, and the only characters a pattern holds. */
    static final String STEPS = "UDLR";
    static final int MIN_STEPS = 4;
    static final int MAX_STEPS = 8;

    /**
     * What the stored secret is. A build that asked for digits wrote no marker, so its value reads as no pattern
     * at all rather than as one nothing can match: a lock nobody can open is worse than no lock.
     */
    private static final String KIND = "arrows";
    private static final String KEY_KIND = "pin_kind";
    static final String KEY_HASH = "pin_hash";
    static final String KEY_SALT = "pin_salt";
    static final String KEY_STEPS = "pin_steps";
    static final String KEY_APPS = "pin_locked_apps";
    static final String KEY_SETTINGS = "pin_lock_settings";
    /** The app a locked tile was opening, kept so the launch survives Home being rebuilt behind the pattern screen. */
    static final String KEY_PENDING = "pin_pending_pkg";

    private PinLock() { }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /** 4 to 8 arrow presses, in step characters and nothing else. */
    static boolean validPattern(String pattern) {
        if (pattern == null || pattern.length() < MIN_STEPS || pattern.length() > MAX_STEPS) return false;
        for (int i = 0; i < pattern.length(); i++) {
            if (STEPS.indexOf(pattern.charAt(i)) < 0) return false;
        }
        return true;
    }

    /**
     * Whether the entry is finished on its own: unlocking is, and only once it holds as many steps as the stored
     * pattern, which is what makes a blind entry workable without reaching for OK. Choosing a new pattern keeps
     * OK, because a new pattern can be any length and only the person knows when it ends.
     */
    static boolean submitsAt(boolean choosing, int entrySteps, int patternSteps) {
        return !choosing && entrySteps > 0 && entrySteps == patternSteps;
    }

    /**
     * Whether an explicit OK press should check the entry as it stands: any 4 to 8 step pattern, not only one
     * exactly as long as the stored length. The stored length is the only record of how long the pattern is,
     * and if it is stale (a pattern set before it was recorded, or recorded wrong) an OK press that only
     * accepted that length would leave the right pattern uncheckable: the auto-submit at the stale length
     * would fail first, and OK would just say "keep going". Checking the full entry on OK is the way out.
     */
    static boolean checksOnOk(int entrySteps) {
        return entrySteps >= MIN_STEPS && entrySteps <= MAX_STEPS;
    }

    /**
     * Repairs a stale stored length after a pattern has just verified: the entry that passed is the real
     * pattern, so its length is the right one to auto-submit at next time. Only the length is rewritten;
     * the salt and hash that just verified stay exactly as they are.
     */
    static void repairSteps(Context ctx, String pattern) {
        if (!validPattern(pattern)) return;
        if (steps(ctx) == pattern.length()) return;
        prefs(ctx).edit().putInt(KEY_STEPS, pattern.length()).apply();
    }

    static String newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return hex(salt);
    }

    /** Lower-case hex SHA-256 of salt and pattern, or "" when the platform has no SHA-256 (it always has). */
    static String hash(String pattern, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update((pattern == null ? "" : pattern).getBytes(StandardCharsets.UTF_8));
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** Whether the drawn pattern hashes to the stored one, compared without an early exit. */
    static boolean verify(String drawn, String salt, String hash) {
        if (drawn == null || salt == null || hash == null || hash.isEmpty()) return false;
        return MessageDigest.isEqual(hash(drawn, salt).getBytes(StandardCharsets.UTF_8),
                hash.getBytes(StandardCharsets.UTF_8));
    }

    /** Stores a new pattern. What it already locks stays locked, so changing one keeps the setup. */
    static boolean setPattern(Context ctx, String pattern) {
        if (!validPattern(pattern)) return false;
        String salt = newSalt();
        return prefs(ctx).edit()
                .putString(KEY_KIND, KIND)
                .putString(KEY_SALT, salt)
                .putString(KEY_HASH, hash(pattern, salt))
                .putInt(KEY_STEPS, pattern.length())
                .commit();
    }

    static boolean hasPin(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String hash = prefs.getString(KEY_HASH, "");
        return KIND.equals(prefs.getString(KEY_KIND, "")) && hash != null && !hash.isEmpty();
    }

    /** The length the pattern screen submits by itself at, so a blind entry needs no count; 4 when none is set. */
    static int steps(Context ctx) {
        int steps = prefs(ctx).getInt(KEY_STEPS, MIN_STEPS);
        return Math.max(MIN_STEPS, Math.min(MAX_STEPS, steps));
    }

    // ---- grace period and PIN-free hours ---------------------------------------------------------
    //
    // Both are off by default, so a pattern that used to ask every time keeps asking every time until the
    // person who knows it turns one on in Launcher settings. They are preferences, not secrets: clearing
    // the pattern keeps them, so a new pattern inherits the same conveniences.

    /** Minutes after a successful entry during which the pattern is not asked again; 0 is off. */
    static final String KEY_GRACE_MINUTES = "pin_grace_minutes";
    /** When the pattern was last entered right, in wall-clock milliseconds; 0 is never. */
    static final String KEY_LAST_UNLOCK = "pin_last_unlock_ms";
    /** First hour of the PIN-free window, 0 to 23, or -1 when there is no window. */
    static final String KEY_FREE_START = "pin_free_start";
    /** Last hour of the PIN-free window, 0 to 23 (the window ends at the start of this hour). */
    static final String KEY_FREE_END = "pin_free_end";
    /** Days the PIN-free window runs on, one bit per Calendar day (bit 0 is Sunday); 0 is no days. */
    static final String KEY_FREE_DAYS = "pin_free_days";

    /** The grace choices the settings row cycles through: off, then 5, 10, 15 and 30 minutes, then an hour. */
    static final int[] GRACE_OPTIONS = {0, 5, 10, 15, 30, 60};

    static int graceMinutes(Context ctx) {
        return prefs(ctx).getInt(KEY_GRACE_MINUTES, 0);
    }

    static void setGraceMinutes(Context ctx, int minutes) {
        prefs(ctx).edit().putInt(KEY_GRACE_MINUTES, minutes).apply();
    }

    /** A pattern was just entered right: the grace window runs from now, not from the first entry of the day. */
    static void recordUnlock(Context ctx) {
        prefs(ctx).edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply();
    }

    static int freeStart(Context ctx) {
        return prefs(ctx).getInt(KEY_FREE_START, -1);
    }

    static int freeEnd(Context ctx) {
        return prefs(ctx).getInt(KEY_FREE_END, -1);
    }

    static int freeDays(Context ctx) {
        return prefs(ctx).getInt(KEY_FREE_DAYS, 0);
    }

    /** Saves one whole PIN-free window at once: the days it runs on and the hours it covers. */
    static void setFreeSchedule(Context ctx, int start, int end, int daysMask) {
        prefs(ctx).edit().putInt(KEY_FREE_START, start).putInt(KEY_FREE_END, end)
                .putInt(KEY_FREE_DAYS, daysMask).apply();
    }

    /** Turns the PIN-free window off, leaving the grace period alone. */
    static void clearFreeSchedule(Context ctx) {
        prefs(ctx).edit().putInt(KEY_FREE_START, -1).putInt(KEY_FREE_END, -1)
                .putInt(KEY_FREE_DAYS, 0).apply();
    }

    /** Whether {@code nowMs} is still inside the grace window a successful entry at {@code lastUnlockMs} opened. */
    static boolean withinGrace(long lastUnlockMs, int graceMinutes, long nowMs) {
        if (graceMinutes <= 0 || lastUnlockMs <= 0) return false;
        return nowMs - lastUnlockMs < graceMinutes * 60_000L;
    }

    /**
     * Whether {@code hour} falls in the window from {@code start} to {@code end}: a start before the end is
     * one stretch of the day, a start after the end runs overnight (22 to 7, say), and start and end the
     * same means the whole day. The window ends at the start of the end hour, so 8 to 15 is free until 15:00.
     */
    static boolean hourInWindow(int start, int end, int hour) {
        if (start == end) return true;
        if (start < end) return hour >= start && hour < end;
        return hour >= start || hour < end;
    }

    /**
     * Whether the PIN-free window is on for this {@link java.util.Calendar} day and hour. The day that
     * matters is the day the window started on, so the small hours of an overnight window belong to the
     * night before: with Mon–Fri 22:00–07:00, Saturday 02:00 is free (Friday night's tail) and Monday
     * 02:00 is not (Sunday night was never picked). The evening hours belong to their own day.
     */
    static boolean inFreeWindow(int start, int end, int daysMask, int dayOfWeek, int hour) {
        if (start < 0 || end < 0 || daysMask == 0) return false;
        if (start > end && hour < end) {
            int prevDay = dayOfWeek == java.util.Calendar.SUNDAY
                    ? java.util.Calendar.SATURDAY : dayOfWeek - 1;
            return (daysMask & (1 << (prevDay - 1))) != 0;
        }
        if ((daysMask & (1 << (dayOfWeek - 1))) == 0) return false;
        return hourInWindow(start, end, hour);
    }

    /**
     * Whether the pattern needs asking right now: no pattern means never, a grace window or a PIN-free
     * window means not now. Every place that asks for the pattern asks this first, so the two conveniences
     * cover apps, folders, the settings and guest mode alike. The device clock decides the window, which
     * is fine for a household lock: it is the same clock the TV's own schedule runs on.
     */
    static boolean needsPin(Context ctx) {
        if (!hasPin(ctx)) return false;
        long now = System.currentTimeMillis();
        if (withinGrace(prefs(ctx).getLong(KEY_LAST_UNLOCK, 0), graceMinutes(ctx), now)) return false;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (inFreeWindow(freeStart(ctx), freeEnd(ctx), freeDays(ctx),
                cal.get(java.util.Calendar.DAY_OF_WEEK), cal.get(java.util.Calendar.HOUR_OF_DAY))) return false;
        return true;
    }

    /** Forgets the pattern and everything it locked. The grace and PIN-free choices stay, for the next pattern. */
    static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_KIND).remove(KEY_HASH).remove(KEY_SALT).remove(KEY_STEPS)
                .remove(KEY_APPS).remove(KEY_SETTINGS).remove(KEY_PENDING).remove(KEY_LAST_UNLOCK).apply();
    }

    // ---- what is locked --------------------------------------------------------------------------

    /** No pattern means nothing is locked, whatever an old choice says. */
    private static Set<String> locks(Context ctx, String key) {
        Set<String> saved = prefs(ctx).getStringSet(key, null);
        return saved == null ? new HashSet<>() : new HashSet<>(saved);
    }

    private static void storeLocks(Context ctx, String key, Set<String> locks) {
        prefs(ctx).edit().putStringSet(key, locks).apply();
    }

    static boolean locked(Context ctx, String pkg) {
        return pkg != null && hasPin(ctx) && locks(ctx, KEY_APPS).contains(pkg);
    }

    static void setLocked(Context ctx, String pkg, boolean locked) {
        if (pkg == null) return;
        Set<String> locks = locks(ctx, KEY_APPS);
        boolean changed = locked ? locks.add(pkg) : locks.remove(pkg);
        if (changed) storeLocks(ctx, KEY_APPS, locks);
    }

    /** Packages the pattern guards, as a copy; empty when none is set. One read, for the whole tile grid. */
    static Set<String> lockedApps(Context ctx) {
        return hasPin(ctx) ? locks(ctx, KEY_APPS) : new HashSet<>();
    }

    static boolean settingsLocked(Context ctx) {
        return hasPin(ctx) && prefs(ctx).getBoolean(KEY_SETTINGS, false);
    }

    static void setSettingsLocked(Context ctx, boolean locked) {
        prefs(ctx).edit().putBoolean(KEY_SETTINGS, locked && hasPin(ctx)).apply();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
