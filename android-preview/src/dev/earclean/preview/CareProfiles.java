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
        List<String> available = ids();
        return available.contains(id) ? id : available.get(0);
    }

    String name(String id) {
        return preferences.getString(NAME_PREFIX + id, context.getString(R.string.profile_self));
    }

    synchronized List<String> ids() {
        List<String> ids = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(NAME_PREFIX)) continue;
            String id = key.substring(NAME_PREFIX.length());
            if (isPersonalId(id)) ids.add(id);
        }
        ids.sort((left, right) -> name(left).compareToIgnoreCase(name(right)));
        if (!preferences.getBoolean("removed.default", false)) ids.add(0, DEFAULT);
        String[] order = preferences.getString("order", "").split(",");
        List<String> ordered = new ArrayList<>();
        for (String id : order) if (ids.remove(id)) ordered.add(id);
        ordered.addAll(ids);
        return ordered;
    }

    void select(String id) {
        preferences.edit().putString("current", id).apply();
    }

    synchronized String save(String id, String name) {
        if (id == null) id = UUID.randomUUID().toString();
        preferences.edit().putString(NAME_PREFIX + id, name).putString("current", id).apply();
        return id;
    }

    synchronized void move(String id, String before, boolean after) {
        List<String> order = ids();
        if (id.equals(before) || !order.remove(id)) return;
        int index = order.indexOf(before);
        if (index < 0) return;
        order.add(index + (after ? 1 : 0), id);
        preferences.edit().putString("order", android.text.TextUtils.join(",", order)).apply();
    }

    int captureCount(String id) {
        java.io.File[] files = CaptureProvider.directory(context, id).listFiles(
                file -> file.isFile() && CaptureProvider.isCapture(file.getName()));
        return files == null ? 0 : files.length;
    }

    synchronized boolean remove(String id) {
        List<String> available = ids();
        if (available.size() <= 1 || !available.contains(id)) return false;
        java.io.File directory = CaptureProvider.directory(context, id);
        java.io.File[] files = directory.listFiles();
        if (files == null && directory.exists()) return false;
        if (files != null) {
            for (java.io.File file : files) {
                // The default directory also contains other people's subdirectories.
                if (!file.isFile()) continue;
                if (!CaptureProvider.isCapture(file.getName())) return false;
            }
            for (java.io.File file : files) {
                if (!file.isFile()) continue;
                if (!file.delete() && file.exists()) return false;
                EarSide.NONE.save(context, file);
                context.revokeUriPermission(CaptureProvider.uri(file),
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        ProfileMasks.reset(context, id);
        available.remove(id);
        android.content.SharedPreferences.Editor edit = preferences.edit().remove(NAME_PREFIX + id)
                .putString("order", android.text.TextUtils.join(",", available));
        if (DEFAULT.equals(id)) edit.putBoolean("removed.default", true);
        if (id.equals(current())) edit.putString("current", available.get(0));
        edit.apply();
        return true;
    }

    static boolean isPersonalId(String id) {
        return id != null && id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
}
