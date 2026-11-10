package dev.earclean.preview;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class CareProfiles {
    static final String DEFAULT = "default";
    static final String EXTRA = "profile";
    private static final String NAME_PREFIX = "name.";
    private final SharedPreferences preferences;
    private final Context context;

    CareProfiles(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("care_profiles", Context.MODE_PRIVATE);
    }

    String current() {
        return resolve(preferences.getString("current", DEFAULT));
    }

    String resolve(String id) {
        return isPersonalId(id) && preferences.contains(NAME_PREFIX + id) ? id : DEFAULT;
    }

    String name(String id) {
        return preferences.getString(NAME_PREFIX + id, context.getString(R.string.profile_self));
    }

    List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(NAME_PREFIX)) continue;
            String id = key.substring(NAME_PREFIX.length());
            if (isPersonalId(id)) ids.add(id);
        }
        ids.sort((left, right) -> name(left).compareToIgnoreCase(name(right)));
        ids.add(0, DEFAULT);
        return ids;
    }

    void select(String id) {
        preferences.edit().putString("current", id).apply();
    }

    String save(String id, String name) {
        if (id == null) id = UUID.randomUUID().toString();
        preferences.edit().putString(NAME_PREFIX + id, name).putString("current", id).apply();
        return id;
    }

    static boolean isPersonalId(String id) {
        return id != null && id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
}
