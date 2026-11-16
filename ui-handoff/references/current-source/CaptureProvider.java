package dev.earclean.preview;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CaptureProvider extends ContentProvider {
    private static final String AUTHORITY = "dev.earclean.preview.captures";

    static File directory(Context context, String profile) {
        File root = new File(context.getFilesDir(), "captures");
        if (CareProfiles.DEFAULT.equals(profile)) return root;
        if (!CareProfiles.isPersonalId(profile)) throw new IllegalArgumentException("Invalid profile");
        return new File(root, profile);
    }

    static boolean isCapture(String name) {
        return name.matches("(photo|snapshot|video)-[0-9-]+\\.(jpg|mp4)"
                + "|(macro|marked-photo|marked-macro)-[0-9-]+\\.jpg|macro-video-[0-9-]+\\.mp4");
    }

    static boolean isMacro(File file) {
        return file.getName().startsWith("macro-") || file.getName().startsWith("marked-macro-");
    }

    static File newFile(Context context, String profile, String kind, String extension) {
        File directory = directory(context, profile);
        directory.mkdirs();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
        return new File(directory, kind + "-" + timestamp + "." + extension);
    }

    static Uri uri(File file) {
        Uri.Builder uri = new Uri.Builder().scheme("content").authority(AUTHORITY);
        String folder = file.getParentFile().getName();
        if (CareProfiles.isPersonalId(folder)) uri.appendPath(folder);
        return uri.appendPath(file.getName()).build();
    }

    static String mime(File file) {
        return file.getName().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
    }

    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        int parts = uri.getPathSegments().size();
        if (!AUTHORITY.equals(uri.getAuthority()) || (parts != 1 && parts != 2)
                || name == null || !isCapture(name)) throw new IllegalArgumentException("Invalid capture");
        String profile = parts == 1 ? CareProfiles.DEFAULT : uri.getPathSegments().get(0);
        if (parts == 2 && !CareProfiles.isPersonalId(profile)) {
            throw new IllegalArgumentException("Invalid profile");
        }
        return new File(directory(getContext(), profile), name);
    }

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return mime(file(uri)); }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File capture = file(uri);
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        if (capture.isFile()) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : columns) {
                if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(capture.getName());
                else if (OpenableColumns.SIZE.equals(column)) row.add(capture.length());
                else row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only capture");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
