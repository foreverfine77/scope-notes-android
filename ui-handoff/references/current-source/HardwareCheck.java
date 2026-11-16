package dev.earclean.preview;

/** Measures received hardware data; visual inspection remains a separate user confirmation. */
final class HardwareCheck {
    int frames, batteryReplies;
    long firstFrame = -1, lastFrame = -1, longestGap;
    int width, height;
    float angleTravel, previousAngle = Float.NaN;
    void frame(long now, int width, int height, float angle) {
        if (firstFrame < 0) firstFrame = now;
        if (lastFrame >= 0) longestGap = Math.max(longestGap, now - lastFrame);
        lastFrame = now; frames++; this.width = width; this.height = height;
        if (!Float.isNaN(angle) && !Float.isInfinite(angle)) {
            if (!Float.isNaN(previousAngle)) {
                float delta = (angle - previousAngle + 540) % 360 - 180;
                angleTravel += Math.abs(delta);
            }
            previousAngle = angle;
        }
    }
    float fps() { return frames < 2 || lastFrame <= firstFrame ? 0 : (frames - 1) * 1000f / (lastFrame - firstFrame); }
    long gapAt(long now) { return lastFrame < 0 ? 0 : Math.max(longestGap, now - lastFrame); }
}
