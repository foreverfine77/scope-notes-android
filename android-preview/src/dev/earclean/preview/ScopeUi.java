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
        Button button = new Button(context) {
            @Override
            protected void drawableStateChanged() {
                super.drawableStateChanged();
                setAlpha(isEnabled() ? 1f : .4f);
            }
        };
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

    Button iconButton(String kind, String label) {
        Button button = button("");
        setIcon(button, kind, label);
        return button;
    }

    void setIcon(Button button, String kind, String label) {
        Drawable icon = symbol(kind, 0);
        icon.setBounds(0, 0, dp(24), dp(24));
        button.setText("");
        button.setCompoundDrawables(icon, null, null, null);
        button.setCompoundDrawablePadding(0);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        button.setContentDescription(label);
        button.setAlpha(button.isEnabled() ? 1f : .4f);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(label);
    }

    void setColorIcon(Button button, int color, String label) {
        setIcon(button, "color", label);
        Drawable swatch = symbol("color", color);
        swatch.setBounds(0, 0, dp(24), dp(24));
        button.setCompoundDrawables(swatch, null, null, null);
    }

    void selectIcon(Button button, boolean selected) {
        button.setSelected(selected);
        button.setBackground(pill(selected ? 0x337dd3fc : GLASS, 24, selected ? ACCENT : EDGE));
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
                } else if ("device".equals(kind)) {
                    canvas.save();
                    canvas.rotate(22, 10, 12);
                    canvas.drawRoundRect(6, 9, 12, 22, 2, 2, paint);
                    canvas.drawLine(7, 9, 8, 6, paint);
                    canvas.drawLine(11, 9, 10, 6, paint);
                    canvas.drawLine(9, 6, 9, 1, paint);
                    canvas.drawLine(8, 1, 9, .5f, paint);
                    canvas.drawLine(9, 16, 9, 18, paint);
                    canvas.restore();
                    canvas.drawCircle(18, 7, 3, paint);
                    canvas.drawLine(20.2f, 9.2f, 23, 12, paint);
                } else if ("share".equals(kind)) {
                    canvas.drawCircle(5, 12, 2.5f, paint);
                    canvas.drawCircle(19, 4, 2.5f, paint);
                    canvas.drawCircle(19, 20, 2.5f, paint);
                    canvas.drawLine(7.2f, 10.8f, 16.8f, 5.2f, paint);
                    canvas.drawLine(7.2f, 13.2f, 16.8f, 18.8f, paint);
                } else if ("expand".equals(kind) || "collapse".equals(kind)) {
                    boolean expand = "expand".equals(kind);
                    float a = expand ? 4 : 10;
                    float b = expand ? 10 : 4;
                    canvas.drawLine(a, a, b, b, paint);
                    canvas.drawLine(a, a, a, a + (expand ? 5 : -5), paint);
                    canvas.drawLine(a, a, a + (expand ? 5 : -5), a, paint);
                    canvas.drawLine(24 - a, 24 - a, 24 - b, 24 - b, paint);
                    canvas.drawLine(24 - a, 24 - a, 24 - a, 24 - a - (expand ? 5 : -5), paint);
                    canvas.drawLine(24 - a, 24 - a, 24 - a - (expand ? 5 : -5), 24 - a, paint);
                } else if ("circle".equals(kind)) {
                    canvas.drawOval(3, 5, 21, 19, paint);
                } else if ("arrow".equals(kind)) {
                    canvas.drawLine(4, 20, 20, 4, paint);
                    canvas.drawLine(12, 4, 20, 4, paint);
                    canvas.drawLine(20, 4, 20, 12, paint);
                } else if ("undo".equals(kind) || "redo".equals(kind)) {
                    if ("redo".equals(kind)) canvas.scale(-1, 1, 12, 12);
                    canvas.drawLine(4, 8, 10, 3, paint);
                    canvas.drawLine(4, 8, 10, 13, paint);
                    path.reset();
                    path.moveTo(4, 8);
                    path.lineTo(14, 8);
                    path.cubicTo(23, 8, 23, 21, 13, 21);
                    canvas.drawPath(path, paint);
                } else if ("cancel".equals(kind)) {
                    canvas.drawLine(6, 6, 18, 18, paint);
                    canvas.drawLine(18, 6, 6, 18, paint);
                } else if ("save".equals(kind)) {
                    canvas.drawRoundRect(3, 3, 21, 21, 2, 2, paint);
                    canvas.drawRect(7, 3, 16, 9, paint);
                    canvas.drawRect(7, 15, 17, 21, paint);
                } else if ("markup".equals(kind)) {
                    path.reset();
                    path.moveTo(4, 15);
                    path.lineTo(15, 4);
                    path.lineTo(20, 9);
                    path.lineTo(9, 20);
                    path.lineTo(3, 21);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawLine(12, 7, 17, 12, paint);
                } else if ("delete".equals(kind)) {
                    canvas.drawLine(3, 6, 21, 6, paint);
                    canvas.drawLine(9, 3, 15, 3, paint);
                    canvas.drawRoundRect(6, 6, 18, 22, 2, 2, paint);
                    canvas.drawLine(10, 10, 10, 18, paint);
                    canvas.drawLine(14, 10, 14, 18, paint);
                } else if ("pause".equals(kind)) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(6, 4, 10, 20, 1, 1, paint);
                    canvas.drawRoundRect(14, 4, 18, 20, 1, 1, paint);
                } else if ("play".equals(kind)) {
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(7, 3);
                    path.lineTo(21, 12);
                    path.lineTo(7, 21);
                    path.close();
                    canvas.drawPath(path, paint);
                } else if ("stabilize".equals(kind)) {
                    canvas.drawLine(3, 9, 3, 3, paint);
                    canvas.drawLine(3, 3, 9, 3, paint);
                    canvas.drawLine(15, 3, 21, 3, paint);
                    canvas.drawLine(21, 3, 21, 9, paint);
                    canvas.drawLine(21, 15, 21, 21, paint);
                    canvas.drawLine(21, 21, 15, 21, paint);
                    canvas.drawLine(9, 21, 3, 21, paint);
                    canvas.drawLine(3, 21, 3, 15, paint);
                    canvas.drawLine(7, 12, 17, 12, paint);
                    canvas.drawCircle(12, 12, 2, paint);
                } else if ("mirror".equals(kind)) {
                    canvas.drawLine(12, 2, 12, 6, paint);
                    canvas.drawLine(12, 9, 12, 15, paint);
                    canvas.drawLine(12, 18, 12, 22, paint);
                    path.reset();
                    path.moveTo(2, 7);
                    path.lineTo(8, 4);
                    path.lineTo(8, 20);
                    path.lineTo(2, 17);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.save();
                    canvas.scale(-1, 1, 12, 12);
                    canvas.drawPath(path, paint);
                    canvas.restore();
                } else if ("tag".equals(kind) || "tag-left".equals(kind) || "tag-right".equals(kind)) {
                    path.reset();
                    path.moveTo(3, 3);
                    path.lineTo(14, 3);
                    path.lineTo(22, 12);
                    path.lineTo(12, 22);
                    path.lineTo(3, 13);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawCircle(7, 7, 1.3f, paint);
                    if (!"tag".equals(kind)) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setTypeface(Typeface.DEFAULT_BOLD);
                        paint.setTextAlign(Paint.Align.CENTER);
                        paint.setTextSize(9);
                        canvas.drawText("tag-left".equals(kind) ? "L" : "R", 13, 15.5f, paint);
                    }
                } else if ("connect".equals(kind) || "disconnect".equals(kind)) {
                    canvas.drawArc(2, 3, 14, 15, 100, 260, false, paint);
                    canvas.drawArc(10, 9, 22, 21, -80, 260, false, paint);
                    if ("disconnect".equals(kind)) {
                        paint.setColor(RED);
                        canvas.drawLine(4, 21, 21, 4, paint);
                    } else {
                        canvas.drawLine(8, 16, 16, 8, paint);
                    }
                } else if ("stop".equals(kind)) {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(6, 6, 18, 18, 2, 2, paint);
                } else if ("filter".equals(kind) || "filter-active".equals(kind)) {
                    canvas.drawLine(3, 6, 21, 6, paint);
                    canvas.drawLine(6, 12, 18, 12, paint);
                    canvas.drawLine(10, 18, 14, 18, paint);
                    if ("filter-active".equals(kind)) {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(ACCENT);
                        canvas.drawCircle(21, 3, 2.5f, paint);
                    }
                } else if ("color".equals(kind)) {
                    paint.setColor(battery);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12, 12, 8, paint);
                    paint.setColor(TEXT);
                    paint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(12, 12, 9, paint);
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
