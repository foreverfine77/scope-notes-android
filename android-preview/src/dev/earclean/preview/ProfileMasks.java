package dev.earclean.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class ProfileMasks {
    static File file(Context context, String owner) {
        if (!CareProfiles.DEFAULT.equals(owner) && !CareProfiles.isPersonalId(owner))
            throw new IllegalArgumentException("Invalid profile");
        return new File(new File(context.getFilesDir(), "profile-masks"), owner + ".png");
    }

    static Bitmap load(Context context, String owner) {
        Bitmap image = null;
        try (java.io.FileInputStream input = new AtomicFile(file(context, owner)).openRead()) {
            image = BitmapFactory.decodeStream(input);
        } catch (IOException | RuntimeException ignored) {
            // A missing or damaged personal cover falls back to the bundled image.
        }
        return image != null ? image : BitmapFactory.decodeResource(context.getResources(), R.drawable.cover_default);
    }

    static void save(Context context, String owner, Bitmap image) throws IOException {
        File target = file(context, owner);
        File folder = target.getParentFile();
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create mask directory");
        AtomicFile file = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IOException("Cannot encode mask");
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    static void reset(Context context, String owner) {
        new AtomicFile(file(context, owner)).delete();
    }
}
