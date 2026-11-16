package dev.earclean.preview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class LensCover extends View {
    interface Host {
        boolean canToggle(boolean opening);
        void changed(boolean opened);
        void shape(float progress);
        boolean inFrame(float x, float y);
    }

    private final Host host;
    private final boolean orange;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path circle = new Path();
    private final RectF target = new RectF();
    private Bitmap image;
    private boolean open;
    private boolean macro;
    private float downX, downY, offsetX, offsetY;
    private float shapeProgress;
    private ValueAnimator animator;

    LensCover(Context context, Host host) {
        super(context);
        this.host = host;
        orange = "orange-outline".equals(ScopeUi.selectedTheme(context));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setClickable(true);
        setSoundEffectsEnabled(false);
        updateLabel();
    }

    void setImage(Bitmap bitmap) {
        Bitmap previous = image;
        image = bitmap;
        if (previous != null && previous != bitmap) previous.recycle();
        invalidate();
    }

    void setMacro(boolean enabled) {
        macro = enabled;
        setShape(open && macro ? 1 : 0);
    }

    void restore(boolean opened) {
        if (animator != null) {
            ValueAnimator running = animator;
            animator = null;
            running.cancel();
        }
        open = opened;
        offsetX = offsetY = 0;
        setShape(open && macro ? 1 : 0);
        updateLabel();
        invalidate();
    }

    private void updateLabel() {
        setContentDescription(getContext().getString(open ? R.string.close_viewfinder : R.string.start_viewfinder));
    }

    private void setShape(float value) {
        shapeProgress = value;
        host.shape(value);
        invalidate();
    }

    private float diameter() {
        return UiGeometry.lens(getWidth(), getHeight(), orange).radius * 2;
    }

    private boolean inside(float x, float y) {
        if (open && macro) return host.inFrame(x, y);
        UiGeometry.Lens lens = UiGeometry.lens(getWidth(), getHeight(), orange);
        return Math.hypot(x - lens.x, y - lens.y) <= lens.radius;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (image == null || (open && animator == null)) return;
        UiGeometry.Lens lens = UiGeometry.lens(getWidth(), getHeight(), orange);
        float size = diameter();
        float x = lens.x + offsetX;
        float y = lens.y + offsetY;
        target.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
        circle.reset();
        circle.addCircle(lens.x, lens.y, size / 2, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(circle);
        circle.reset();
        circle.addCircle(x, y, size / 2, Path.Direction.CW);
        canvas.clipPath(circle);
        paint.setColor(0xfffbf9f2);
        canvas.drawRect(target, paint);
        float scale = Math.max(size / image.getWidth(), size / image.getHeight());
        RectF photo = new RectF(x - image.getWidth() * scale / 2, y - image.getHeight() * scale / 2,
                x + image.getWidth() * scale / 2, y + image.getHeight() * scale / 2);
        canvas.drawBitmap(image, null, photo, paint);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (animator != null) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inside(event.getX(), event.getY()) || !host.canToggle(!open)) return false;
                downX = event.getX();
                downY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!open) {
                    offsetX = event.getX() - downX;
                    offsetY = event.getY() - downY;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.hypot(dx, dy) >= 28 * getResources().getDisplayMetrics().density) {
                    toggle(dx, dy);
                } else if (!open) {
                    animateOffset(0, 0, null);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!open) animateOffset(0, 0, null);
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (animator == null && host.canToggle(!open)) toggle(0, -1);
        return true;
    }

    private void toggle(float dx, float dy) {
        double distance = Math.max(1, Math.hypot(dx, dy));
        float travel = diameter() * 1.25f;
        float x = (float) (dx / distance * travel);
        float y = (float) (dy / distance * travel);
        AppFeedback.get(getContext()).play(this, "swipe");
        if (!open) {
            animateOffset(x, y, () -> animateShape(macro ? 1 : 0, () -> {
                if (!host.canToggle(true)) {
                    restore(false);
                    return;
                }
                open = true;
                animator = null;
                updateLabel();
                host.changed(true);
                invalidate();
            }));
        } else {
            // The incoming cover travels in the direction of the new gesture.
            offsetX = -x;
            offsetY = -y;
            animateShape(0, () -> {
                open = false;
                host.changed(false);
                updateLabel();
                animateOffset(0, 0, null);
            });
        }
    }

    private long duration() {
        return android.os.Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled() ? 0 : 180;
    }

    private void animateOffset(float x, float y, Runnable done) {
        final float startX = offsetX, startY = offsetY;
        animate(value -> {
            offsetX = startX + (x - startX) * value;
            offsetY = startY + (y - startY) * value;
            invalidate();
        }, done);
    }

    private void animateShape(float target, Runnable done) {
        final float start = shapeProgress;
        animate(value -> setShape(start + (target - start) * value), done);
    }

    private interface Frame { void update(float value); }

    private void animate(Frame frame, Runnable done) {
        ValueAnimator current = ValueAnimator.ofFloat(0, 1);
        animator = current;
        current.setDuration(duration());
        current.setInterpolator(new DecelerateInterpolator());
        current.addUpdateListener(value -> frame.update((Float) value.getAnimatedValue()));
        current.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (animator != current) return;
                animator = null;
                if (done != null) done.run();
                invalidate();
            }
        });
        current.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) {
            ValueAnimator running = animator;
            animator = null;
            running.cancel();
        }
        super.onDetachedFromWindow();
    }
}
