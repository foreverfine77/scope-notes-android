package dev.earclean.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

final class PhotoMarkup extends View {
    interface Changes {
        void changed(boolean hasMarks, boolean canRedo);
    }

    private final Bitmap photo;
    private final boolean circular;
    private final Changes changes;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask = new Path();
    private final RectF photoBounds;
    private final ArrayList<Mark> marks = new ArrayList<>();
    private final ArrayList<Mark> undone = new ArrayList<>();
    private Mark draft;
    private boolean arrow;
    private int color = 0xffff6545;
    private float scale;
    private float left;
    private float top;

    PhotoMarkup(Context context, Bitmap photo, boolean circular, String description, Changes changes) {
        super(context);
        this.photo = photo;
        this.circular = circular;
        this.changes = changes;
        photoBounds = new RectF(0, 0, photo.getWidth(), photo.getHeight());
        setContentDescription(description);
        float diameter = Math.min(photo.getWidth(), photo.getHeight());
        mask.addCircle(photo.getWidth() / 2f, photo.getHeight() / 2f, diameter / 2f, Path.Direction.CW);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(Math.max(3f, diameter / 85f));
        ink.setStrokeCap(Paint.Cap.ROUND);
    }

    void setArrow(boolean arrow) {
        this.arrow = arrow;
    }

    void setColor(int color) {
        this.color = color;
    }

    boolean canRedo() {
        return !undone.isEmpty();
    }

    void undo() {
        if (marks.isEmpty()) return;
        undone.add(marks.remove(marks.size() - 1));
        changes.changed(!marks.isEmpty(), true);
        invalidate();
    }

    void redo() {
        if (undone.isEmpty()) return;
        marks.add(undone.remove(undone.size() - 1));
        changes.changed(true, !undone.isEmpty());
        invalidate();
    }

    Bitmap createCopy() {
        Bitmap copy = photo.copy(Bitmap.Config.ARGB_8888, true);
        if (copy != null) drawMarks(new Canvas(copy));
        return copy;
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        scale = Math.min((float) width / photo.getWidth(), (float) height / photo.getHeight());
        left = (width - photo.getWidth() * scale) / 2f;
        top = (height - photo.getHeight() * scale) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        if (circular) canvas.clipPath(mask);
        canvas.drawBitmap(photo, null, photoBounds, imagePaint);
        drawMarks(canvas);
        if (draft != null) drawMark(canvas, draft);
        canvas.restore();
    }

    private void drawMarks(Canvas canvas) {
        canvas.save();
        if (circular) canvas.clipPath(mask);
        for (Mark mark : marks) drawMark(canvas, mark);
        canvas.restore();
    }

    private void drawMark(Canvas canvas, Mark mark) {
        ink.setColor(mark.color);
        if (!mark.arrow) {
            canvas.drawOval(Math.min(mark.x, mark.endX), Math.min(mark.y, mark.endY),
                    Math.max(mark.x, mark.endX), Math.max(mark.y, mark.endY), ink);
            return;
        }
        float dx = mark.endX - mark.x;
        float dy = mark.endY - mark.y;
        float length = (float) Math.hypot(dx, dy);
        if (length == 0) return;
        float head = Math.min(5 * ink.getStrokeWidth(), length * .4f);
        float ux = dx / length;
        float uy = dy / length;
        float baseX = mark.endX - head * ux;
        float baseY = mark.endY - head * uy;
        float spread = head * .5f;
        canvas.drawLine(mark.x, mark.y, mark.endX, mark.endY, ink);
        canvas.drawLine(mark.endX, mark.endY, baseX - spread * uy, baseY + spread * ux, ink);
        canvas.drawLine(mark.endX, mark.endY, baseX + spread * uy, baseY - spread * ux, ink);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || scale <= 0) return false;
        float x = (event.getX() - left) / scale;
        float y = (event.getY() - top) / scale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (x < 0 || y < 0 || x >= photo.getWidth() || y >= photo.getHeight()) return false;
                if (circular && Math.hypot(x - photo.getWidth() / 2f, y - photo.getHeight() / 2f)
                        > Math.min(photo.getWidth(), photo.getHeight()) / 2f) return false;
                draft = new Mark(x, y, arrow, color);
                break;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (draft == null) return false;
                draft.endX = Math.max(0, Math.min(photo.getWidth(), x));
                draft.endY = Math.max(0, Math.min(photo.getHeight(), y));
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    float dx = Math.abs(draft.endX - draft.x);
                    float dy = Math.abs(draft.endY - draft.y);
                    if (draft.arrow ? Math.hypot(dx, dy) >= 3 : dx >= 3 && dy >= 3) {
                        marks.add(draft);
                        undone.clear();
                        changes.changed(true, false);
                    }
                    draft = null;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                draft = null;
                break;
            default:
                return true;
        }
        invalidate();
        return true;
    }

    private static final class Mark {
        final float x;
        final float y;
        final boolean arrow;
        final int color;
        float endX;
        float endY;

        Mark(float x, float y, boolean arrow, int color) {
            this.x = endX = x;
            this.y = endY = y;
            this.arrow = arrow;
            this.color = color;
        }
    }
}
