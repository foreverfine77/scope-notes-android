package dev.earclean.preview;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Local reorder gesture: no text selection, clipboard payload or system drag session. */
final class ProfileOrderList extends LinearLayout {
    interface Move { void save(String id, String target, boolean after); }
    private final ScopeUi style;
    private final Move move;
    private final List<View> original = new ArrayList<>();
    private View candidate, lifted;
    private float downX, downY, pointerY, grab;
    private int pointer;
    private boolean settling;
    private View overlayHost;
    private FloatingCard card;
    private ValueAnimator landing;
    private final int[] listLocation = new int[2], hostLocation = new int[2], viewportLocation = new int[2];
    private final Runnable begin = () -> lift();
    private final Runnable edgeScroll = new Runnable() {
        @Override public void run() {
            if (lifted == null) return;
            ScrollView scroll = (ScrollView) getParent();
            float visibleY = pointerY - scroll.getScrollY();
            int step = visibleY < style.dp(56) ? -style.dp(6)
                    : visibleY > scroll.getHeight() - style.dp(56) ? style.dp(6) : 0;
            int old = scroll.getScrollY();
            scroll.scrollBy(0, step);
            pointerY += scroll.getScrollY() - old;
            position();
            postDelayed(this, 16);
        }
    };

    ProfileOrderList(Context context, ScopeUi style, Move move) {
        super(context);
        this.style = style;
        this.move = move;
        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (settling) return true;
        if (!isEnabled()) return super.dispatchTouchEvent(e);
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = pointerY = e.getY(); pointer = e.getPointerId(0);
            candidate = null;
            // The delete control remains an ordinary button; all other row space can lift.
            if (downX < getWidth() - style.dp(56)) {
                for (int i = 0; i < getChildCount(); i++) {
                    View row = getChildAt(i);
                    if (downY >= row.getTop() && downY <= row.getBottom()) candidate = row;
                }
            }
            if (candidate != null && getChildCount() > 1) postDelayed(begin, 180);
        } else if (action == MotionEvent.ACTION_MOVE) {
            int index = e.findPointerIndex(pointer);
            if (index < 0) { finish(false); return true; }
            pointerY = e.getY(index);
            if (lifted != null) { position(); return true; }
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.hypot(e.getX(index) - downX, pointerY - downY) > slop) {
                removeCallbacks(begin); candidate = null;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(begin);
            candidate = null;
            if (lifted != null) { finish(action == MotionEvent.ACTION_UP); return true; }
        }
        return super.dispatchTouchEvent(e);
    }

    private void lift() {
        if (candidate == null || !isAttachedToWindow() || !isEnabled()) return;
        lifted = candidate;
        original.clear();
        for (int i = 0; i < getChildCount(); i++) original.add(getChildAt(i));
        grab = downY - lifted.getTop();
        MotionEvent cancel = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, downX, downY, 0);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        getParent().requestDisallowInterceptTouchEvent(true);
        lifted.animate().cancel();
        Bitmap snapshot = Bitmap.createBitmap(lifted.getWidth(), lifted.getHeight(), Bitmap.Config.ARGB_8888);
        lifted.draw(new Canvas(snapshot));
        overlayHost = (View) ((View) getParent()).getParent();
        card = new FloatingCard(snapshot);
        card.setBounds(0, 0, overlayHost.getWidth(), overlayHost.getHeight());
        overlayHost.getOverlay().add(card);
        lifted.setAlpha(0); // The real row stays as the insertion placeholder.
        positionCard(downY - grab);
        AppFeedback.get(getContext()).play(lifted, "swipe");
        post(edgeScroll);
    }

    private void position() {
        if (lifted == null) return;
        float top = pointerY - grab;
        positionCard(top);
        int from = indexOfChild(lifted);
        float[] centers = new float[getChildCount()];
        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            centers[i] = other.getTop() + other.getHeight() / 2f;
        }
        int to = UiGeometry.reorderTarget(from, top, lifted.getHeight(), centers);
        if (from == to) return;
        Map<View, Float> old = new HashMap<>();
        for (int i = 0; i < getChildCount(); i++) old.put(getChildAt(i), getChildAt(i).getY());
        View row = lifted;
        removeView(row);
        addView(row, to);
        // Layout now so the next pointer event uses the new positions, even within this frame.
        measure(MeasureSpec.makeMeasureSpec(getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.EXACTLY));
        layout(getLeft(), getTop(), getRight(), getBottom());
        for (int i = 0; i < getChildCount(); i++) {
            View other = getChildAt(i);
            if (other == row) continue;
            other.animate().cancel();
            other.setTranslationY(old.get(other) - other.getTop());
            other.animate().translationY(0).setDuration(180).start();
        }
        positionCard(top);
    }

    private void positionCard(float top) {
        if (card == null) return;
        getLocationInWindow(listLocation);
        overlayHost.getLocationInWindow(hostLocation);
        View viewport = (View) getParent();
        viewport.getLocationInWindow(viewportLocation);
        float minY = viewportLocation[1] - hostLocation[1] + style.dp(5);
        float maxY = Math.max(minY, minY + viewport.getHeight() - lifted.getHeight() - style.dp(10));
        card.x = listLocation[0] - hostLocation[0] + lifted.getLeft();
        card.y = Math.max(minY, Math.min(maxY, listLocation[1] - hostLocation[1] + top));
        card.scale = 1.015f;
        card.invalidateSelf();
    }

    private void finish(boolean save) {
        removeCallbacks(begin); removeCallbacks(edgeScroll);
        if (lifted == null) return;
        View row = lifted;
        lifted = candidate = null;
        getParent().requestDisallowInterceptTouchEvent(false);
        if (save) {
            int index = indexOfChild(row);
            if (index != original.indexOf(row)) {
                View target = getChildAt(index == 0 ? 1 : index - 1);
                move.save((String) row.getTag(), (String) target.getTag(), index != 0);
            }
        } else {
            removeAllViews();
            for (View child : original) { child.animate().cancel(); child.setTranslationY(0); addView(child); }
        }
        original.clear();
        settling = true;
        // Wait for any restored layout before animating the overlay into its final slot.
        post(() -> land(row));
    }

    private void land(View row) {
        if (card == null || !isAttachedToWindow()) { clearCard(row); return; }
        getLocationInWindow(listLocation);
        overlayHost.getLocationInWindow(hostLocation);
        float startX = card.x, startY = card.y;
        float targetX = listLocation[0] - hostLocation[0] + row.getLeft();
        float targetY = listLocation[1] - hostLocation[1] + row.getTop();
        landing = ValueAnimator.ofFloat(0, 1);
        landing.setDuration(180);
        landing.addUpdateListener(value -> {
            if (card == null) return;
            float t = (Float) value.getAnimatedValue();
            card.x = startX + (targetX - startX) * t;
            card.y = startY + (targetY - startY) * t;
            card.scale = 1.015f - .015f * t;
            card.invalidateSelf();
        });
        landing.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { clearCard(row); }
        });
        landing.start();
    }

    private void clearCard(View row) {
        row.setAlpha(1);
        if (card != null) {
            overlayHost.getOverlay().remove(card);
            card.bitmap.recycle();
            card = null;
        }
        overlayHost = null;
        landing = null;
        settling = false;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(begin); removeCallbacks(edgeScroll);
        if (landing != null) landing.cancel();
        if (lifted != null) clearCard(lifted);
        // A queued landing may not have started yet.
        if (card != null) {
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).setAlpha(1);
            overlayHost.getOverlay().remove(card);
            card.bitmap.recycle(); card = null; overlayHost = null;
        }
        lifted = candidate = null;
        original.clear(); settling = false;
        super.onDetachedFromWindow();
    }

    private final class FloatingCard extends Drawable {
        final Bitmap bitmap;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF bounds = new RectF();
        float x, y, scale = 1.015f;
        FloatingCard(Bitmap bitmap) { this.bitmap = bitmap; }
        @Override public void draw(Canvas canvas) {
            float dx = bitmap.getWidth() * (scale - 1) / 2f;
            float dy = bitmap.getHeight() * (scale - 1) / 2f;
            bounds.set(x - dx, y - dy, x + bitmap.getWidth() + dx, y + bitmap.getHeight() + dy);
            paint.setColor(0x14000000);
            RectF shadow = new RectF(bounds);
            shadow.inset(-style.dp(3), -style.dp(3)); shadow.offset(0, style.dp(3));
            canvas.drawRoundRect(shadow, style.dp(28), style.dp(28), paint);
            paint.setColor(0xffffffff);
            canvas.drawBitmap(bitmap, null, bounds, paint);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Canvas label avoids OEM TextView long-press recognition while retaining accessibility. */
    static View name(Context context, ScopeUi style, String name) {
        View label = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(style.TEXT);
                paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
                String text = TextUtils.ellipsize(name, new android.text.TextPaint(paint),
                        Math.max(0, getWidth() - style.dp(16)), TextUtils.TruncateAt.END).toString();
                float x = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                        ? getWidth() - style.dp(8) - paint.measureText(text) : style.dp(8);
                canvas.drawText(text, x, getHeight()/2f - (paint.ascent()+paint.descent())/2f, paint);
            }
            @Override public CharSequence getAccessibilityClassName() { return "android.widget.Button"; }
        };
        label.setId(R.id.profile_name);
        label.setContentDescription(name);
        label.setFocusable(true);
        label.setLongClickable(false);
        label.setBackground(style.pill(0, 24, 0));
        return label;
    }
}
