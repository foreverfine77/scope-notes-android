package dev.earclean.preview;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

final class CaptureImage {
    static final int SIZE = 480;
    final Bitmap frame;
    final float rotation;
    final boolean mirror;

    CaptureImage(Bitmap frame, float rotation, boolean mirror) {
        this.frame = frame;
        this.rotation = rotation;
        this.mirror = mirror;
    }

    void render(Canvas canvas, float centerX, float centerY, float diameter,
                Paint paint, Path mask, RectF bounds) {
        float scale = diameter / Math.min(frame.getWidth(), frame.getHeight());
        float halfWidth = frame.getWidth() * scale / 2;
        float halfHeight = frame.getHeight() * scale / 2;
        bounds.set(-halfWidth, -halfHeight, halfWidth, halfHeight);
        canvas.save();
        mask.rewind();
        mask.addCircle(centerX, centerY, diameter / 2, Path.Direction.CW);
        canvas.clipPath(mask);
        canvas.translate(centerX, centerY);
        if (mirror) canvas.scale(-1, 1);
        canvas.rotate(rotation);
        canvas.drawBitmap(frame, null, bounds, paint);
        canvas.restore();
    }

    Bitmap createScreenshot() {
        Bitmap output = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(0xff0a1115);
        render(canvas, SIZE / 2f, SIZE / 2f, SIZE,
                new Paint(Paint.FILTER_BITMAP_FLAG), new Path(), new RectF());
        return output;
    }

    void renderFull(Canvas canvas, float centerX, float centerY, float width, float height,
                    Paint paint, RectF bounds) {
        float scale = Math.min(width / frame.getWidth(), height / frame.getHeight());
        float halfWidth = frame.getWidth() * scale / 2;
        float halfHeight = frame.getHeight() * scale / 2;
        bounds.set(-halfWidth, -halfHeight, halfWidth, halfHeight);
        canvas.save();
        canvas.translate(centerX, centerY);
        if (mirror) canvas.scale(-1, 1);
        canvas.drawBitmap(frame, null, bounds, paint);
        canvas.restore();
    }

    Bitmap createFullPhoto() {
        int width = frame.getWidth();
        int height = frame.getHeight();
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        renderFull(new Canvas(output), width / 2f, height / 2f, width, height,
                new Paint(Paint.FILTER_BITMAP_FLAG), new RectF());
        return output;
    }
}
