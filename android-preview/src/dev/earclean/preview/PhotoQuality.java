package dev.earclean.preview;

final class PhotoQuality {
    private PhotoQuality() {}

    static double score(int[] pixels, int width, int height, boolean fullFrame) {
        int step = Math.max(1, Math.min(width, height) / 240);
        int centerX = width / 2;
        int centerY = height / 2;
        double radius = Math.min(width, height) * .44;
        int margin = 2 * step;
        long detail = 0;
        int samples = 0;
        for (int y = margin; y < height - margin; y += step) {
            for (int x = margin; x < width - margin; x += step) {
                int dx = x - centerX;
                int dy = y - centerY;
                if (!fullFrame && dx * dx + dy * dy > radius * radius) continue;
                samples++;
                int index = y * width + x;
                int center = pixels[index];
                int left = pixels[index - step];
                int right = pixels[index + step];
                int above = pixels[index - step * width];
                int below = pixels[index + step * width];
                if (ignore(center) || ignore(left) || ignore(right) || ignore(above) || ignore(below)) continue;
                int edge = Math.abs(4 * luma(center) - luma(left) - luma(right) - luma(above) - luma(below));
                // Ignore low-amplitude noise and cap the influence of any single edge.
                int strength = Math.max(0, Math.min(edge, 48) - 6);
                detail += strength * strength;
            }
        }
        // Masked samples contribute zero so a tiny patch cannot dominate a mostly obscured frame.
        return (double) detail / Math.max(1, samples);
    }

    private static boolean ignore(int pixel) {
        int red = (pixel >>> 16) & 255;
        int green = (pixel >>> 8) & 255;
        int blue = pixel & 255;
        boolean spoon = blue >= 75 && blue - red >= 24 && blue - green >= 10;
        boolean glare = red >= 235 && green >= 235 && blue >= 235;
        return spoon || glare;
    }

    private static int luma(int pixel) {
        return (77 * ((pixel >>> 16) & 255) + 150 * ((pixel >>> 8) & 255) + 29 * (pixel & 255)) >> 8;
    }
}
