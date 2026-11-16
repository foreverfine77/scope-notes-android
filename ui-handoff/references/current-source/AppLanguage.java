package dev.earclean.preview;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

final class AppLanguage {
    static final String[] TAGS = {"", "zh-CN", "zh-TW", "en"};

    private AppLanguage() {
    }

    static String selected(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getString("language", "");
    }

    static void select(Context context, String tag) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .edit().putString("language", tag).apply();
    }

    static Context apply(Context context) {
        String tag = selected(context);
        if (tag.isEmpty()) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(tag));
        return context.createConfigurationContext(configuration);
    }
}
