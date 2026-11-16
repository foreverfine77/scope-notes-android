package dev.earclean.preview;

final class PhotoPaging {
    static boolean commit(float dx, float velocity, float width, boolean hasTarget) {
        if (!hasTarget || width <= 0) return false;
        return Math.abs(dx) >= width * .22f
                || (Math.abs(dx) >= 12 && Math.abs(velocity) >= 650 && dx * velocity > 0);
    }
}
