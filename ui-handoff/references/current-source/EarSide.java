package dev.earclean.preview;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

enum EarSide {
    NONE("none", R.string.ear_unspecified),
    LEFT("left", R.string.ear_left),
    RIGHT("right", R.string.ear_right);

    private final String id;
    private final int label;

    EarSide(String id, int label) {
        this.id = id;
        this.label = label;
    }

    String label(Context context) {
        return context.getString(label);
    }

    static String[] labels(Context context) {
        EarSide[] sides = values();
        String[] labels = new String[sides.length];
        for (int i = 0; i < sides.length; i++) labels[i] = sides[i].label(context);
        return labels;
    }

    static EarSide read(Context context, File file) {
        String value = preferences(context).getString(key(file), NONE.id);
        for (EarSide side : values()) {
            if (side.id.equals(value)) return side;
        }
        return NONE;
    }

    void save(Context context, File file) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (this == NONE) editor.remove(key(file));
        else editor.putString(key(file), id);
        editor.apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("photo-ear-sides", Context.MODE_PRIVATE);
    }

    private static String key(File file) {
        return file.getParentFile().getName() + "/" + file.getName();
    }
}
