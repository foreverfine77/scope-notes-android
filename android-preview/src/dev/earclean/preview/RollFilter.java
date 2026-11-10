package dev.earclean.preview;

final class RollFilter {
    enum Source { BACKGROUND, GRAVITY, HOLD }

    private long previousTime;
    private float roll = Float.NaN;
    private boolean tracking;
    private float previousGravity = Float.NaN;
    private float correction;
    private Source source = Source.HOLD;

    float update(int packed, long timestampMs) {
        if (packed == 0 || packed == -1) {
            tracking = false;
            return Float.NaN;
        }
        int x = signedAxis((packed >>> 20) & 1023);
        int y = signedAxis((packed >>> 10) & 1023);
        int z = signedAxis(packed & 1023);
        double inPlane = Math.hypot(y, z);
        double total = Math.hypot(inPlane, x);
        double minimum = Math.max(tracking ? 48 : 64, total * (tracking ? 0.18 : 0.24));
        if (inPlane < minimum) {
            tracking = false;
            return Float.NaN;
        }
        if (!tracking) roll = Float.NaN;
        tracking = true;

        // This ear camera uses the Y/Z plane for rotation about the lens axis.
        float measured = (float) Math.toDegrees(Math.atan2(y, z));
        if (Float.isNaN(roll)) {
            roll = measured;
        } else {
            float delta = wrap(measured - roll);
            long elapsed = Math.max(1, Math.min(100, timestampMs - previousTime));
            float maxStep = 0.30f * elapsed;
            float timeConstant = Math.abs(delta) > 3 ? 38 : 110;
            float step = delta * elapsed / (timeConstant + elapsed);
            if (Math.abs(delta) > 0.8f) {
                roll = wrap(roll + Math.max(-maxStep, Math.min(maxStep, step)));
            }
        }
        previousTime = timestampMs;
        return roll;
    }

    float correction(float gravity, float backgroundDelta) {
        if (!Float.isNaN(backgroundDelta)) {
            correction = wrap(correction - backgroundDelta);
            source = Source.BACKGROUND;
        } else if (!Float.isNaN(gravity) && !Float.isNaN(previousGravity)) {
            correction = wrap(correction + wrap(gravity - previousGravity));
            source = Source.GRAVITY;
        } else {
            source = Source.HOLD;
        }
        // Keep the gravity baseline current even while visual tracking is active.
        previousGravity = gravity;
        return correction;
    }

    Source source() {
        return source;
    }

    private static int signedAxis(int bits) {
        return bits >= 512 ? bits - 1024 : bits;
    }

    static float wrap(float degrees) {
        return (degrees + 540) % 360 - 180;
    }
}
