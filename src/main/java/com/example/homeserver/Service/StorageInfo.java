package com.example.homeserver.Service;

import java.util.Locale;

public record StorageInfo(long totalBytes, long usedBytes, long freeBytes,
        double usagePercent, boolean warning, boolean available) {

    public String totalDisplay() { return formatBytes(totalBytes); }
    public String usedDisplay() { return formatBytes(usedBytes); }
    public String freeDisplay() { return formatBytes(freeBytes); }

    static String formatBytes(long bytes) {
        if (bytes < 0) return "—";
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    public static StorageInfo unavailable() { return new StorageInfo(0, 0, 0, 0, false, false); }
}
