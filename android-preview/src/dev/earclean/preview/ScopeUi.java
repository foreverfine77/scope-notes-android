package dev.earclean.preview;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class ScopeUi {
    interface ProfileSelection {
        void pick(String id);
    }

    static final int BASE = 0xff070b12;
    static final int TEXT = 0xffffffff;
    static final int MUTED = 0xffc2cbd9;
    static final int ACCENT = 0xff7dd3fc;
    static final int GLASS = 0x24ffffff;
    static final int EDGE = 0x43ffffff;
    static final int INK = 0xff06202e;
    static final int RED = 0xffff453a;

    private final Context context;

    ScopeUi(Context context) {
        this.context = context;
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    Drawable pill(int fill, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x35ffffff),
                shape(fill, radius, stroke), shape(0xffffffff, radius, 0));
    }

    TextView label(CharSequence text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    Button button(String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(pill(GLASS, 24, EDGE));
        return button;
    }

    Drawable avatar(String name) {
        String initial = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(Canvas canvas) {
                float x = getBounds().exactCenterX();
                float y = getBounds().exactCenterY();
                float radius = getBounds().width() / 2f;
                paint.setShader(new LinearGradient(x - radius, y - radius, x + radius, y + radius,
                        ACCENT, 0xff818cf8, Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, radius, paint);
                paint.setShader(null);
                paint.setColor(INK);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(radius);
                canvas.drawText(initial, x, y - (paint.ascent() + paint.descent()) / 2f, paint);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    Drawable symbol(String kind, int battery) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                paint.setColor(TEXT);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                if ("battery".equals(kind)) {
                    canvas.drawRoundRect(1, 6, 20, 18, 3, 3, paint);
                    canvas.drawLine(23, 11, 23, 13, paint);
                    if (battery > 0) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(battery <= 20 ? 0xffffa66c : 0xff6ee7a0);
                        canvas.drawRoundRect(4, 9, 4 + 13f * battery / 100, 15, 1, 1, paint);
                    }
                } else if ("wifi".equals(kind)) {
                    canvas.drawArc(-2, 6, 26, 31, 227, 86, false, paint);
                    canvas.drawArc(3, 11, 21, 27, 222, 96, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12, 18, 1.3f, paint);
                } else {
                    canvas.drawRoundRect(2.5f, 4, 21.5f, 20, 3, 3, paint);
                    canvas.drawCircle(8.5f, 9, 1.35f, paint);
                    path.reset();
                    path.moveTo(3, 17);
                    path.lineTo(8, 13);
                    path.lineTo(11, 16);
                    path.lineTo(16.5f, 10.5f);
                    path.lineTo(21, 15);
                    canvas.drawPath(path, paint);
                }
                canvas.restore();
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    LinearLayout page() {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(backdrop());
        return page;
    }

    void fit(Activity activity, View page, int side, int vertical) {
        activity.getWindow().setStatusBarColor(BASE);
        activity.getWindow().setNavigationBarColor(BASE);
        page.setPadding(dp(side), dp(vertical), dp(side), dp(vertical));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            }
            view.setPadding(left + dp(side), top + dp(vertical), right + dp(side), bottom + dp(vertical));
            return insets;
        });
    }

    void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(gap);
        parent.addView(child, size);
    }

    void showProfiles(CareProfiles profiles, String selected, ProfileSelection selection,
                      Runnable addProfile, Runnable editProfile) {
        Dialog sheet = new Dialog(context);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(18), dp(16), dp(16));
        panel.setBackground(shape(0xff202b43, 32, EDGE));
        TextView title = label(context.getString(R.string.profiles), 19, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(title);
        add(panel, label(context.getString(R.string.profiles_hint), 13, MUTED), 4);
        ScrollView scroll = new ScrollView(context);
        LinearLayout entries = new LinearLayout(context);
        entries.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entries);
        int height = Math.min(dp(60) * profiles.ids().size(),
                context.getResources().getDisplayMetrics().heightPixels / 3);
        LinearLayout.LayoutParams listSize = new LinearLayout.LayoutParams(-1, height);
        listSize.topMargin = dp(12);
        panel.addView(scroll, listSize);
        for (String id : profiles.ids()) {
            boolean active = id.equals(selected);
            String name = profiles.name(id);
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), 0, dp(10), 0);
            row.setBackground(pill(active ? 0x397dd3fc : 0, 22, 0));
            TextView avatar = label(name.substring(0, name.offsetByCodePoints(0, 1)), 16, INK);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(shape(ACCENT, 20, 0));
            row.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
            TextView person = label(name, 16, TEXT);
            person.setSingleLine(true);
            person.setEllipsize(TextUtils.TruncateAt.END);
            person.setPadding(dp(12), 0, dp(6), 0);
            row.addView(person, new LinearLayout.LayoutParams(0, -2, 1));
            if (active) row.addView(label(context.getString(R.string.profile_active), 12, ACCENT));
            row.setContentDescription(name + (active ? ", " + context.getString(R.string.profile_active) : ""));
            row.setOnClickListener(view -> {
                selection.pick(id);
                sheet.dismiss();
            });
            entries.addView(row, new LinearLayout.LayoutParams(-1, dp(60)));
        }
        if (addProfile != null) {
            Button add = button(context.getString(R.string.profile_add_person));
            add.setOnClickListener(view -> { sheet.dismiss(); addProfile.run(); });
            add(panel, add, 10);
            Button edit = button(context.getString(R.string.profile_edit_current));
            edit.setOnClickListener(view -> { sheet.dismiss(); editProfile.run(); });
            add(panel, edit, 8);
        }
        sheet.setContentView(panel);
        sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sheet.getWindow().setGravity(Gravity.BOTTOM);
        sheet.show();
        sheet.getWindow().setLayout(-1, -2);
    }

    Drawable backdrop() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private Shader[] lights;

            @Override
            protected void onBoundsChange(android.graphics.Rect bounds) {
                float w = bounds.width();
                float h = bounds.height();
                if (w == 0 || h == 0) return;
                float radius = Math.max(w, h) * .55f;
                lights = new Shader[] {
                        new RadialGradient(w * .15f, h * .08f, radius,
                                0xff1d4e89, 0x001d4e89, Shader.TileMode.CLAMP),
                        new RadialGradient(w * .90f, h * .55f, radius,
                                0xff5b2a86, 0x005b2a86, Shader.TileMode.CLAMP),
                        new RadialGradient(w * .20f, h, radius,
                                0xff0f6b6b, 0x000f6b6b, Shader.TileMode.CLAMP)
                };
            }

            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                float w = getBounds().width();
                float h = getBounds().height();
                paint.setShader(null);
                paint.setColor(BASE);
                canvas.drawRect(0, 0, w, h, paint);
                if (lights != null) {
                    for (int i = lights.length - 1; i >= 0; i--) {
                        paint.setShader(lights[i]);
                        canvas.drawRect(0, 0, w, h, paint);
                    }
                }
                canvas.restore();
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }
}
