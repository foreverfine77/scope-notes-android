package dev.earclean.preview;

import java.util.ArrayList;
import java.util.List;

/** Estimates frame-to-frame rotation of the scene, excluding the attached scoop. */
final class BackgroundRoll {
    static final int SIZE = 96;
    private static final int PATCH = 3;
    private static final int SEARCH = 9;
    private static final float INLIER_ERROR = 2f;
    private final int[] previous = new int[SIZE * SIZE];
    private boolean ready;

    void reset() {
        ready = false;
    }

    float update(int[] current) {
        float delta = ready ? estimate(current) : Float.NaN;
        System.arraycopy(current, 0, previous, 0, previous.length);
        ready = true;
        return delta;
    }

    private float estimate(int[] current) {
        List<Match> matches = new ArrayList<>();
        for (int top = 8; top < SIZE - 8; top += 9) {
            for (int left = 8; left < SIZE / 2 - 3; left += 9) {
                int point = corner(left, top);
                if (point < 0) continue;
                int next = find(previous, current, point);
                if (next < 0) continue;
                int back = find(current, previous, next);
                if (back < 0 || Math.abs(back % SIZE - point % SIZE) > 1
                        || Math.abs(back / SIZE - point / SIZE) > 1) continue;
                matches.add(new Match(point, next));
            }
        }
        if (matches.size() < 5) return Float.NaN;

        // Score a similarity transform from each well-separated pair. Fitting
        // translation as well keeps sideways hand motion out of the roll estimate.
        int bestCount = 0;
        double bestError = Double.POSITIVE_INFINITY;
        Transform best = null;
        for (int i = 0; i < matches.size(); i++) {
            Match first = matches.get(i);
            for (int j = i + 1; j < matches.size(); j++) {
                Match second = matches.get(j);
                double dx = second.x - first.x;
                double dy = second.y - first.y;
                double length = dx * dx + dy * dy;
                if (length < 18 * 18) continue;
                double u = second.u - first.u;
                double v = second.v - first.v;
                Transform candidate = new Transform((u * dx + v * dy) / length,
                        (v * dx - u * dy) / length, first.x, first.y, first.u, first.v);
                if (!candidate.plausible()) continue;
                int count = 0;
                double error = 0;
                for (Match match : matches) {
                    double residual = candidate.error(match);
                    if (residual <= INLIER_ERROR * INLIER_ERROR) {
                        count++;
                        error += residual;
                    }
                }
                if (count > bestCount || (count == bestCount && error < bestError)) {
                    bestCount = count;
                    bestError = error;
                    best = candidate;
                }
            }
        }
        if (bestCount < 5 || bestCount * 5 < matches.size() * 3) return Float.NaN;

        double x = 0, y = 0, u = 0, v = 0;
        for (Match match : matches) {
            if (best.error(match) > INLIER_ERROR * INLIER_ERROR) continue;
            x += match.x;
            y += match.y;
            u += match.u;
            v += match.v;
        }
        x /= bestCount;
        y /= bestCount;
        u /= bestCount;
        v /= bestCount;
        double dot = 0, cross = 0, spread = 0;
        for (Match match : matches) {
            if (best.error(match) > INLIER_ERROR * INLIER_ERROR) continue;
            double dx = match.x - x;
            double dy = match.y - y;
            dot += dx * (match.u - u) + dy * (match.v - v);
            cross += dx * (match.v - v) - dy * (match.u - u);
            spread += dx * dx + dy * dy;
        }
        if (spread < bestCount * 60) return Float.NaN;
        float angle = (float) Math.toDegrees(Math.atan2(cross, dot));
        return Math.abs(angle) <= 15 ? angle : Float.NaN;
    }

    private int corner(int left, int top) {
        int best = -1;
        int score = 140;
        for (int y = top; y < Math.min(top + 9, SIZE - 8); y += 2) {
            for (int x = left; x < Math.min(left + 9, SIZE / 2 - 3); x += 2) {
                if (!background(x, y)) continue;
                int point = y * SIZE + x;
                int xx = 0, yy = 0, xy = 0;
                for (int row = -2; row <= 2; row++) {
                    for (int col = -2; col <= 2; col++) {
                        int index = point + row * SIZE + col;
                        int gx = previous[index + 1] - previous[index - 1];
                        int gy = previous[index + SIZE] - previous[index - SIZE];
                        xx += gx * gx;
                        yy += gy * gy;
                        xy += gx * gy;
                    }
                }
                int strength = (int) ((xx * (double) yy - xy * (double) xy) / (xx + yy + 1));
                if (strength > score) {
                    score = strength;
                    best = point;
                }
            }
        }
        return best;
    }

    private static boolean background(int x, int y) {
        int dx = x - SIZE / 2;
        int dy = y - SIZE / 2;
        // On this model the fixed scoop enters the unrotated image from the right.
        return x < SIZE / 2 - 2 && dx * dx + dy * dy < 40 * 40;
    }

    private static int find(int[] from, int[] to, int point) {
        int x = point % SIZE;
        int y = point / SIZE;
        int mean = mean(from, point);
        int best = -1;
        int bestError = 500;
        for (int v = y - SEARCH; v <= y + SEARCH; v++) {
            for (int u = x - SEARCH; u <= x + SEARCH; u++) {
                if (!background(u, v)) continue;
                int candidate = v * SIZE + u;
                int offset = mean(to, candidate) - mean;
                int error = 0;
                for (int row = -PATCH; row <= PATCH && error < bestError; row++) {
                    for (int col = -PATCH; col <= PATCH; col++) {
                        int index = row * SIZE + col;
                        error += Math.abs(from[point + index] - to[candidate + index] + offset);
                    }
                }
                if (error < bestError) {
                    best = candidate;
                    bestError = error;
                }
            }
        }
        return best;
    }

    private static int mean(int[] image, int center) {
        int sum = 0;
        for (int row = -PATCH; row <= PATCH; row++) {
            for (int col = -PATCH; col <= PATCH; col++) {
                sum += image[center + row * SIZE + col];
            }
        }
        return sum / 49;
    }

    private static final class Match {
        final int x, y, u, v;

        Match(int from, int to) {
            x = from % SIZE;
            y = from / SIZE;
            u = to % SIZE;
            v = to / SIZE;
        }
    }

    private static final class Transform {
        final double a, b, tx, ty;

        Transform(double a, double b, double x, double y, double u, double v) {
            this.a = a;
            this.b = b;
            tx = u - a * x + b * y;
            ty = v - b * x - a * y;
        }

        boolean plausible() {
            double scale = a * a + b * b;
            return scale >= 0.72 && scale <= 1.38 && a > 0 && Math.abs(b) < a * 0.27;
        }

        double error(Match match) {
            double dx = a * match.x - b * match.y + tx - match.u;
            double dy = b * match.x + a * match.y + ty - match.v;
            return dx * dx + dy * dy;
        }
    }
}
