package dev.earclean.preview;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/** App-owned dialogs share the current palette, typography and action sizes. */
final class ScopeDialog extends Dialog {
    private final Map<Integer, Button> buttons = new LinkedHashMap<>();

    private ScopeDialog(Builder builder) {
        super(builder.context);
        ScopeUi ui = new ScopeUi(builder.context);
        ui.load();
        LinearLayout panel = new LinearLayout(builder.context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(16));
        panel.setBackground(ui.shape(ui.light ? ui.SURFACE : 0xff202b43, 28, ui.EDGE));
        TextView title = ui.label(builder.title, 18, ui.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        LinearLayout body = new LinearLayout(builder.context);
        body.setOrientation(LinearLayout.VERTICAL);
        if (builder.message != null) {
            TextView message = ui.label(builder.message, 14, ui.TEXT);
            message.setLineSpacing(ui.dp(4), 1);
            ui.add(body, message, 12);
        }
        if (builder.view != null) ui.add(body, builder.view, 12);
        for (int i = 0; builder.items != null && i < builder.items.length; i++) {
            final int item = i;
            Button row = ui.button(builder.items[i] + (builder.checked == i ? "  ✓" : ""));
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setBackground(ui.pill(builder.checked == i ? ui.SOFT : 0, 16, 0));
            row.setTextColor(builder.checked == i ? ui.ACCENT : ui.TEXT);
            row.setElevation(0);
            row.setOnClickListener(v -> {
                if (builder.singleChoice) {
                    for (int j = 0; j < body.getChildCount(); j++) {
                        View child = body.getChildAt(j);
                        if (child instanceof Button) {
                            Button option = (Button) child;
                            int index = (Integer) option.getTag();
                            option.setText(builder.items[index] + (index == item ? "  ✓" : ""));
                            option.setTextColor(index == item ? ui.ACCENT : ui.TEXT);
                            option.setSelected(index == item);
                            option.setBackground(ui.pill(index == item ? ui.SOFT : 0, 16, 0));
                        }
                    }
                    row.setSelected(true);
                }
                builder.itemClick.onClick(this, item);
                if (!builder.singleChoice) dismiss();
            });
            row.setTag(i);
            row.setSelected(builder.checked == i);
            ui.add(body, row, 2);
        }
        ScrollView scroll = new ScrollView(builder.context);
        scroll.addView(body);
        int maximum = builder.context.getResources().getDisplayMetrics().heightPixels * 55 / 100;
        body.measure(View.MeasureSpec.makeMeasureSpec(
                builder.context.getResources().getDisplayMetrics().widthPixels - ui.dp(64),
                View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, Math.min(maximum, body.getMeasuredHeight())));
        LinearLayout actions = new LinearLayout(builder.context);
        actions.setGravity(Gravity.CENTER);
        for (Map.Entry<Integer, Action> entry : builder.actions.entrySet()) {
            Action action = entry.getValue();
            int which = entry.getKey();
            Button button = ui.button(action.text.toString());
            button.setTextSize(14);
            button.setOnClickListener(v -> {
                if (action.click != null) action.click.onClick(this, which);
                dismiss();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ui.dp(48), 1);
            if (actions.getChildCount() > 0) size.leftMargin = ui.dp(8);
            actions.addView(button, size);
            buttons.put(which, button);
        }
        ui.add(panel, actions, 14);
        panel.setElevation(ui.dp(2));
        setContentView(panel);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
    }

    Button getButton(int which) { return buttons.get(which); }

    @Override public void show() {
        super.show();
        ScopeUi ui = new ScopeUi(getContext());
        ui.load();
        ui.decorateWindow(getWindow());
        getWindow().setGravity(Gravity.BOTTOM);
        getWindow().setLayout(getContext().getResources().getDisplayMetrics().widthPixels - 24,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static final class Action {
        final CharSequence text;
        final DialogInterface.OnClickListener click;
        Action(CharSequence text, DialogInterface.OnClickListener click) {
            this.text = text;
            this.click = click;
        }
    }

    static final class Builder {
        private final Context context;
        private CharSequence title = "";
        private CharSequence message;
        private View view;
        private String[] items;
        private int checked = -1;
        private boolean singleChoice;
        private DialogInterface.OnClickListener itemClick;
        private final Map<Integer, Action> actions = new LinkedHashMap<>();

        Builder(Context context) { this.context = context; }
        Builder setTitle(CharSequence text) { title = text; return this; }
        Builder setTitle(int id) { return setTitle(context.getText(id)); }
        Builder setMessage(CharSequence text) { message = text; return this; }
        Builder setMessage(int id) { return setMessage(context.getText(id)); }
        Builder setView(View content) { view = content; return this; }
        Builder setItems(String[] values, DialogInterface.OnClickListener click) {
            items = values; itemClick = click; return this;
        }
        Builder setSingleChoiceItems(String[] values, int active, DialogInterface.OnClickListener click) {
            setItems(values, click); checked = active; singleChoice = true; return this;
        }
        private Builder action(int which, CharSequence text, DialogInterface.OnClickListener click) {
            actions.put(which, new Action(text, click)); return this;
        }
        Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener click) { return action(-1, text, click); }
        Builder setPositiveButton(int id, DialogInterface.OnClickListener click) { return setPositiveButton(context.getText(id), click); }
        Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener click) { return action(-2, text, click); }
        Builder setNegativeButton(int id, DialogInterface.OnClickListener click) { return setNegativeButton(context.getText(id), click); }
        Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener click) { return action(-3, text, click); }
        Builder setNeutralButton(int id, DialogInterface.OnClickListener click) { return setNeutralButton(context.getText(id), click); }
        ScopeDialog create() { return new ScopeDialog(this); }
        ScopeDialog show() { ScopeDialog dialog = create(); dialog.show(); return dialog; }
    }
}
