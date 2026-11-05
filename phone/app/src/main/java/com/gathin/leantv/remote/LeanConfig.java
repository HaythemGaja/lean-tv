package com.gathin.leantv.remote;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lean TV's own settings, changed from the phone.
 *
 * <p>The launcher reads a {@code config.json} from its external files directory and lays it over the values built
 * into the app, so that file is the one place a phone can change on the TV without the launcher taking an order
 * from the network - it has no {@code INTERNET} permission and no listening socket, by rule.
 *
 * <p>The file is written through the launcher's own provider, which is how the adb shell (and so this app) reaches
 * {@code Android/data} on Android 11 and newer; firmware without it, Android 8 and 9, takes a plain copy. Either
 * way the whole file is written, not just the keys this screen changes: an existing theme keeps its colours
 * because its keys are read first and written back untouched.
 *
 * <p>Home reads the file once, so it is restarted afterwards. The entries here are the ones a phone screen can
 * carry in a row; everything else, the wallpaper and the tiles among them, is on the launcher's own settings
 * screen on the TV, which this app can open.
 */
final class LeanConfig {
    static final String PACKAGE = "com.gathin.tvhome";
    static final String HOME = PACKAGE + "/.MainActivity";
    static final String SETTINGS_SCREEN = PACKAGE + "/.LauncherSettingsActivity";
    private static final String URI = "content://" + PACKAGE + ".theme/config.json";
    private static final String PATH = "/sdcard/Android/data/" + PACKAGE + "/files/config.json";
    private static final String TEMP = "/data/local/tmp/leantv-config.json";

    /** One row of the customise screen: what it is called, the key it writes, and the values it can take. */
    static final class Setting {
        final int label;
        final String key;
        final String[] values;

        Setting(int label, String key, String... values) {
            this.label = label;
            this.key = key;
            this.values = values;
        }
    }

    static final Setting[] SETTINGS = {
            new Setting(R.string.set_columns, "columns", "4", "5", "6", "7"),
            new Setting(R.string.set_rows, "rows_below_dock", "0", "1", "2"),
            new Setting(R.string.set_dock_opacity, "dock_opacity", "100", "70", "50", "30"),
            new Setting(R.string.set_folders, "folders", "true", "false"),
            new Setting(R.string.set_pin_lock, "pin_lock", "true", "false"),
            new Setting(R.string.set_greeting, "show_greeting", "true", "false"),
    };

    private LeanConfig() { }

    /**
     * The config.json the TV already has, or an empty one when it has none: a theme pushed from a computer keeps
     * everything this screen does not name.
     */
    static JSONObject overlay(AdbClient tv) throws IOException {
        String text = tv.shell("content read --uri " + URI + " 2>/dev/null");
        if (unreadable(text)) text = tv.shell("cat " + PATH + " 2>/dev/null");
        if (unreadable(text)) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static boolean unreadable(String text) {
        return text == null || !text.trim().startsWith("{");
    }

    /** What the file names now, key to its value, for the screen to start from. Keys it does not name are absent. */
    static Map<String, String> current(AdbClient tv) throws IOException {
        JSONObject json = overlay(tv);
        Map<String, String> values = new LinkedHashMap<>();
        for (Setting setting : SETTINGS) {
            if (!json.has(setting.key)) continue;
            values.put(setting.key, String.valueOf(json.opt(setting.key)));
        }
        return values;
    }

    /**
     * Writes the chosen keys over the overlay, restarts Home and says what the TV now has. A null value removes the
     * key, which is what "TV default" means: the value built into the launcher applies again.
     */
    static String apply(AdbClient tv, Map<String, String> changes) throws IOException {
        JSONObject json = overlay(tv);
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (change.getValue() == null) {
                json.remove(change.getKey());
                continue;
            }
            try {
                json.put(change.getKey(), value(change.getValue()));
            } catch (JSONException ignored) {
                // A key the file cannot hold is a key this screen did not mean to write.
            }
        }
        write(tv, json.toString());
        restartHome(tv);
        return json.toString();
    }

    /** true and false as booleans, numbers as numbers: a quoted number reads as a missing key to the launcher. */
    private static Object value(String literal) {
        if ("true".equals(literal) || "false".equals(literal)) return Boolean.valueOf(literal);
        try {
            return Integer.valueOf(literal);
        } catch (NumberFormatException e) {
            return literal;
        }
    }

    /** The whole file, through the launcher's provider first and as a plain copy on firmware without it. */
    private static void write(AdbClient tv, String json) throws IOException {
        String quoted = json.replace("'", "'\\''");
        String answer = tv.shell("printf '%s' '" + quoted + "' > " + TEMP
                + "; content write --uri " + URI + " < " + TEMP + " 2>&1");
        boolean written = clean(answer);
        if (!written) written = clean(tv.shell("cp " + TEMP + " " + PATH + " 2>&1"));
        tv.shell("rm -f " + TEMP);
        if (!written) throw new IOException("The TV would not take the settings");
    }

    /** A command that printed nothing it minds: the provider answers with silence when it took the file. */
    private static boolean clean(String answer) {
        if (answer == null) return true;
        String lower = answer.toLowerCase(Locale.US);
        return !lower.contains("error") && !lower.contains("exception")
                && !lower.contains("unsupported") && !lower.contains("denied")
                && !lower.contains("no such file");
    }

    /** Home reads config.json once, at start: a restart is what makes a written setting show. */
    static void restartHome(AdbClient tv) throws IOException {
        tv.shell("am force-stop " + PACKAGE + "; am start -n " + HOME);
    }

    /** Lean TV's own settings screen on the TV, driven with this phone's D-pad: everything a row cannot carry. */
    static void openSettings(AdbClient tv) throws IOException {
        tv.shell("am start -n " + SETTINGS_SCREEN);
    }
}
