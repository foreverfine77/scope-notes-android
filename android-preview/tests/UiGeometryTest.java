package dev.earclean.preview;

public final class UiGeometryTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        float[] centers = {32, 96, 160};
        check(UiGeometry.reorderTarget(0, 128, 64, centers) == 2,
                "A row must reach the last slot even exactly at the last center");
        check(UiGeometry.reorderTarget(0, 600, 64, centers) == 2,
                "Dragging beyond the final row must insert at the end");
        check(UiGeometry.reorderTarget(2, 0, 64, centers) == 0,
                "A row must reach the first slot exactly at the first center");
        check(UiGeometry.reorderTarget(1, 64, 64, centers) == 1,
                "A stationary row must retain its slot");
        System.out.println("PASS: first/last insertion and stationary reorder");
        for (int count : new int[]{1, 2, 6, 50}) {
            float[] slots = new float[count];
            for (int i = 0; i < count; i++) slots[i] = 8 + i * 64 + 28;
            for (int from = 0; from < count; from++) {
                for (int to = 0; to < count; to++) {
                    check(UiGeometry.reorderTarget(from, slots[to] - 28, 56, slots) == to,
                            "Every slot must be reachable, including first/last with row margins");
                }
            }
        }
        System.out.println("PASS: all reorder slots for 1, 2, 6 and 50 rows");
        for (float[] size : new float[][]{{240, 320}, {320, 320}, {360, 640}, {640, 360}, {1440, 1800}}) {
            float w = size[0], h = size[1], unit = Math.min(w, h);
            UiGeometry.Lens lens = UiGeometry.lens(w, h, true);
            float stroke = 3;
            check(lens.x - lens.radius - stroke > 0 && lens.x + lens.radius + stroke < w,
                    "Front rim must stay within horizontal bounds");
            check(lens.y - lens.radius - stroke > 0 && lens.y + lens.radius + stroke < h,
                    "Front rim must stay within vertical bounds");
            check(lens.backX - lens.backRadius - stroke > 0 && lens.backX + lens.backRadius + stroke < w,
                    "Orange disc must not clip at either horizontal edge");
            check(lens.backY - lens.backRadius - stroke > 0 && lens.backY + lens.backRadius + stroke < h,
                    "Orange disc must not clip at either vertical edge");
            float reveal = (lens.backX + lens.backRadius - lens.x - lens.radius) / (2 * lens.radius);
            check(reveal >= .12f && reveal <= .16f, "Orange reveal should be 12-16% of aperture diameter");
            UiGeometry.Lens normal = UiGeometry.lens(w, h, false);
            check(normal.x == w/2 && normal.y == h/2 && Math.abs(normal.radius - unit*.4425f) < .001f,
                    "Other themes must retain centered aperture geometry");
        }
        System.out.println("PASS: orange disc bounds and reveal on five portrait/square/landscape sizes");
    }
}
