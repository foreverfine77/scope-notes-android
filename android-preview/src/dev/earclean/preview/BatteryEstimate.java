package dev.earclean.preview;

/** Display estimate fitted to supplied app readings, not a measured discharge capacity. */
final class BatteryEstimate {
    static int minutes(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (p < 20) return p * 15 / 20;
        int[] levels = {20, 40, 60, 100}, minutes = {15, 27, 39, 65};
        for (int i = 1; i < levels.length; i++) {
            if (p <= levels[i]) return Math.round(minutes[i - 1]
                    + (p - levels[i - 1]) * (minutes[i] - minutes[i - 1]) / (float) (levels[i] - levels[i - 1]));
        }
        return 65;
    }
}
