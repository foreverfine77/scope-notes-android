package dev.earclean.preview;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

    int BASE = 0xff031a2d;
    int TEXT = 0xffffffff;
    int MUTED = 0xffc1d6e5;
    int ACCENT = 0xff69d9ff;
    int GLASS = 0x2dffffff;
    int EDGE = 0x65d4ecff;
    int INK = 0xff06202e;
    int RED = 0xffff453a;
    int RECORD = 0xffff453a;

    boolean light;
    boolean orange;
    boolean cyan;
    static final String[] THEMES = {"scope-dark", "warm-paper", "orange-outline", "fresh-cyan"};

    int SURFACE = 0xf00b253c;
    int SOFT = 0x337dd3fc;
    int VIEWPORT = 0xff0a0f16;
    int MAT = 0x197dd3fc;
    int WHITE = 0xffffffff;
    int SHUTTER = 0xffffffff;
    private Bitmap oceanBackdrop;

    static String selectedTheme(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getString("ui_theme", "scope-dark");
    }

    void load() {
        String selected = selectedTheme(context);
        orange = "orange-outline".equals(selected);
        cyan = "fresh-cyan".equals(selected);
        light = orange || cyan || "warm-paper".equals(selected);
        if (!light) return;
        if (orange) {
            BASE = 0xfff6f0e3;
            TEXT = 0xff3a2414;
            MUTED = 0xff6f5a47;
            GLASS = SURFACE = 0xfffffdf7;
            EDGE = TEXT;
            ACCENT = 0xfff7b733;
            INK = TEXT;
            SOFT = 0xfffde7b8;
            RED = 0xffc2412d;
            VIEWPORT = 0xff2a1b12;
            MAT = 0xfff6a04d;
            WHITE = SURFACE;
            SHUTTER = 0xfff6a04d;
        } else if (cyan) {
            BASE = 0xfff3f9fa;
            TEXT = 0xff14212e;
            MUTED = 0xff55697a;
            GLASS = SURFACE = 0xffffffff;
            EDGE = 0xffd3e4ea;
            ACCENT = 0xff1e2a4a;
            INK = 0xffffffff;
            SOFT = 0xffd8f2f8;
            RED = 0xffc7303f;
            VIEWPORT = 0xff0b1f2a;
            MAT = 0xffffffff;
            WHITE = 0xffffffff;
            SHUTTER = 0xff12b6d6;
        } else {
            BASE = 0xffefebdd;
            TEXT = 0xff23211c;
            MUTED = 0xff6b665a;
            GLASS = SURFACE = 0xfffbf9f2;
            EDGE = 0xffd5cebb;
            ACCENT = 0xffa8613a;
            INK = 0xffffffff;
            SOFT = 0xfff1ddcf;
            RED = 0xffa83232;
            VIEWPORT = 0xff1e1d1b;
            MAT = 0xffe8e2d0;
            WHITE = SURFACE;
            SHUTTER = WHITE;
        }
        RECORD = cyan ? 0xffff7a6b : RED;
    }

    int surfaceColor(int color) {
        if (!light) return color == 0xff202b43 ? SURFACE : color;
        switch (color) {
            case 0xff202b43: return SURFACE;
            case 0xdd242333:
            case 0xee242333:
            case 0x66070b12:
            case 0x54776d95:
            case 0xb5070b12:
            case 0xd31b2132:
            case 0x16ffffff: return SURFACE;
            case 0x337dd3fc:
            case 0x397dd3fc:
            case 0xb3152635:
            case 0x48ffffff: return SOFT;
            case 0x487dd3fc: return ACCENT;
            case 0x58ff453a: return SOFT;
            case 0xff0a0f16: return VIEWPORT;
            default: return color;
        }
    }

    private final Context context;

    ScopeUi(Context context) {
        this.context = context;
    }

    int themeLabel() {
        String id = selectedTheme(context);
        switch (id) {
            case "warm-paper": return R.string.ui_warm;
            case "orange-outline": return R.string.ui_orange;
            case "fresh-cyan": return R.string.ui_cyan;
            default: return R.string.ui_dark;
        }
    }

    int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(surfaceColor(fill));
        shape.setCornerRadius(dp(radius));
        int mapped = surfaceColor(fill);
        if (orange && mapped != 0 && (stroke != 0 || mapped == SURFACE || mapped == SOFT || mapped == ACCENT))
            shape.setStroke(dp(2), TEXT);
        else if (stroke != 0 && (!light || stroke == ACCENT || stroke == TEXT))
            shape.setStroke(dp(1), stroke);
        return shape;
    }

    Drawable pill(int fill, int radius, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(light ? 0x2223211c : 0x35ffffff),
                shape(fill, radius, stroke), shape(0xffffffff, radius, 0));
    }

    TextView label(CharSequence text, int size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize((orange || cyan) && size > 22 ? 22 : size);
        view.setTextColor(color);
        return view;
    }

    Button button(String text) {
        Button button = new Button(context) {
            @Override public boolean performClick() {
                if (isEnabled()) AppFeedback.get(context).play(this, "click");
                return super.performClick();
            }
            @Override
            protected void drawableStateChanged() {
                super.drawableStateChanged();
                setAlpha(isEnabled() ? 1f : .4f);
            }
        };
        button.setSoundEffectsEnabled(false);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(pill(GLASS, 24, EDGE));
        if (light && !orange && !cyan) button.setElevation(dp(2));
        return button;
    }

    void captureFeedback(View view) { view.setSoundEffectsEnabled(false); }

    void decorateWindow(android.view.Window window) {
        window.setStatusBarColor(BASE);
        window.setNavigationBarColor(BASE);
        int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (light && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(flags);
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
        button.setCompoundDrawables(null, null, null, null);
        centerIcon(button, icon);
        button.setCompoundDrawablePadding(0);
        button.setGravity(Gravity.CENTER);
        button.setPadding(0, 0, 0, 0);
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
        centerIcon(button, swatch);
    }

    private void centerIcon(Button button, Drawable glyph) {
        button.setForeground(new Drawable() {
            @Override public void draw(Canvas canvas) {
                int half = dp(12), x = getBounds().centerX(), y = getBounds().centerY();
                glyph.setBounds(x-half, y-half, x+half, y+half);
                glyph.draw(canvas);
            }
            @Override public void setAlpha(int alpha) { glyph.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { glyph.setColorFilter(filter); }
            @Override public void setTint(int color) { glyph.setTint(color); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
    }

    void selectIcon(Button button, boolean selected) {
        button.setSelected(selected);
        if (light) {
            button.setElevation(selected || orange || cyan ? 0 : dp(2));
            Drawable icon = button.getForeground();
            if (icon == null) icon = button.getCompoundDrawables()[0];
            if (icon != null) icon.setTint(selected ? (cyan ? INK : orange ? TEXT : ACCENT) : TEXT);
        }
        button.setBackground(pill(selected ? (cyan ? ACCENT : SOFT) : GLASS, 24, selected ? ACCENT : EDGE));
    }

    Drawable avatar(String name) {
        String initial = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1));
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(Canvas canvas) {
                float x = getBounds().exactCenterX();
                float y = getBounds().exactCenterY();
                float radius = getBounds().width() / 2f;
                if (light) paint.setColor(ACCENT);
                else paint.setShader(new LinearGradient(x - radius, y - radius, x + radius, y + radius,
                        0xffa3def9, 0xff4d91b8, Shader.TileMode.CLAMP));
                canvas.drawCircle(x, y, radius, paint);
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, radius * .055f));
                paint.setColor(light ? EDGE : 0xffc0eaff);
                canvas.drawCircle(x, y, radius - paint.getStrokeWidth()/2, paint);
                paint.setStyle(Paint.Style.FILL);
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
                paint.setStrokeWidth(orange || cyan ? 2f : 1.6f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                if ("settings".equals(kind)) {
                    canvas.drawLine(3, 6, 21, 6, paint);
                    canvas.drawLine(3, 12, 21, 12, paint);
                    canvas.drawLine(3, 18, 21, 18, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(surfaceColor(SURFACE));
                    canvas.drawCircle(8, 6, 2.6f, paint);
                    canvas.drawCircle(16, 12, 2.6f, paint);
                    canvas.drawCircle(9, 18, 2.6f, paint);
                    paint.setStyle(Paint.Style.STROKE); paint.setColor(TEXT);
                    canvas.drawCircle(8, 6, 2.6f, paint);
                    canvas.drawCircle(16, 12, 2.6f, paint);
                    canvas.drawCircle(9, 18, 2.6f, paint);
                } else if ("alarm".equals(kind)) {
                    canvas.drawCircle(12, 13, 7, paint);
                    canvas.drawLine(12, 9, 12, 13, paint);
                    canvas.drawLine(12, 13, 15, 15, paint);
                    canvas.drawLine(4, 4, 7, 2, paint);
                    canvas.drawLine(17, 2, 20, 4, paint);
                    canvas.drawLine(7, 19, 5, 22, paint);
                    canvas.drawLine(17, 19, 19, 22, paint);
                } else if ("back".equals(kind)) {
                    canvas.drawLine(15.5f, 5, 8.5f, 12, paint);
                    canvas.drawLine(8.5f, 12, 15.5f, 19, paint);
                } else if ("drag".equals(kind)) {
                    canvas.drawLine(4, 6, 20, 6, paint);
                    canvas.drawLine(4, 12, 20, 12, paint);
                    canvas.drawLine(4, 18, 20, 18, paint);
                } else if ("battery".equals(kind)) {
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
                } else if ("inspect".equals(kind)) {
                    canvas.drawLine(3, 8, 3, 3, paint);
                    canvas.drawLine(3, 3, 8, 3, paint);
                    canvas.drawLine(16, 3, 21, 3, paint);
                    canvas.drawLine(21, 3, 21, 8, paint);
                    canvas.drawLine(21, 16, 21, 21, paint);
                    canvas.drawLine(21, 21, 16, 21, paint);
                    canvas.drawLine(8, 21, 3, 21, paint);
                    canvas.drawLine(3, 21, 3, 16, paint);
                    canvas.drawCircle(12, 12, 2, paint);
                } else if (light && "stabilize".equals(kind)) {
                    canvas.drawCircle(12, 12, 9, paint);
                    canvas.drawLine(12, 18, 12, 6, paint);
                    canvas.drawLine(8, 10, 12, 6, paint);
                    canvas.drawLine(12, 6, 16, 10, paint);
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
            @Override public void setColorFilter(ColorFilter filter) {
                paint.setColorFilter(filter);
                invalidateSelf();
            }
            @Override public void setTint(int color) {
                setColorFilter(new android.graphics.PorterDuffColorFilter(
                        color, android.graphics.PorterDuff.Mode.SRC_IN));
            }
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
        int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (light && Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
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
            android.widget.ImageView avatarView = new android.widget.ImageView(context);
            avatarView.setImageDrawable(avatar(name));
            avatarView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(avatarView, new LinearLayout.LayoutParams(dp(40), dp(40)));
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
            if (editProfile != null) {
                Button edit = button(context.getString(R.string.profile_edit_current));
                edit.setOnClickListener(view -> { sheet.dismiss(); editProfile.run(); });
                add(panel, edit, 8);
            }
        }
        sheet.setContentView(panel);
        sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sheet.getWindow().setGravity(Gravity.BOTTOM);
        sheet.show();
        sheet.getWindow().setLayout(-1, -2);
    }

    Drawable captureBackdrop(View controls) {
        if (!cyan) return backdrop();
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path panel = new Path();
            @Override public void draw(Canvas canvas) {
                paint.setColor(0xff12b6d6);
                canvas.drawRect(getBounds(), paint);
                paint.setColor(SURFACE);
                // Draw a single full-width sheet; child padding cannot create square steps.
                float radius = dp(32);
                panel.rewind();
                panel.addRoundRect(new android.graphics.RectF(getBounds().left, controls.getTop(),
                        getBounds().right, getBounds().bottom),
                        new float[]{radius, radius, radius, radius, 0, 0, 0, 0}, Path.Direction.CW);
                canvas.drawPath(panel, paint);
            }
            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    Drawable backdrop() {
        if (light) return new android.graphics.drawable.ColorDrawable(BASE);
        if (oceanBackdrop == null)
            oceanBackdrop = BitmapFactory.decodeResource(context.getResources(), R.drawable.deep_ocean);
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Bitmap ocean = oceanBackdrop;
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
                if (ocean != null) {
                    float scale = Math.max(getBounds().width() / (float) ocean.getWidth(),
                            getBounds().height() / (float) ocean.getHeight());
                    float w = ocean.getWidth() * scale, h = ocean.getHeight() * scale;
                    float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
                    paint.setFilterBitmap(true);
                    canvas.drawBitmap(ocean, null, new android.graphics.RectF(x-w/2, y-h/2, x+w/2, y+h/2), paint);
                    return;
                }
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
