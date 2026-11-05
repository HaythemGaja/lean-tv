package com.gathin.leantv.remote;

import java.util.Locale;

/**
 * The three numbers that answer "why is the TV slow": how long it has been up, how much memory is free, and how
 * full the data partition is. Each comes from a command whose output differs by firmware, so every part is parsed
 * on its own and a part that does not look like what it should leaves a question mark instead of a wrong number.
 */
final class Health {
    private Health() { }

    static String describe(String uptime, String meminfo, String disk) {
        return "Up " + duration(uptime) + " · " + memory(meminfo) + " free · " + storage(disk);
    }

    /** Days, hours and minutes, from the seconds since boot in the first number of /proc/uptime. */
    static String duration(String uptime) {
        if (uptime == null || uptime.trim().isEmpty()) return "?";
        String[] parts = uptime.trim().split("\\s+");
        long seconds = 0;
        if (parts.length > 0 && !parts[0].isEmpty()) {
            try {
                seconds = (long) Double.parseDouble(parts[0]);
            } catch (NumberFormatException ignored) {
                return "?";
            }
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    /** MemAvailable from /proc/meminfo, in kB, as whole megabytes. */
    static String memory(String meminfo) {
        Long kilobytes = value(meminfo, "MemAvailable:");
        return kilobytes == null ? "?" : (kilobytes / 1024) + " MB";
    }

    /** The data partition's free and total size, from a df line whose sizes are in 1 kB blocks. */
    static String storage(String disk) {
        if (disk == null) return "?";
        String[] lines = disk.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String[] parts = lines[i].trim().split("\\s+");
            if (parts.length < 4) continue;
            try {
                long total = Long.parseLong(parts[1]);
                long free = Long.parseLong(parts[3]);
                return gigabytes(free) + " free of " + gigabytes(total);
            } catch (NumberFormatException ignored) {
                // Human-readable sizes: some firmware prints 8.0G instead of blocks.
            }
        }
        return "?";
    }

    private static String gigabytes(long kilobytes) {
        return String.format(Locale.US, "%.1f GB", kilobytes / 1024.0 / 1024.0);
    }

    private static Long value(String text, String key) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(key)) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) return null;
            try {
                return Long.parseLong(parts[1]);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
