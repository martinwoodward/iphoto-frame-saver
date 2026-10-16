package io.github.martinwoodward.iphotoframesaver.core;

public final class PlaybackPolicy {
    public static final int PREFETCH = 5;
    private PlaybackPolicy() {}

    public static long refreshMillis(String mode) {
        if ("daily".equals(mode)) return 86_400_000L;
        if ("weekly".equals(mode)) return 604_800_000L;
        if ("manual".equals(mode)) return 0;
        return 3_600_000L;
    }

    public static boolean refreshDue(long now, long lastSuccess, String mode) {
        long interval = refreshMillis(mode);
        return interval > 0 && (lastSuccess == 0 || now < lastSuccess || now - lastSuccess >= interval);
    }

    public static int sampleSize(int width, int height, int screenWidth, int screenHeight) {
        int sample = 1;
        long budget = Math.min(4_000_000L, Math.max(1, (long) screenWidth * screenHeight * 2));
        while ((long) Math.max(1, width / sample) * Math.max(1, height / sample) > budget
                && sample < 65536) sample *= 2;
        return sample;
    }

    public static int intervalSeconds(String value) {
        try {
            int seconds = Integer.parseInt(value.trim());
            if (seconds >= 5 && seconds <= 86400) return seconds;
        } catch (NumberFormatException ignored) {
            // The same validation message covers malformed and out-of-range input.
        }
        throw new IllegalArgumentException("Choose an interval from 5 to 86400 seconds.");
    }
}
