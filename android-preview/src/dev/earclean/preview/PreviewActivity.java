package dev.earclean.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.Toast;

import java.util.Locale;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class PreviewActivity extends Activity {
    private static final int TEXT = ScopeUi.TEXT;
    private static final int MUTED = ScopeUi.MUTED;
    private static final int ACCENT = ScopeUi.ACCENT;
    private final ScopeUi style = new ScopeUi(this);
    private static final int TEST_SECONDS = 30;
    private static final int WIFI_PERMISSIONS = 20;
    private CameraStream session;
    private EarWifi earWifi;
    private EarWifi.Device selectedEar;
    private boolean previewRequested;
    private String status;
    private TextView batteryStatus;
    private LinearLayout empty;
    private TextView connectionTitle;
    private TextView connectionHint;
    private TextView guide;
    private TextView recordingTime;
    private Button previewButton;
    private Switch stabilizeButton;
    private Button wifiButton;
    private LinearLayout connectionActions;
    private LinearLayout imageOptions;
    private Button earSideButton;
    private EarSide earSide = EarSide.NONE;
    private boolean macro;
    private TextView videoLabel;
    private View videoButton;
    private View recordMark;
    private View shutter;
    private Button disconnect;
    private Button profileButton;
    private CareProfiles profiles;
    private String profile;
    private ImagePreview preview;
    private VideoCapture recorder;
    private boolean finishingVideo;
    private boolean savingPicture;
    private long recordingStarted;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private long testStarted = -1;
    private int testPhase = -1;
    private String lastStatus = "";
    private String savedReport = "";
    private final Runnable testTick = this::updateTest;
    private final Runnable recordingTick = this::updateRecordingTime;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.apply(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        macro = getPreferences(MODE_PRIVATE).getBoolean("macro", false);
        savedReport = getPreferences(MODE_PRIVATE).getString("lastReport", "");
        profiles = new CareProfiles(this);
        profile = profiles.current();
        if (state != null) {
            previewRequested = state.getBoolean("previewRequested");
            selectedEar = EarWifi.Device.read(state.getString("selectedEar", ""));
            if (profile.equals(state.getString("profile"))) {
                earSide = EarSide.values()[state.getInt("earSide", EarSide.NONE.ordinal())];
            }
        }
        earWifi = new EarWifi(this, ui, () -> {
            if (previewRequested) showMessage(getString(R.string.wifi_failed));
        });

        LinearLayout page = style.page();
        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        if (landscape) {
            ScrollView scroll = new ScrollView(this);
            scroll.addView(page);
            setContentView(scroll);
        } else {
            setContentView(page);
        }
        style.fit(this, page, 16, 12);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        profileButton = style.button("");
        profileButton.setSingleLine(true);
        profileButton.setEllipsize(TextUtils.TruncateAt.END);
        profileButton.setPadding(dp(6), 0, dp(10), 0);
        selectProfile(profile);
        profileButton.setOnClickListener(view -> {
            if (previewRequested || finishingVideo || savingPicture) {
                showMessage(getString(R.string.switch_after_stop));
            } else {
                style.showProfiles(profiles, profile, this::selectProfile,
                        () -> editProfile(null), () -> editProfile(profile));
            }
        });
        FrameLayout leading = new FrameLayout(this);
        leading.addView(profileButton, new FrameLayout.LayoutParams(-2, dp(44), Gravity.START));
        header.addView(leading, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button settings = style.button("⚙");
        settings.setPadding(0, 0, 0, 0);
        settings.setTextSize(23);
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(view -> showSettings());
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(dp(44), dp(44));
        settingsSize.setMargins(dp(8), 0, dp(8), 0);
        header.addView(settings, settingsSize);
        batteryStatus = label("", 12, TEXT);
        batteryStatus.setGravity(Gravity.CENTER);
        batteryStatus.setPadding(dp(10), 0, dp(10), 0);
        batteryStatus.setCompoundDrawablePadding(dp(5));
        batteryStatus.setMaxLines(2);
        batteryStatus.setBackground(style.shape(ScopeUi.GLASS, 22, ScopeUi.EDGE));
        FrameLayout trailing = new FrameLayout(this);
        trailing.addView(batteryStatus, new FrameLayout.LayoutParams(-2, dp(44), Gravity.END));
        header.addView(trailing, new LinearLayout.LayoutParams(0, dp(44), 1));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));

        FrameLayout screen = new FrameLayout(this);
        LinearLayout.LayoutParams screenSize = landscape
                ? new LinearLayout.LayoutParams(-1, dp(240))
                : new LinearLayout.LayoutParams(-1, 0, 1);
        screenSize.setMargins(0, dp(18), 0, dp(6));
        page.addView(screen, screenSize);
        preview = new ImagePreview();
        screen.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(8), 0, dp(8), 0);
        View wifiIcon = new View(this);
        wifiIcon.setBackground(style.symbol("wifi", 0));
        empty.addView(wifiIcon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        connectionTitle = label("", 17, TEXT);
        connectionTitle.setGravity(Gravity.CENTER);
        connectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connectionTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        style.add(empty, connectionTitle, 4);
        connectionHint = label("", 13, MUTED);
        connectionHint.setGravity(Gravity.CENTER);
        style.add(empty, connectionHint, 5);
        screen.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        recordingTime = label("", 14, TEXT);
        recordingTime.setBackground(style.shape(0xdd242333, 17, ScopeUi.EDGE));
        recordingTime.setPadding(dp(12), dp(5), dp(12), dp(5));
        recordingTime.setVisibility(View.GONE);
        FrameLayout.LayoutParams timer = new FrameLayout.LayoutParams(-2, -2,
                Gravity.CENTER);
        screen.addView(recordingTime, timer);
        screen.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) ->
                recordingTime.setTranslationY(Math.min(right - left, bottom - top) * .34f));

        connectionActions = new LinearLayout(this);
        connectionActions.setGravity(Gravity.CENTER);
        wifiButton = style.button(getString(R.string.choose_device));
        wifiButton.setOnClickListener(view -> chooseEar(true));
        previewButton = style.button(getString(R.string.connect_device));
        previewButton.setBackground(style.pill(0x487dd3fc, 24, 0x757dd3fc));
        previewButton.setOnClickListener(view -> {
            if (previewRequested) stopRequestedPreview();
            else connectEar();
        });
        connectionActions.addView(wifiButton);
        LinearLayout.LayoutParams connectSize = new LinearLayout.LayoutParams(-2, dp(46));
        connectSize.leftMargin = dp(8);
        connectionActions.addView(previewButton, connectSize);
        page.addView(connectionActions);

        imageOptions = new LinearLayout(this);
        imageOptions.setGravity(Gravity.CENTER);
        stabilizeButton = imageSwitch(getString(R.string.stabilize));
        stabilizeButton.setOnCheckedChangeListener((button, enabled) -> setStabilize(enabled));
        Switch mirror = imageSwitch(getString(R.string.mirror));
        mirror.setOnCheckedChangeListener((button, enabled) -> {
            preview.mirror = enabled;
            preview.invalidate();
        });
        imageOptions.addView(stabilizeButton);
        LinearLayout.LayoutParams mirrorSize = new LinearLayout.LayoutParams(-2, dp(44));
        mirrorSize.leftMargin = dp(12);
        imageOptions.addView(mirror, mirrorSize);
        earSideButton = style.button("");
        setEarSide(earSide);
        earSideButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.ear_choose)
                .setSingleChoiceItems(EarSide.labels(this), earSide.ordinal(), (dialog, which) -> {
                    setEarSide(EarSide.values()[which]);
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show());
        imageOptions.addView(earSideButton);
        page.addView(imageOptions);

        guide = label("", 13, MUTED);
        guide.setGravity(Gravity.CENTER);
        guide.setMinHeight(dp(40));
        style.add(page, guide, 8);

        LinearLayout captureBar = new LinearLayout(this);
        captureBar.setGravity(Gravity.CENTER);
        View gallery = new View(this);
        gallery.setBackground(style.pill(0x54776d95, 16, ScopeUi.EDGE));
        View galleryIcon = new View(this);
        galleryIcon.setBackground(style.symbol("album", 0));
        addCapture(captureBar, gallery, galleryIcon, getString(R.string.album), 56, 80, () -> {
            if (recorder != null || finishingVideo || savingPicture) {
                showMessage(getString(R.string.finish_recording_first));
            } else {
                startActivity(new Intent(this, GalleryActivity.class)
                        .putExtra(CareProfiles.EXTRA, profile));
            }
        });

        shutter = new View(this);
        GradientDrawable shutterRim = style.shape(0x14000000, 42, 0);
        shutterRim.setStroke(dp(3), TEXT);
        shutter.setBackground(shutterRim);
        View shutterCenter = new View(this);
        shutterCenter.setBackground(style.shape(TEXT, 33, 0));
        addCapture(captureBar, shutter, shutterCenter, getString(R.string.take_photo), 80, 104, this::savePicture);

        videoButton = new View(this);
        videoButton.setBackground(style.pill(ScopeUi.GLASS, 32, ScopeUi.EDGE));
        recordMark = new View(this);
        videoLabel = addCapture(captureBar, videoButton, recordMark, getString(R.string.video), 64, 80, () -> {
            if (recorder != null) stopVideo();
            else if (!finishingVideo) startVideo();
        });
        style.add(page, captureBar, 18);
        disconnect = style.button(getString(R.string.stop_preview));
        disconnect.setBackground(style.pill(0, 22, 0));
        disconnect.setTextColor(MUTED);
        disconnect.setTextSize(12);
        disconnect.setOnClickListener(view -> stopRequestedPreview());
        LinearLayout.LayoutParams disconnectSize = new LinearLayout.LayoutParams(-2, dp(44));
        disconnectSize.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(disconnect, disconnectSize);
        updateVideoControl();
        showConnection(getString(R.string.disconnected), getString(R.string.connect_hint));
        if (state == null) showWirelessReminder();
    }

    private Switch imageSwitch(String title) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextColor(TEXT);
        toggle.setTextSize(14);
        toggle.setMinimumHeight(dp(44));
        toggle.setSwitchPadding(dp(8));
        toggle.setPadding(dp(12), 0, dp(10), 0);
        toggle.setBackground(style.pill(ScopeUi.GLASS, 22, ScopeUi.EDGE));
        toggle.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xff38c478, 0xff71819b}));
        toggle.setThumbTintList(ColorStateList.valueOf(TEXT));
        return toggle;
    }

    private TextView addCapture(LinearLayout bar, View control, View symbol, String name,
                                int diameter, int columnWidth, Runnable action) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout ring = new FrameLayout(this);
        FrameLayout.LayoutParams hit = new FrameLayout.LayoutParams(dp(diameter), dp(diameter), Gravity.CENTER);
        ring.addView(control, hit);
        int size = diameter == 80 ? 64 : diameter == 64 ? 34 : 36;
        ring.addView(symbol, new FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER));
        control.setContentDescription(name);
        control.setOnClickListener(view -> action.run());
        column.addView(ring, new LinearLayout.LayoutParams(-1, dp(80)));
        TextView title = label(name, 12, MUTED);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(5), 0, 0);
        column.addView(title);
        bar.addView(column, new LinearLayout.LayoutParams(dp(columnWidth), -2));
        return title;
    }

    private void selectProfile(String id) {
        if (!id.equals(profile) && earSideButton != null) setEarSide(EarSide.NONE);
        profile = id;
        profiles.select(id);
        String name = profiles.name(id);
        profileButton.setText(name + "  ▾");
        profileButton.setContentDescription(getString(R.string.profile_switch, name));
        profileButton.setCompoundDrawablePadding(dp(6));
        setLeadingIcon(profileButton, style.avatar(name), 28);
    }

    private void showConnection(String state, String detail) {
        status = state;
        boolean live = detail == null;
        connectionTitle.setText(state);
        connectionHint.setText(detail);
        empty.setVisibility(live ? View.GONE : View.VISIBLE);
        imageOptions.setVisibility(live ? View.VISIBLE : View.GONE);
        stabilizeButton.setVisibility(macro ? View.GONE : View.VISIBLE);
        earSideButton.setVisibility(macro ? View.GONE : View.VISIBLE);
        connectionActions.setVisibility(live ? View.GONE : View.VISIBLE);
        disconnect.setVisibility(live ? View.VISIBLE : View.INVISIBLE);
        shutter.setEnabled(live);
        ((View) shutter.getParent()).setAlpha(live ? 1f : .45f);
        updateVideoControl();
        if (!live) showBattery("—", 0);
        if (testStarted < 0) guide.setText(live
                ? (macro ? getString(R.string.macro_hint) : getString(R.string.recording_for, profiles.name(profile)))
                : "");
    }

    private void setLeadingIcon(TextView label, Drawable icon, int size) {
        icon.setBounds(0, 0, dp(size), dp(size));
        label.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private void showBattery(String text, int percent) {
        batteryStatus.setText(text);
        batteryStatus.setContentDescription(getString(R.string.battery_description, text));
        setLeadingIcon(batteryStatus, style.symbol("battery", percent), 20);
    }

    private void updateVideoControl() {
        boolean active = recorder != null;
        boolean canRecord = empty.getVisibility() == View.GONE || active;
        ((View) videoButton.getParent()).setVisibility(macro ? View.GONE : View.VISIBLE);
        String text = active ? getString(R.string.stop_recording) : finishingVideo ? getString(R.string.saving) : getString(R.string.video);
        videoButton.setContentDescription(text);
        videoLabel.setText(text);
        videoButton.setEnabled(canRecord && !finishingVideo);
        ((View) videoButton.getParent()).setAlpha(canRecord ? 1f : .45f);
        int size = active ? 24 : 34;
        recordMark.getLayoutParams().width = dp(size);
        recordMark.getLayoutParams().height = dp(size);
        recordMark.requestLayout();
        recordMark.setBackground(style.shape(ScopeUi.RED, active ? 7 : 17, 0));
    }

    private void showSettings() {
        Dialog dialog = new Dialog(this);
        LinearLayout page = style.page();
        page.setPadding(dp(20), dp(16), dp(20), dp(16));
        Button back = style.button("‹");
        back.setContentDescription(getString(R.string.back_to_preview));
        back.setOnClickListener(view -> dialog.dismiss());
        page.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView heading = label(getString(R.string.settings), 30, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        style.add(page, heading, 18);
        settingsGroup(page, new String[]{getString(R.string.language_title), getString(R.string.help), getString(R.string.privacy), getString(R.string.feedback)},
                new Runnable[]{() -> showLanguages(dialog), this::showHelp, this::showPrivacy, this::openFeedback});
        style.add(page, label(getString(R.string.macro_mode), 13, MUTED), 24);
        settingsGroup(page, new String[]{getString(macro ? R.string.macro_on : R.string.macro_off)},
                new Runnable[]{() -> chooseMacroMode(dialog)});
        style.add(page, label(getString(R.string.test_tools), 13, MUTED), 24);
        settingsGroup(page,
                new String[]{testStarted >= 0 ? getString(R.string.test_stop) : getString(R.string.test_label, TEST_SECONDS), getString(R.string.test_copy)},
                new Runnable[]{() -> {
                    dialog.dismiss();
                    if (testStarted >= 0) finishTest(getString(R.string.test_manual_end));
                    else startTest();
                }, this::copyReport});
        TextView version = label(getString(R.string.brand), 12, MUTED);
        version.setGravity(Gravity.CENTER);
        style.add(page, version, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        dialog.setContentView(scroll);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }

    private void chooseMacroMode(Dialog settings) {
        if (recorder != null || finishingVideo || savingPicture) {
            showMessage(getString(R.string.finish_recording_first));
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.macro_mode)
                .setMessage(R.string.macro_explanation)
                .setPositiveButton(macro ? R.string.macro_disable : R.string.macro_enable, (dialog, which) -> {
                    macro = !macro;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("macro", macro).apply();
                    if (session != null) session.setStabilize(!macro && preview.stabilize);
                    preview.resetReference();
                    preview.invalidate();
                    boolean live = empty.getVisibility() == View.GONE;
                    showConnection(status, live ? null : connectionHint.getText().toString());
                    settings.dismiss();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showLanguages(Dialog settings) {
        String[] names = {getString(R.string.language_system), "简体中文", "繁體中文", "English"};
        String active = AppLanguage.selected(this);
        int checked = java.util.Arrays.asList(AppLanguage.TAGS).indexOf(active);
        new AlertDialog.Builder(this).setTitle(R.string.language_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (AppLanguage.TAGS[which].equals(active)) return;
                    if (recorder != null || finishingVideo || savingPicture) {
                        showMessage(getString(R.string.finish_recording_first));
                        return;
                    }
                    AppLanguage.select(this, AppLanguage.TAGS[which]);
                    settings.dismiss();
                    recreate();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void settingsGroup(LinearLayout page, String[] names, Runnable[] actions) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(style.shape(ScopeUi.GLASS, 26, ScopeUi.EDGE));
        group.setPadding(dp(6), dp(4), dp(6), dp(4));
        for (int i = 0; i < names.length; i++) {
            Button row = style.button(names[i] + "  ›");
            row.setContentDescription(names[i]);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setBackground(style.pill(0, 22, 0));
            Runnable action = actions[i];
            row.setOnClickListener(view -> action.run());
            group.addView(row, new LinearLayout.LayoutParams(-1, dp(54)));
        }
        style.add(page, group, 12);
    }

    private void editProfile(String id) {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint(getString(R.string.nickname_hint));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(24)});
        if (id != null) {
            name.setText(profiles.name(id));
            name.setSelection(name.length());
        }
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(22), 0, dp(22), 0);
        form.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(id == null ? getString(R.string.profile_add) : getString(R.string.profile_edit))
                .setView(form)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.save), null)
                .create();
        dialog.setOnShowListener(window -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String nickname = name.getText().toString().trim();
                    if (nickname.isEmpty()) {
                        name.setError(getString(R.string.nickname_required));
                        return;
                    }
                    for (String existing : profiles.ids()) {
                        if (!existing.equals(id) && profiles.name(existing).equals(nickname)) {
                            name.setError(getString(R.string.nickname_duplicate));
                            return;
                        }
                    }
                    selectProfile(profiles.save(id, nickname));
                    dialog.dismiss();
                }));
        name.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        String activeProfile = profiles.current();
        if (!activeProfile.equals(profile)) selectProfile(activeProfile);
        if (previewRequested) startPreview();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("previewRequested", previewRequested);
        if (selectedEar != null) state.putString("selectedEar", selectedEar.key());
        state.putString("profile", profile);
        state.putInt("earSide", earSide.ordinal());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStop() {
        if (testStarted >= 0) finishTest(getString(R.string.test_left));
        stopPreview();
        super.onStop();
    }

    private void startPreview() {
        if (session != null) return;
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        if (selectedEar != null) earWifi.start(selectedEar);
        session = new CameraStream(connectivity, selectedEar == null ? null : earWifi, new CameraStream.Listener() {
            @Override
            public void status(CameraStream source, int statusId) {
                String message = getString(statusId);
                runOnUiThread(() -> {
                    if (session != source) return;
                    if (!message.equals(lastStatus)) {
                        record(message);
                        lastStatus = message;
                    }
                    preview.resetReference();
                    preview.setFrame(null, 0);
                    showConnection(message, getString(R.string.waiting_image));
                    wifiButton.setVisibility(message.contains("Wi-Fi") ? View.VISIBLE : View.GONE);
                });
            }

            @Override
            public void connected(CameraStream source, Network network) {
                runOnUiThread(() -> {
                    if (session == source) earWifi.confirm(network);
                });
            }

            @Override
            public void frame(CameraStream source, Bitmap image, float rotation) {
                runOnUiThread(() -> {
                    if (session != source) return;
                    if (empty.getVisibility() != View.GONE) {
                        showConnection(getString(R.string.live), null);
                        record("进入实时预览");
                    }
                    preview.setFrame(image, rotation);
                    if (recorder != null) recorder.offer(preview.currentImage());
                });
            }

            @Override
            public void detail(CameraStream source, String message) {
                runOnUiThread(() -> {
                    if (session != source || testStarted < 0) return;
                    if (message.startsWith("包=")) {
                        messageWithCorrection(message);
                    } else {
                        record(message);
                    }
                });
            }

            @Override
            public void battery(CameraStream source, BatteryReading reading) {
                runOnUiThread(() -> {
                    if (session != source) return;
                    if (reading == null) {
                        showBattery(getString(R.string.battery_unavailable), 0);
                    } else if (reading.charging) {
                        showBattery(reading.full ? getString(R.string.battery_full) : getString(R.string.battery_charging, reading.percent),
                                reading.percent);
                    } else {
                        showBattery(getString(R.string.battery_estimate, reading.percent, reading.estimatedMinutes()),
                                reading.percent);
                    }
                });
            }
        });
        previewButton.setText(getString(R.string.stop_connecting));
        showConnection(getString(R.string.connecting), getString(R.string.waiting_image));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session.setStabilize(!macro && preview.stabilize);
        session.start();
    }

    private void messageWithCorrection(String message) {
        CaptureImage image = preview.currentImage();
        String correction = image == null ? "—" : String.format(Locale.ROOT, "%.1f°", image.rotation);
        record(message + " 显示补偿=" + correction);
    }

    private void stopRequestedPreview() {
        previewRequested = false;
        stopPreview();
        selectedEar = null;
        if (testStarted >= 0) finishTest(getString(R.string.test_preview_stopped));
    }

    private void stopPreview() {
        record("用户停止预览");
        stopVideo();
        if (session != null) session.stop();
        session = null;
        earWifi.stop();
        preview.resetReference();
        preview.setFrame(null, 0);
        wifiButton.setVisibility(View.VISIBLE);
        previewButton.setText(getString(R.string.connect_device));
        showConnection(getString(R.string.disconnected), getString(R.string.connect_hint));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private boolean hasWifiPermissions() {
        for (String permission : EarWifi.permissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void connectEar() {
        EarWifi.Device last = earWifi.last();
        if (last != null && Build.VERSION.SDK_INT >= 29 && hasWifiPermissions()) {
            previewWith(last);
        } else {
            chooseEar(true);
        }
    }

    private void chooseEar(boolean refresh) {
        if (Build.VERSION.SDK_INT < 29) {
            openWifiSettings();
            return;
        }
        if (!hasWifiPermissions()) {
            new AlertDialog.Builder(this).setTitle(getString(R.string.wifi_discover))
                    .setMessage(getString(R.string.wifi_permissions))
                    .setPositiveButton(getString(R.string.allow_discovery), (dialog, which) ->
                            requestPermissions(EarWifi.permissions(), WIFI_PERMISSIONS))
                    .setNeutralButton(getString(R.string.wifi_settings), (dialog, which) -> openWifiSettings())
                    .setNegativeButton(getString(R.string.cancel), null).show();
            return;
        }
        try {
            if (refresh) {
                earWifi.scan();
                showMessage(getString(R.string.wifi_searching));
                ui.postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && hasWindowFocus()) chooseEar(false);
                }, 1500);
                return;
            }
            List<EarWifi.Device> devices = earWifi.devices();
            String[] names = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                EarWifi.Device device = devices.get(i);
                names[i] = device.ssid;
                for (int j = 0; j < devices.size(); j++) {
                    if (i != j && device.ssid.equals(devices.get(j).ssid)) {
                        names[i] += " · " + device.bssid.substring(device.bssid.length() - 8);
                        break;
                    }
                }
            }
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(getString(R.string.wifi_choose))
                    .setNeutralButton(getString(R.string.wifi_settings), (d, which) -> openWifiSettings())
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.wifi_rescan), (d, which) -> chooseEar(true));
            if (names.length == 0) {
                dialog.setMessage(getString(R.string.wifi_no_devices));
            } else {
                dialog.setItems(names, (d, which) -> previewWith(devices.get(which)));
            }
            dialog.show();
        } catch (SecurityException error) {
            showMessage(getString(R.string.wifi_scan_failed));
            openWifiSettings();
        }
    }

    private void previewWith(EarWifi.Device device) {
        if (session != null) stopPreview();
        selectedEar = device;
        previewRequested = true;
        startPreview();
    }

    private void openWifiSettings() {
        if (session != null) stopPreview();
        selectedEar = null;
        previewRequested = true;
        startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WIFI_PERMISSIONS) {
            if (hasWifiPermissions()) chooseEar(true);
            else showMessage(getString(R.string.wifi_manual));
        }
    }

    private void setStabilize(boolean enabled) {
        if (enabled != preview.stabilize) preview.resetReference();
        preview.stabilize = enabled;
        if (session != null) session.setStabilize(!macro && enabled);
        if (stabilizeButton.isChecked() != enabled) stabilizeButton.setChecked(enabled);
        preview.invalidate();
        record(enabled ? "稳向开启" : "稳向关闭");
    }

    private void setEarSide(EarSide side) {
        earSide = side;
        earSideButton.setText(side.label(this));
        earSideButton.setContentDescription(getString(R.string.ear_current, side.label(this)));
    }

    private void showWirelessReminder() {
        new AlertDialog.Builder(this).setTitle(R.string.wireless_title)
                .setMessage(R.string.wireless_prompt)
                .setPositiveButton(R.string.wireless_settings, (dialog, which) -> openWifiSettings())
                .setNegativeButton(R.string.continue_app, null).show();
    }

    private void savePicture() {
        CaptureImage capture = preview.currentImage();
        if (capture == null) {
            showMessage(getString(R.string.wait_for_preview));
            return;
        }
        if (savingPicture) return;
        savingPicture = true;
        preview.flashUntil = SystemClock.uptimeMillis() + 130;
        preview.invalidate();
        preview.postInvalidateDelayed(130);
        String owner = profile;
        boolean fullFrame = macro;
        EarSide photoSide = fullFrame ? EarSide.NONE : earSide;
        new Thread(() -> {
            File file = CaptureProvider.newFile(this, owner, fullFrame ? "macro" : "photo", "jpg");
            File partial = new File(file.getPath() + ".part");
            String result;
            try {
                try (FileOutputStream output = new FileOutputStream(partial)) {
                    Bitmap image = fullFrame ? capture.createFullPhoto() : capture.createScreenshot();
                    try {
                        if (!image.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                            throw new IOException("图片编码失败");
                        }
                    } finally {
                        image.recycle();
                    }
                }
                if (!partial.renameTo(file)) throw new IOException("无法保存图片");
                photoSide.save(this, file);
                result = getString(R.string.photo_saved);
            } catch (IOException | RuntimeException error) {
                result = getString(R.string.photo_error);
            } finally {
                partial.delete();
            }
            String message = result;
            runOnUiThread(() -> {
                savingPicture = false;
                showMessage(message);
            });
        }, "earclean-photo").start();
    }

    private void startVideo() {
        CaptureImage image = preview.currentImage();
        if (image == null) {
            showMessage(getString(R.string.wait_for_preview));
            return;
        }
        File file = CaptureProvider.newFile(this, profile, "video", "mp4");
        recorder = new VideoCapture(file, error -> runOnUiThread(() -> {
            recorder = null;
            finishingVideo = false;
            hideRecordingTime();
            updateVideoControl();
            showMessage(error == null ? getString(R.string.video_saved) : getString(R.string.video_error, error));
        }));
        updateVideoControl();
        recordingStarted = SystemClock.elapsedRealtime();
        recordingTime.setVisibility(View.VISIBLE);
        updateRecordingTime();
        recorder.offer(image);
        recorder.start();
    }

    private void stopVideo() {
        if (recorder == null) return;
        finishingVideo = true;
        recorder.stop();
        recorder = null;
        hideRecordingTime();
        updateVideoControl();
    }

    private void updateRecordingTime() {
        if (recorder == null) return;
        long seconds = (SystemClock.elapsedRealtime() - recordingStarted) / 1000;
        recordingTime.setText("● " + GalleryActivity.formatTime(seconds));
        ui.postDelayed(recordingTick, 200);
    }

    private void hideRecordingTime() {
        ui.removeCallbacks(recordingTick);
        recordingTime.setVisibility(View.GONE);
    }

    private void showHelp() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.help))
                .setMessage(R.string.help_body)
                .setPositiveButton(getString(R.string.feedback_action), (dialog, which) -> openFeedback())
                .setNeutralButton(getString(R.string.privacy), (dialog, which) -> showPrivacy())
                .setNegativeButton(getString(R.string.close), null).show();
    }

    private void showPrivacy() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.privacy))
                .setMessage(R.string.privacy_body)
                .setPositiveButton(getString(R.string.close), null).show();
    }

    private void openFeedback() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/foreverfine77")));
        } catch (ActivityNotFoundException error) {
            showMessage(getString(R.string.feedback_address));
        }
    }

    private void showMessage(String message) {
        if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void startTest() {
        if (macro) {
            showMessage(getString(R.string.macro_test_hint));
            return;
        }
        report.setLength(0);
        testStarted = SystemClock.elapsedRealtime();
        testPhase = -1;
        lastStatus = "";
        record("微镜记诊断开始 / " + status);
        previewRequested = true;
        startPreview();
        updateTest();
    }

    private void updateTest() {
        long elapsed = (SystemClock.elapsedRealtime() - testStarted) / 1000;
        if (elapsed >= TEST_SECONDS) {
            finishTest(getString(R.string.test_complete, TEST_SECONDS));
            return;
        }
        int phase = elapsed < 4 ? 0 : elapsed < 12 ? 1 : elapsed < 25 ? 2 : 3;
        String step;
        switch (phase) {
            case 0: step = getString(R.string.test_target); break;
            case 1: step = getString(R.string.test_rotate_off); break;
            case 2: step = getString(R.string.test_rotate_on); break;
            default: step = getString(R.string.test_hold); break;
        }
        if (phase != testPhase) {
            testPhase = phase;
            record(step);
            if (phase == 0 || phase == 2) setStabilize(phase == 2);
        }
        guide.setText(getString(R.string.test_countdown, TEST_SECONDS - elapsed, step));
        ui.postDelayed(testTick, 200);
    }

    private void finishTest(String reason) {
        ui.removeCallbacks(testTick);
        record(reason);
        savedReport = report.toString();
        getPreferences(MODE_PRIVATE).edit().putString("lastReport", savedReport).apply();
        testStarted = -1;
        guide.setText(getString(R.string.test_copy_hint, reason));
    }

    private void record(String message) {
        if (testStarted < 0) return;
        double seconds = (SystemClock.elapsedRealtime() - testStarted) / 1000.0;
        report.append(String.format(Locale.ROOT, "%05.1fs  %s%n", seconds, message));
    }

    private void copyReport() {
        String text = testStarted >= 0 ? report.toString() : savedReport;
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.test_first, TEST_SECONDS), Toast.LENGTH_SHORT).show();
            return;
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.test_log_name), text));
        Toast.makeText(this, getString(R.string.test_copied), Toast.LENGTH_SHORT).show();
    }

    private TextView label(String text, int size, int color) {
        return style.label(text, size, color);
    }

    private int dp(int value) {
        return style.dp(value);
    }

    private final class ImagePreview extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mask = new Path();
        private final RectF bounds = new RectF();
        private Bitmap image;
        private long flashUntil;
        private float rotation;
        private boolean stabilize;
        private boolean mirror;
        private float reference = Float.NaN;

        ImagePreview() {
            super(PreviewActivity.this);
            setContentDescription(getString(R.string.preview_description));
        }

        void setFrame(Bitmap frame, float angle) {
            image = frame;
            rotation = angle;
            invalidate();
        }

        void resetReference() {
            reference = Float.NaN;
        }

        CaptureImage currentImage() {
            if (image == null) return null;
            if (!stabilize || Float.isNaN(rotation)) return new CaptureImage(image, 0, mirror);
            if (Float.isNaN(reference)) reference = rotation;
            return new CaptureImage(image, RollFilter.wrap(rotation - reference), mirror);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (macro && image != null) {
                currentImage().renderFull(canvas, getWidth() / 2f, getHeight() / 2f,
                        getWidth(), getHeight(), paint, bounds);
                if (SystemClock.uptimeMillis() < flashUntil) canvas.drawColor(0x55ffffff);
                return;
            }
            float outer = Math.min(getWidth(), getHeight()) / 2f;
            float radius = outer * .885f;
            if (radius <= 0) return;
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            circle.setColor(0x197dd3fc);
            canvas.drawCircle(centerX, centerY, outer * .96f, circle);
            circle.setColor(0x38ffffff);
            canvas.drawCircle(centerX, centerY, radius + outer * .045f, circle);
            circle.setColor(0xff0a0f16);
            canvas.drawCircle(centerX, centerY, radius, circle);
            CaptureImage capture = currentImage();
            if (capture != null) capture.render(canvas, centerX, centerY, 2 * radius, paint, mask, bounds);
            if (SystemClock.uptimeMillis() < flashUntil) {
                circle.setColor(0xbfffffff);
                canvas.drawCircle(centerX, centerY, radius, circle);
            }
            canvas.save();
            canvas.translate(centerX, centerY);
            for (int i = 0; i < 12; i++) {
                boolean major = i % 3 == 0;
                circle.setColor(major ? ACCENT : 0x86ffffff);
                circle.setStrokeWidth(dp(major ? 3 : 1));
                canvas.drawLine(0, -outer, 0, -outer + dp(major ? 8 : 5), circle);
                canvas.rotate(30);
            }
            canvas.restore();
        }
    }
}
