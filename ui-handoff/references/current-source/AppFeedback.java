package dev.earclean.preview;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.HashSet;
import java.util.Set;

final class AppFeedback {
    private static AppFeedback instance;
    private final SoundPool sounds;
    private final Set<Integer> loaded = new HashSet<>();
    private final int click, shutter, recordStart, recordStop, swipe;

    private AppFeedback(Context context) {
        sounds = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        sounds.setOnLoadCompleteListener((pool, sound, status) -> {
            if (status == 0) synchronized (loaded) { loaded.add(sound); }
        });
        click = sounds.load(context, R.raw.ui_click, 1);
        shutter = sounds.load(context, R.raw.ui_shutter, 1);
        recordStart = sounds.load(context, R.raw.ui_record_start, 1);
        recordStop = sounds.load(context, R.raw.ui_record_stop, 1);
        swipe = sounds.load(context, R.raw.ui_swipe, 1);
    }

    static synchronized AppFeedback get(Context context) {
        if (instance == null) instance = new AppFeedback(context.getApplicationContext());
        return instance;
    }

    void play(View view, String action) {
        Context context = view.getContext();
        if (context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getBoolean("haptic_feedback", true)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        if (!context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
                .getBoolean("sound_feedback", true)) return;
        int sound;
        switch (action) {
            case "shutter": sound = shutter; break;
            case "record-start": sound = recordStart; break;
            case "record-stop": sound = recordStop; break;
            case "swipe": sound = swipe; break;
            default: sound = click;
        }
        synchronized (loaded) {
            if (loaded.contains(sound)) sounds.play(sound, .75f, .75f, 1, 0, 1);
        }
    }
}
