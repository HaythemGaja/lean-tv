package com.gathin.leantv.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the phone is allowed to switch off on the TV, and what it writes down before it does.
 *
 * <p>Nothing is uninstalled: an app is switched off for the TV's main user with {@code pm disable-user --user 0}
 * and comes back with {@code pm enable}, so the restore point is a list of package names and nothing more.
 *
 * <p>The packages that are never offered are the ones the launcher's own safety rules name (Android's core, the
 * permission controller, the package installer, the settings, the system UI, the Play Store), plus the parts a
 * package name only hints at: the home screen, the setup wizard, the live TV and input services and the on-screen
 * keyboard. A package this cannot read is protected as well, so a name it does not understand is never switched
 * off.
 */
final class Clean {
    /** Parts of a package name that are never offered: without them the TV stops working, or the remote does. */
    private static final String[] NEEDED = {
            "launcher", "setup", "tvinput", "livetv", "tuner", "systemui", "inputmethod", "keyboard",
    };

    /** Packages that are never offered whatever their name says. */
    private static final String[] KEEP = {
            "android", "android.ext.services", "android.ext.shared",
            "com.gathin.tvhome", "com.gathin.leantv.remote", "com.android.vending",
    };

    /** How many names the confirmation spells out before it counts the rest. */
    private static final int NAMED = 8;

    private Clean() { }

    /** The package names in a {@code pm list packages} answer, sorted, with every other line dropped. */
    static List<String> packages(String listing) {
        List<String> names = new ArrayList<>();
        if (listing == null) return names;
        for (String line : listing.split("\n")) {
            String text = line.trim();
            if (!text.startsWith("package:")) continue;
            String pkg = text.substring("package:".length()).trim();
            if (pkg.matches("[A-Za-z0-9_.]+")) names.add(pkg);
        }
        Collections.sort(names);
        return names;
    }

    /** Android's own packages, the parts the TV needs, and Lean TV itself are never offered for switching off. */
    static boolean protectedPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String lower = pkg.toLowerCase(Locale.US);
        for (String keep : KEEP) if (lower.equals(keep)) return true;
        if (lower.startsWith("com.android.") || lower.startsWith("com.google.android.")) return true;
        for (String part : NEEDED) if (lower.contains(part)) return true;
        return false;
    }

    /** The apps that can be offered: installed, not already switched off, and not protected. */
    static List<String> offered(List<String> installed, List<String> switchedOff) {
        List<String> offered = new ArrayList<>();
        if (installed == null) return offered;
        for (String pkg : installed) {
            if (switchedOff != null && switchedOff.contains(pkg)) continue;
            if (protectedPackage(pkg)) continue;
            offered.add(pkg);
        }
        return offered;
    }

    /** The plan in one line, so the confirmation names what it will do instead of a number. */
    static String plan(List<String> packages) {
        if (packages == null || packages.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        int shown = Math.min(packages.size(), NAMED);
        for (int i = 0; i < shown; i++) {
            if (i > 0) text.append(", ");
            text.append(packages.get(i));
        }
        if (packages.size() > shown) text.append(", and ").append(packages.size() - shown).append(" more");
        return text.toString();
    }

    /** The restore point: one package name per line, so a value that does not decode loses nothing but the list. */
    static String encode(List<String> packages) {
        StringBuilder text = new StringBuilder();
        if (packages == null) return "";
        for (String pkg : packages) {
            if (pkg != null && pkg.matches("[A-Za-z0-9_.]+")) text.append(pkg).append('\n');
        }
        return text.toString();
    }

    static List<String> decode(String text) {
        List<String> packages = new ArrayList<>();
        if (text == null) return packages;
        for (String line : text.split("\n")) {
            String pkg = line.trim();
            if (pkg.isEmpty() || !pkg.matches("[A-Za-z0-9_.]+") || packages.contains(pkg)) continue;
            packages.add(pkg);
        }
        return packages;
    }
}
