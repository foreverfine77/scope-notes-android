package dev.earclean.preview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.ArrayList;
import java.util.UUID;

/** A draft selection is committed only by the explicit apply action. */
public final class CoverPickerActivity extends Activity {
    private final ScopeUi style = new ScopeUi(this);
    private final ArrayList<Bitmap> images = new ArrayList<>();
    private final ArrayList<View> tiles = new ArrayList<>();
    private String owner;
    private Bitmap selected;
    private CircleImage preview;
    private File draft;
    private boolean saving;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.apply(base));
    }

    @Override protected void onCreate(Bundle state) {
        style.load();
        setTheme(style.light ? android.R.style.Theme_Material_Light_NoActionBar
                : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        owner = getIntent().getStringExtra(CareProfiles.EXTRA);
        if (owner == null || !new CareProfiles(this).ids().contains(owner)) { finish(); return; }
        draft = new File(getCacheDir(), state == null || state.getString("draft") == null ? "cover-draft-" + UUID.randomUUID() + ".png"
                : state.getString("draft"));
        selected = state == null ? null : BitmapFactory.decodeFile(draft.getPath());
        if (selected == null) selected = ProfileMasks.load(this, owner);
        images.add(selected);
        LinearLayout page = style.page();
        style.fit(this, page, 16, 12);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = style.iconButton("back", getString(R.string.back));
        back.setOnClickListener(v -> onBackPressed());
        header.addView(back, new LinearLayout.LayoutParams(style.dp(48), style.dp(48)));
        TextView title = style.label(getString(R.string.mask_image), 20, style.TEXT);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(new View(this), new LinearLayout.LayoutParams(style.dp(48), 1));
        page.addView(header);
        TextView name = style.label(new CareProfiles(this).name(owner), 14, style.MUTED);
        name.setGravity(Gravity.CENTER);
        style.add(page, name, 8);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int[] resources = {R.drawable.cover_default, R.drawable.cover_duo,
                R.drawable.cover_sad, R.drawable.cover_sweet};
        int[] labels = {R.string.cover_default, R.string.cover_duo,
                R.string.cover_sad, R.string.cover_sweet};
        ArrayList<Bitmap> options = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        if (ProfileMasks.file(this, owner).isFile()) {
            options.add(selected); names.add(getString(R.string.cover_current));
        }
        for (int i = 0; i < resources.length; i++) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), resources[i]);
            images.add(bitmap); options.add(bitmap); names.add(getString(labels[i]));
        }
        LinearLayout row = null;
        for (int i = 0; i <= options.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER);
                style.add(body, row, 12);
            }
            final Bitmap candidate = i < options.size() ? options.get(i) : null;
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(style.dp(4), style.dp(8), style.dp(4), style.dp(8));
            LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, -2, 1);
            slot.setMargins(style.dp(4), 0, style.dp(4), 0);
            row.addView(tile, slot);
            String label = candidate == null ? getString(R.string.cover_add) : names.get(i);
            if (candidate == null) {
                TextView plus = style.label("+", 36, style.TEXT);
                plus.setGravity(Gravity.CENTER);
                plus.setBackground(style.pill(style.SURFACE, 40, style.EDGE));
                tile.addView(plus, new LinearLayout.LayoutParams(style.dp(76), style.dp(76)));
            } else {
                CircleImage thumb = new CircleImage(candidate);
                tile.addView(thumb, new LinearLayout.LayoutParams(style.dp(76), style.dp(76)));
            }
            TextView caption = style.label(label, 12, style.MUTED);
            caption.setGravity(Gravity.CENTER);
            style.add(tile, caption, 6);
            tile.setContentDescription(label);
            tile.setOnClickListener(v -> {
                AppFeedback.get(this).play(tile, "click");
                if (candidate == null) {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 1);
                } else {
                    select(candidate);
                    for (View t : tiles) t.setBackground(null);
                    tile.setBackground(style.pill(style.SOFT, 20, style.ACCENT));
                }
            });
            tiles.add(tile);
        }
        // Keep the final row on the same three-column grid.
        if ((options.size() + 1) % 3 != 0)
            for (int i = (options.size() + 1) % 3; i < 3; i++)
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        preview = new CircleImage(selected);
        LinearLayout.LayoutParams previewSize = new LinearLayout.LayoutParams(style.dp(260), style.dp(260));
        previewSize.gravity = Gravity.CENTER_HORIZONTAL;
        previewSize.topMargin = style.dp(20);
        body.addView(preview, previewSize);
        Button adjust = style.button(getString(R.string.mask_adjust));
        adjust.setOnClickListener(v -> {
            if (!saving) MaskCropper.show(this, selected.copy(Bitmap.Config.ARGB_8888, true), this::acceptCrop);
        });
        LinearLayout.LayoutParams adjustment = new LinearLayout.LayoutParams(-2, style.dp(48));
        adjustment.gravity = Gravity.CENTER_HORIZONTAL;
        adjustment.topMargin = style.dp(12);
        body.addView(adjust, adjustment);
        Button apply = style.button(getString(R.string.cover_apply));
        apply.setOnClickListener(v -> apply(apply));
        style.add(page, apply, 16);
        setContentView(page);
        style.decorateWindow(getWindow());
    }

    private void select(Bitmap bitmap) {
        selected = bitmap;
        preview.bitmap = bitmap;
        preview.invalidate();
    }

    private void acceptCrop(Bitmap bitmap) {
        images.add(bitmap);
        select(bitmap);
        for (View tile : tiles) tile.setBackground(null);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null && data.getData() != null)
            MaskCropper.fromUri(this, data.getData(), this::acceptCrop);
    }

    private void apply(Button button) {
        if (saving) return;
        final Bitmap output = circularCopy(selected);
        saving = true;
        button.setEnabled(false);
        new Thread(() -> {
            boolean success = false;
            try {
                if (!new CareProfiles(this).ids().contains(owner)) throw new IOException("Profile removed");
                ProfileMasks.save(this, owner, output);
                success = true;
            } catch (IOException | RuntimeException ignored) {
            } finally { output.recycle(); }
            final boolean done = success;
            runOnUiThread(() -> {
                saving = false;
                if (isDestroyed()) return;
                if (done) { setResult(RESULT_OK); finish(); }
                else {
                    button.setEnabled(true);
                    Toast.makeText(this, R.string.mask_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }, "cover-apply").start();
    }

    private Bitmap circularCopy(Bitmap source) {
        Bitmap output = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        drawCircleImage(canvas, source, 768, 768);
        return output;
    }

    private static void drawCircleImage(Canvas canvas, Bitmap source, int width, int height) {
        float size = Math.min(width, height);
        float cx = width / 2f, cy = height / 2f;
        Path circle = new Path();
        circle.addCircle(cx, cy, size / 2, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(circle);
        canvas.drawColor(0xfffbf9f2);
        float scale = Math.max(size / source.getWidth(), size / source.getHeight());
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, null, new RectF(cx - source.getWidth() * scale / 2,
                cy - source.getHeight() * scale / 2, cx + source.getWidth() * scale / 2,
                cy + source.getHeight() * scale / 2), paint);
        canvas.restore();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (draft == null || selected == null) return;
        try (FileOutputStream out = new FileOutputStream(draft)) {
            selected.compress(Bitmap.CompressFormat.PNG, 100, out);
            state.putString("draft", draft.getName());
        } catch (IOException ignored) { }
    }

    @Override public void onBackPressed() { if (!saving) super.onBackPressed(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (isFinishing() && draft != null) draft.delete();
        for (Bitmap bitmap : images) if (!bitmap.isRecycled()) bitmap.recycle();
    }

    private final class CircleImage extends View {
        Bitmap bitmap;
        CircleImage(Bitmap bitmap) { super(CoverPickerActivity.this); this.bitmap = bitmap; }
        @Override protected void onDraw(Canvas canvas) {
            if (bitmap != null) drawCircleImage(canvas, bitmap, getWidth(), getHeight());
        }
    }
}
