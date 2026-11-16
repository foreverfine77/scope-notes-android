package dev.earclean.preview;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Two-page, finger-following photo navigation with cancellable release animation. */
final class PhotoPager extends FrameLayout {
    interface Source {
        File neighbor(int direction);
        void configure(ImageView image, File file);
        void changed(File file, Bitmap bitmap);
    }
    private final ImageView primary, neighbor;
    private final Source source;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Map<Integer, Bitmap> cache = new HashMap<>();
    private final Map<Integer, File> files = new HashMap<>();
    private int generation, direction;
    private boolean horizontal, multi, moving, closed;
    private float startX, startY, offset;
    private VelocityTracker velocity;
    private ValueAnimator animation;

    PhotoPager(Context context, ImageView primary, Source source) {
        super(context);
        this.primary = primary; this.source = source;
        setClipChildren(true); setClickable(true);
        neighbor = new ImageView(context);
        neighbor.setScaleType(ImageView.ScaleType.FIT_CENTER);
        neighbor.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(neighbor, new LayoutParams(-1, -1));
        addView(primary, new LayoutParams(-1, -1));
        neighbor.setVisibility(GONE);
        post(this::prefetch);
    }

    private void prefetch() {
        if (closed) return;
        int token = ++generation;
        cache.clear(); files.clear();
        int max = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        for (int d : new int[]{-1, 1}) {
            File file = source.neighbor(d);
            if (file == null) continue;
            files.put(d, file);
            decoder.execute(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) > max)
                    options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                post(() -> { if (!closed && generation == token && bitmap != null) cache.put(d, bitmap); });
            });
        }
    }

    private boolean ready(int d) {
        return cache.get(d) != null && files.get(d) != null && files.get(d).isFile();
    }

    private void showOffset(float dx) {
        direction = dx < 0 ? 1 : -1;
        boolean target = ready(direction);
        offset = Math.max(-getWidth(), Math.min(getWidth(), target ? dx : dx * .18f));
        primary.setTranslationX(offset);
        if (target) {
            neighbor.setImageBitmap(cache.get(direction));
            source.configure(neighbor, files.get(direction));
            neighbor.setVisibility(VISIBLE);
            neighbor.setTranslationX(offset + direction * getWidth());
        } else neighbor.setVisibility(GONE);
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (closed || moving) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = event.getX(); startY = event.getY(); horizontal = multi = false;
                velocity = VelocityTracker.obtain(); velocity.addMovement(event);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                multi = true;
                settle(false); return true;
            case MotionEvent.ACTION_MOVE:
                if (multi) return true;
                if (velocity != null) velocity.addMovement(event);
                float dx = event.getX() - startX, dy = event.getY() - startY;
                if (!horizontal && Math.abs(dx) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                        && Math.abs(dx) > Math.abs(dy) * 1.2f) horizontal = true;
                if (horizontal) showOffset(dx);
                return true;
            case MotionEvent.ACTION_UP:
                float speed = 0;
                if (velocity != null) { velocity.addMovement(event); velocity.computeCurrentVelocity(1000); speed = velocity.getXVelocity(); }
                float density = getResources().getDisplayMetrics().density;
                boolean commit = horizontal && !multi && PhotoPaging.commit(offset / density, speed / density,
                        getWidth() / density, ready(direction));
                if (horizontal && files.containsKey(direction) && !ready(direction))
                    Toast.makeText(getContext(), R.string.photo_loading, Toast.LENGTH_SHORT).show();
                settle(commit);
                return true;
            case MotionEvent.ACTION_CANCEL:
                settle(false); return true;
            default: return true;
        }
    }

    boolean page(int direction) {
        if (moving || closed || !ready(direction)) return false;
        showOffset(-direction);
        settle(true);
        return true;
    }

    private void settle(boolean commit) {
        if (velocity != null) { velocity.recycle(); velocity = null; }
        getParent().requestDisallowInterceptTouchEvent(false);
        moving = true;
        final int d = direction;
        final File file = files.get(d);
        final Bitmap bitmap = cache.get(d);
        final boolean change = commit && ready(d);
        final float from = offset, to = change ? -d * getWidth() : 0;
        animation = ValueAnimator.ofFloat(0, 1);
        animation.setDuration(change ? 200 : 180);
        animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        animation.addUpdateListener(value -> {
            float t = (Float) value.getAnimatedValue();
            float x = from + (to-from)*t;
            primary.setTranslationX(x);
            neighbor.setTranslationX(x + d * getWidth());
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animator) {
                if (closed) return;
                if (change && file.isFile()) source.changed(file, bitmap);
                primary.setTranslationX(0); neighbor.setTranslationX(0);
                neighbor.setVisibility(GONE); neighbor.setImageDrawable(null);
                offset = 0; moving = false; animation = null;
                if (change) prefetch();
            }
        });
        animation.start();
    }

    @Override protected void onDetachedFromWindow() {
        closed = true; generation++;
        if (animation != null) animation.cancel();
        if (velocity != null) velocity.recycle();
        velocity = null; cache.clear(); files.clear(); decoder.shutdownNow();
        super.onDetachedFromWindow();
    }
}
