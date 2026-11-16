package dev.earclean.preview;

/** Geometry shared by drawing, cover hit testing and local regression checks. */
final class UiGeometry {
    private UiGeometry() {}

    static int reorderTarget(int from, float requestedTop, float rowHeight,
                             float[] centers) {
        // Use the pointer's intended position, not the visual card's clamped position.
        float center = requestedTop + rowHeight / 2f;
        int to = from;
        for (int i = 0; i < centers.length; i++) {
            if (i < from && center <= centers[i]) return i;
            if (i > from && center >= centers[i]) to = i;
        }
        return to;
    }

    static final class Lens {
        final float x, y, radius, backX, backY, backRadius;
        Lens(float x, float y, float radius, float backX, float backY, float backRadius) {
            this.x = x; this.y = y; this.radius = radius;
            this.backX = backX; this.backY = backY; this.backRadius = backRadius;
        }
    }

    static Lens lens(float width, float height, boolean orange) {
        float unit = Math.min(width, height);
        if (!orange) return new Lens(width / 2f, height / 2f, unit * .4425f, 0, 0, 0);
        float x = width / 2f - unit * .0625f, y = height / 2f + unit * .0475f;
        return new Lens(x, y, unit * .4f, x + unit * .1f, y - unit * .07f, unit * .425f);
    }
}
