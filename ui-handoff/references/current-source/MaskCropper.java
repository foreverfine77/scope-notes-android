package dev.earclean.preview;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;

final class MaskCropper {
    interface Result { void selected(Bitmap image); }
    static void fromUri(Activity activity, Uri uri, Result result) {
        new Thread(() -> {
            Bitmap image = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(input, null, bounds);
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Unsupported image");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048)
                    options.inSampleSize *= 2;
                try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                    image = BitmapFactory.decodeStream(input, null, options);
                }
                if (image == null) throw new IOException("Cannot decode image");
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                        int orientation = new ExifInterface(input).getAttributeInt(
                                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                        Matrix matrix = new Matrix();
                        switch (orientation) {
                            case 2: matrix.setScale(-1, 1); break;
                            case 3: matrix.setRotate(180); break;
                            case 4: matrix.setScale(1, -1); break;
                            case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                            case 6: matrix.setRotate(90); break;
                            case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                            case 8: matrix.setRotate(270); break;
                            default: break;
                        }
                        if (!matrix.isIdentity()) {
                            Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), matrix, true);
                            if (rotated != image) image.recycle();
                            image = rotated;
                        }
                    } catch (IOException ignored) {
                        // Formats without EXIF still use the decoded pixels.
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                if (image != null) image.recycle();
                activity.runOnUiThread(() -> Toast.makeText(activity, R.string.mask_failed, Toast.LENGTH_SHORT).show());
                return;
            }
            final Bitmap decoded = image;
            activity.runOnUiThread(() -> {
                if (activity.isDestroyed()) decoded.recycle();
                else show(activity, decoded, result);
            });
        }, "mask-decode").start();
    }

    static void show(Activity activity, Bitmap image, Result result) {
        ScopeUi style = new ScopeUi(activity);
        style.load();
        Dialog dialog = new Dialog(activity);
        LinearLayout page = style.page();
        page.setPadding(style.dp(16), style.dp(16), style.dp(16), style.dp(32));
        TextView heading = style.label(activity.getString(R.string.mask_crop), 18, style.TEXT);
        heading.setGravity(Gravity.CENTER);
        page.addView(heading);
        TextView hint = style.label(activity.getString(R.string.mask_crop_hint), 13, style.MUTED);
        hint.setGravity(Gravity.CENTER);
        style.add(page, hint, 12);
        CropView crop = new CropView(activity, image, style);
        page.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER);
        Button cancel = style.button(activity.getString(R.string.cancel));
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel);
        Button save = style.button(activity.getString(R.string.save));
        LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(-2, style.dp(48));
        saveSize.leftMargin = style.dp(16);
        actions.addView(save, saveSize);
        page.addView(actions);
        save.setOnClickListener(view -> {
            final Bitmap output;
            try {
                output = crop.renderCrop();
            } catch (RuntimeException | OutOfMemoryError error) {
                Toast.makeText(activity, R.string.mask_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            result.selected(output);
            dialog.dismiss();
        });
        dialog.setOnDismissListener(window -> image.recycle());
        dialog.setContentView(page);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        style.decorateWindow(dialog.getWindow());
    }

    private static final class CropView extends View {
        private final Bitmap image;
        private final ScopeUi style;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path circle = new Path();
        private final ScaleGestureDetector pinch;
        private float zoom = 1, panX, panY, lastX, lastY;

        CropView(Activity activity, Bitmap image, ScopeUi style) {
            super(activity);
            this.image = image;
            this.style = style;
            setContentDescription(activity.getString(R.string.mask_crop_hint));
            pinch = new ScaleGestureDetector(activity, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float previous = zoom;
                    zoom = Math.max(1, Math.min(6, zoom * detector.getScaleFactor()));
                    float ratio = zoom / previous;
                    float x = detector.getFocusX() - getWidth() / 2f;
                    float y = detector.getFocusY() - getHeight() / 2f;
                    panX = x - (x - panX) * ratio;
                    panY = y - (y - panY) * ratio;
                    clamp();
                    invalidate();
                    return true;
                }
            });
        }

        private float diameter() { return Math.min(getWidth(), getHeight()) * .88f; }
        private float scale() { return diameter() / Math.min(image.getWidth(), image.getHeight()) * zoom; }
        private void clamp() {
            float maxX = Math.max(0, (image.getWidth() * scale() - diameter()) / 2);
            float maxY = Math.max(0, (image.getHeight() * scale() - diameter()) / 2);
            panX = Math.max(-maxX, Math.min(maxX, panX));
            panY = Math.max(-maxY, Math.min(maxY, panY));
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { clamp(); }
        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f, y = getHeight() / 2f, size = diameter();
            circle.reset();
            circle.addCircle(x, y, size / 2, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(circle);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(style.SURFACE);
            canvas.drawPaint(paint);
            drawImage(canvas);
            canvas.restore();
            paint.setColor(style.ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(style.dp(1));
            canvas.drawCircle(x, y, size / 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawImage(Canvas canvas) {
            paint.setAlpha(255);
            float scale = scale();
            float left = getWidth() / 2f + panX - image.getWidth() * scale / 2;
            float top = getHeight() / 2f + panY - image.getHeight() * scale / 2;
            canvas.drawBitmap(image, null, new RectF(left, top, left + image.getWidth() * scale,
                    top + image.getHeight() * scale), paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && !pinch.isInProgress()) {
                        panX += event.getX() - lastX;
                        panY += event.getY() - lastY;
                        clamp();
                        invalidate();
                    }
                    break;
                default: break;
            }
            int pointer = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
                    && event.getActionIndex() == 0 ? 1 : 0;
            lastX = event.getX(pointer);
            lastY = event.getY(pointer);
            return true;
        }

        Bitmap renderCrop() {
            if (diameter() <= 0) throw new IllegalStateException("Crop view not laid out");
            Bitmap output = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            float ratio = 768 / diameter();
            canvas.scale(ratio, ratio);
            canvas.translate(diameter() / 2 - getWidth() / 2f, diameter() / 2 - getHeight() / 2f);
            circle.reset();
            circle.addCircle(getWidth() / 2f, getHeight() / 2f, diameter() / 2, Path.Direction.CW);
            canvas.clipPath(circle);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(style.SURFACE);
            canvas.drawPaint(paint);
            drawImage(canvas);
            return output;
        }
    }
}
