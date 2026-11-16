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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class PreviewActivity extends Activity {
    private final ScopeUi style = new ScopeUi(this);
    private static final int TEST_SECONDS = 30;
    private static final int WIFI_PERMISSIONS = 20;
    private static final int GALLERY_REQUEST = 21;
    private static final long GALLERY_PREVIEW_GRACE_MS = 60_000;
    private static final int PHOTO_BURST_FRAMES = 5;
    private static final int PHOTO_BURST_MS = 250;
    private CameraStream session;
    private EarWifi earWifi;
    private EarWifi.Device selectedEar;
    private boolean previewRequested;
    private boolean viewfinderEnabled;
    private boolean connectedDevice;
    private LensCover lensCover;
    private static final int MASK_PICK = 502;
    private TextView batteryTime;
    private View batteryIcon;
    private View batteryCapsule;
    private Runnable refreshProfileManager;
    private boolean profileOperation;
    private boolean galleryOpen;
    private String status;
    private TextView batteryStatus;
    private LinearLayout empty;
    private TextView connectionTitle;
    private TextView connectionHint;
    private TextView guide;
    private TextView recordingTime;
    private Button previewButton;
    private Button stabilizeButton;
    private Button mirrorButton;
    private Button wifiButton;
    private LinearLayout connectionActions;
    private LinearLayout imageOptions;
    private Button earSideButton;
    private EarSide earSide = EarSide.NONE;
    private boolean macro;
    private boolean macroRotated;
    private View videoButton;
    private View recordMark;
    private View shutter;
    private Button freezeButton;
    private Button rotateMacroButton;
    private Button profileButton;
    private CareProfiles profiles;
    private String profile;
    private ImagePreview preview;
    private VideoCapture recorder;
    private boolean finishingVideo;
    private boolean savingPicture;
    private PhotoBurst photoBurst;
    private long recordingStarted;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private long testStarted = -1;
    private int testPhase = -1;
    private HardwareCheck hardwareCheck;
    private String lastStatus = "";
    private String savedReport = "";
    private final Runnable testTick = this::updateTest;
    private final Runnable recordingTick = this::updateRecordingTime;
    private final Runnable photoBurstTimeout = this::finishPhotoBurst;
    private final Runnable galleryPreviewTimeout = this::stopPreview;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.apply(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        style.load();
        setTheme(style.light ? android.R.style.Theme_Material_Light_NoActionBar
                : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        AppFeedback.get(this);
        macro = getPreferences(MODE_PRIVATE).getBoolean("macro", false);
        savedReport = getPreferences(MODE_PRIVATE).getString("lastReport", "");
        profiles = new CareProfiles(this);
        profile = profiles.current();
        if (state != null) {
            macroRotated = state.getBoolean("macroRotated");
            previewRequested = state.getBoolean("previewRequested");
            viewfinderEnabled = state.getBoolean("viewfinderEnabled");
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
            if (recorder != null || finishingVideo || savingPicture) {
                showMessage(getString(R.string.switch_after_stop));
            } else {
                style.showProfiles(profiles, profile, this::selectProfile,
                        () -> editProfile(null), null);
            }
        });
        FrameLayout leading = new FrameLayout(this);
        leading.addView(profileButton, new FrameLayout.LayoutParams(-1, dp(48), Gravity.CENTER));
        header.addView(leading, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button settings = style.iconButton("settings", getString(R.string.settings));
        settings.setPadding(0, 0, 0, 0);
        settings.setTextSize(23);
        settings.setContentDescription(getString(R.string.settings));
        settings.setOnClickListener(view -> showSettings());
        LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsSize.setMargins(dp(16), 0, dp(16), 0);
        header.addView(settings, settingsSize);
        LinearLayout batteryGroup = new LinearLayout(this);
        batteryGroup.setGravity(Gravity.CENTER_VERTICAL);
        batteryGroup.setBackground(style.shape(style.GLASS, 24, style.EDGE));
        batteryCapsule = batteryGroup;
        LinearLayout charge = new LinearLayout(this);
        charge.setGravity(Gravity.CENTER);
        batteryIcon = new View(this);
        charge.addView(batteryIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        batteryStatus = label("—", 12, style.TEXT);
        batteryStatus.setSingleLine(true);
        batteryStatus.setPadding(dp(3), 0, 0, 0);
        charge.addView(batteryStatus);
        batteryGroup.addView(charge, new LinearLayout.LayoutParams(0, -1, 1));
        TextView separator = label("|", 12, style.MUTED);
        separator.setGravity(Gravity.CENTER);
        batteryGroup.addView(separator, new LinearLayout.LayoutParams(dp(8), -1));
        LinearLayout remaining = new LinearLayout(this);
        remaining.setGravity(Gravity.CENTER);
        batteryTime = label("—", 11, style.TEXT);
        batteryTime.setSingleLine(true);
        batteryTime.setEllipsize(TextUtils.TruncateAt.END);
        remaining.addView(batteryTime);
        View clock = new View(this);
        clock.setBackground(style.symbol("alarm", 0));
        LinearLayout.LayoutParams alarmSize = new LinearLayout.LayoutParams(dp(16), dp(16));
        alarmSize.leftMargin = dp(2);
        remaining.addView(clock, alarmSize);
        batteryGroup.addView(remaining, new LinearLayout.LayoutParams(0, -1, 1));
        FrameLayout trailing = new FrameLayout(this);
        trailing.addView(batteryGroup, new FrameLayout.LayoutParams(-1, dp(48), Gravity.CENTER));
        header.addView(trailing, new LinearLayout.LayoutParams(0, dp(48), 1));
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));

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
        Drawable wifiSymbol = style.symbol("wifi", 0);
        if (style.light) wifiSymbol.setTint(0xfff4f1e6);
        wifiIcon.setBackground(wifiSymbol);
        empty.addView(wifiIcon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        connectionTitle = label("", 17, style.light ? 0xfff4f1e6 : style.TEXT);
        connectionTitle.setGravity(Gravity.CENTER);
        connectionTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        connectionTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        style.add(empty, connectionTitle, 4);
        connectionHint = label("", 13, style.light ? 0xffd5cebb : style.MUTED);
        connectionHint.setGravity(Gravity.CENTER);
        style.add(empty, connectionHint, 5);
        empty.setAlpha(0f);
        empty.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        screen.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        lensCover = new LensCover(this, new LensCover.Host() {
            @Override public boolean canToggle(boolean opening) {
                return (!opening || connectedDevice) && recorder == null && !finishingVideo
                        && !savingPicture && !profileOperation && !preview.isFrozen();
            }
            @Override public void changed(boolean opened) { setViewfinder(opened); }
            @Override public boolean inFrame(float x, float y) {
                preview.updateFrameBounds();
                return preview.frameBounds.contains(x, y);
            }
            @Override public void shape(float value) {
                preview.shapeProgress = value;
                preview.invalidate();
            }
        });
        lensCover.setImage(ProfileMasks.load(this, profile));
        lensCover.setMacro(macro);
        lensCover.restore(viewfinderEnabled);
        screen.addView(lensCover, new FrameLayout.LayoutParams(-1, -1));
        recordingTime = label("", 14, style.TEXT);
        recordingTime.setBackground(style.shape(0xdd242333, 17, style.EDGE));
        recordingTime.setPadding(dp(12), dp(5), dp(12), dp(5));
        recordingTime.setVisibility(View.GONE);
        FrameLayout.LayoutParams timer = new FrameLayout.LayoutParams(-2, -2,
                Gravity.CENTER);
        screen.addView(recordingTime, timer);
        screen.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) ->
                recordingTime.setTranslationY(Math.min(right - left, bottom - top) * .34f));
        freezeButton = style.iconButton("pause", getString(R.string.freeze_preview));
        freezeButton.setBackground(style.pill(0xee242333, 22, style.EDGE));
        freezeButton.setOnClickListener(view -> {
            finishPhotoBurst();
            preview.setFrozen(!preview.isFrozen());
            updateCaptureControls();
        });

        rotateMacroButton = style.iconButton("expand", getString(R.string.macro_rotate_preview));
        rotateMacroButton.setBackground(style.pill(style.light ? 0xeefbf9f2 : 0x66070b12, 24, 0x30ffffff));
        rotateMacroButton.setOnClickListener(view -> {
            macroRotated = !macroRotated;
            rotateMacroButton.animate().rotationBy(90).setDuration(180).start();
            preview.resetZoom();
            updateCaptureControls();
        });
        screen.addView(rotateMacroButton, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.LEFT));
        preview.addOnLayoutChangeListener((view, l, t, r, b, a, c, d, e) -> preview.positionMacroButton());

        connectionActions = new LinearLayout(this);
        connectionActions.setGravity(Gravity.CENTER);
        wifiButton = style.iconButton("device", getString(R.string.choose_device));
        wifiButton.setOnClickListener(view -> chooseEar(true));
        previewButton = style.iconButton("connect", getString(R.string.connect_device));
        previewButton.setBackground(style.pill(0x487dd3fc, 24, 0x757dd3fc));
        if (style.light) previewButton.setTextColor(style.INK);
        previewButton.setOnClickListener(view -> {
            if (previewRequested) stopRequestedPreview();
            else connectEar();
        });
        connectionActions.addView(wifiButton);
        LinearLayout.LayoutParams connectSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        connectSize.leftMargin = dp(8);
        connectionActions.addView(previewButton, connectSize);

        imageOptions = new LinearLayout(this);
        imageOptions.setGravity(Gravity.CENTER);
        stabilizeButton = style.iconButton("stabilize", getString(R.string.stabilize));
        stabilizeButton.setOnClickListener(view -> setStabilize(!preview.stabilize));
        mirrorButton = style.iconButton("mirror", getString(R.string.mirror));
        mirrorButton.setOnClickListener(view -> {
            finishPhotoBurst();
            preview.setFrozen(false);
            preview.mirror = !preview.mirror;
            preview.invalidate();
            updateCaptureControls();
        });
        imageOptions.addView(stabilizeButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams mirrorSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        mirrorSize.setMargins(dp(12), 0, dp(12), 0);
        imageOptions.addView(mirrorButton, mirrorSize);
        earSideButton = style.iconButton("tag", getString(R.string.ear_choose));
        setEarSide(earSide);
        earSideButton.setOnClickListener(view -> new ScopeDialog.Builder(this)
                .setTitle(R.string.ear_choose)
                .setSingleChoiceItems(EarSide.labels(this), earSide.ordinal(), (dialog, which) -> {
                    setEarSide(EarSide.values()[which]);
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show());
        imageOptions.addView(earSideButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams freezeSize = new LinearLayout.LayoutParams(dp(48), dp(48));
        freezeSize.leftMargin = dp(12);
        imageOptions.addView(freezeButton, freezeSize);
        LinearLayout controlsPanel = page;
        if (style.cyan) {
            controlsPanel = new LinearLayout(this);
            controlsPanel.setOrientation(LinearLayout.VERTICAL);
            controlsPanel.setPadding(0, dp(8), 0, 0);
            LinearLayout.LayoutParams panelSize = new LinearLayout.LayoutParams(-1, -2);
            page.addView(controlsPanel, panelSize);
            page.setBackground(style.captureBackdrop(controlsPanel));
        }
        controlsPanel.addView(imageOptions);

        guide = label("", 13, style.MUTED);
        guide.setGravity(Gravity.CENTER);
        guide.setMinHeight(dp(40));
        style.add(controlsPanel, guide, 8);

        LinearLayout captureBar = new LinearLayout(this);
        captureBar.setGravity(Gravity.CENTER);
        View gallery = new View(this);
        gallery.setBackground(style.pill(0x54776d95, 16, style.EDGE));
        View galleryIcon = new View(this);
        galleryIcon.setBackground(style.symbol("album", 0));
        addCapture(captureBar, gallery, galleryIcon, getString(R.string.album), 56, 80, () -> {
            if (recorder != null || finishingVideo || savingPicture) {
                showMessage(getString(R.string.finish_recording_first));
            } else {
                preview.setFrozen(false);
                updateCaptureControls();
                galleryOpen = true;
                startActivityForResult(new Intent(this, GalleryActivity.class)
                        .putExtra(CareProfiles.EXTRA, profile), GALLERY_REQUEST);
            }
        });

        shutter = new View(this);
        GradientDrawable shutterRim = style.shape(0x14000000, 42, 0);
        shutterRim.setStroke(dp(style.orange ? 2 : style.light ? 1 : 3), style.TEXT);
        shutter.setBackground(shutterRim);
        View shutterCenter = new View(this);
        shutterCenter.setBackground(style.shape(style.SHUTTER, 33, style.orange ? style.TEXT : 0));
        addCapture(captureBar, shutter, shutterCenter, getString(R.string.take_photo), 80, 104, this::savePicture);

        videoButton = new View(this);
        videoButton.setBackground(style.pill(style.GLASS, 32, style.EDGE));
        recordMark = new View(this);
        addCapture(captureBar, videoButton, recordMark, getString(R.string.video), 64, 80, () -> {
            if (recorder != null) stopVideo();
            else if (!finishingVideo) startVideo();
        });
        style.add(controlsPanel, captureBar, 18);
        LinearLayout.LayoutParams connectionRow = new LinearLayout.LayoutParams(-1, dp(48));
        connectionRow.topMargin = dp(12);
        controlsPanel.addView(connectionActions, connectionRow);
        updateCaptureControls();
        showConnection(getString(R.string.disconnected), getString(R.string.connect_hint));
        if (state == null) showWirelessReminder();
    }

    private void addCapture(LinearLayout bar, View control, View symbol, String name,
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
        style.captureFeedback(control);
        if (Build.VERSION.SDK_INT >= 26) control.setTooltipText(name);
        control.setOnClickListener(view -> {
            if (control != shutter && control != videoButton) AppFeedback.get(this).play(control, "click");
            action.run();
        });
        column.addView(ring, new LinearLayout.LayoutParams(-1, dp(80)));
        bar.addView(column, new LinearLayout.LayoutParams(dp(columnWidth), -2));
    }

    private void selectProfile(String id) {
        if (!id.equals(profile) && earSideButton != null) setEarSide(EarSide.NONE);
        profile = id;
        profiles.select(id);
        String name = profiles.name(id);
        profileButton.setText(name);
        if (lensCover != null) {
            viewfinderEnabled = false;
            if (session != null) session.setPreviewEnabled(false);
            lensCover.setImage(ProfileMasks.load(this, id));
            lensCover.restore(false);
            preview.setFrame(null, 0);
            showConnection(getString(connectedDevice ? R.string.device_ready : R.string.disconnected),
                    getString(connectedDevice ? R.string.idle_battery_hint : R.string.connect_hint));
        }
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
        if (!live) preview.setFrozen(false);
        updateCaptureControls();
        if (!connectedDevice) showBattery("—", 0);

        if (testStarted < 0) guide.setText(live
                ? (macro ? getString(R.string.macro_hint) : getString(R.string.recording_for, profiles.name(profile)))
                : status);
    }

    private void setLeadingIcon(TextView label, Drawable icon, int size) {
        icon.setBounds(0, 0, dp(size), dp(size));
        label.setCompoundDrawablesRelative(icon, null, null, null);
    }

    private void showBattery(String text, int percent) {
        int separator = text.indexOf('|');
        batteryStatus.setText(text.contains("%") ? percent + "%" : "—");
        batteryTime.setText(separator >= 0 ? text.substring(separator + 1).trim() : "—");
        batteryCapsule.setContentDescription(getString(R.string.battery_description, text));
        batteryIcon.setBackground(style.symbol("battery", percent));
    }

    private void updateCaptureControls() {
        boolean live = empty.getVisibility() == View.GONE;
        boolean frozen = preview.isFrozen();
        boolean active = recorder != null;
        boolean canCapture = live && !frozen;
        boolean canRecord = canCapture || active;
        if (testStarted < 0) guide.setText(frozen ? getString(R.string.frozen_status) : live
                ? (macro ? getString(R.string.macro_hint) : getString(R.string.recording_for, profiles.name(profile))) : status);
        freezeButton.setVisibility(View.VISIBLE);
        freezeButton.setEnabled(live && !active && !finishingVideo);
        style.setIcon(freezeButton, "inspect",
                getString(frozen ? R.string.resume_preview : R.string.freeze_preview));
        style.selectIcon(freezeButton, frozen);
        stabilizeButton.setEnabled(live && !macro);
        mirrorButton.setEnabled(live);
        earSideButton.setEnabled(live && !macro);
        style.selectIcon(stabilizeButton, preview.stabilize);
        style.selectIcon(mirrorButton, preview.mirror);
        style.setIcon(previewButton, previewRequested ? (connectedDevice ? "disconnect" : "cancel") : "connect",
                getString(previewRequested ? (connectedDevice ? R.string.disconnect_device : R.string.stop_connecting) : R.string.connect_device));
        previewButton.setBackground(style.pill(previewRequested ? style.SOFT : style.ACCENT, 24, 0));

        if (style.light && previewButton.getCompoundDrawables()[0] != null) {
            previewButton.getCompoundDrawables()[0].setTint(previewRequested ? style.RED : style.INK);
        }
        rotateMacroButton.setVisibility(live && macro ? View.VISIBLE : View.GONE);
        style.setIcon(rotateMacroButton, macroRotated ? "collapse" : "expand",
                getString(macroRotated ? R.string.macro_restore_preview : R.string.macro_rotate_preview));
        preview.positionMacroButton();
        shutter.setEnabled(canCapture);
        ((View) shutter.getParent()).setAlpha(canCapture ? 1f : .45f);
        String text = active ? getString(R.string.stop_recording) : finishingVideo ? getString(R.string.saving) : getString(R.string.video);
        videoButton.setContentDescription(text);
        if (Build.VERSION.SDK_INT >= 26) videoButton.setTooltipText(text);
        videoButton.setEnabled(canRecord && !finishingVideo);
        ((View) videoButton.getParent()).setAlpha(canRecord ? 1f : .45f);
        int size = active ? 24 : 34;
        recordMark.getLayoutParams().width = dp(size);
        recordMark.getLayoutParams().height = dp(size);
        recordMark.requestLayout();
        recordMark.setBackground(style.shape(style.RECORD, active ? 7 : 17, 0));
    }

    private void showSettings() {
        Dialog dialog = new Dialog(this);
        LinearLayout page = style.page();
        style.fit(this, page, 16, 12);
        Button back = style.iconButton("back", getString(R.string.back));
        back.setContentDescription(getString(R.string.back_to_preview));
        back.setOnClickListener(view -> dialog.dismiss());
        page.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = label(getString(R.string.settings), 30, style.TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        style.add(page, heading, 18);
        style.add(page, label(getString(R.string.profiles), 13, style.MUTED), 24);
        settingsGroup(page, new String[]{getString(R.string.manage_profiles)},
                new Runnable[]{this::showProfileManager});
        style.add(page, label(getString(R.string.settings_appearance), 13, style.MUTED), 24);
        settingsGroup(page, new String[]{getString(R.string.mask_image) + " · " + profiles.name(profile)},
                new Runnable[]{this::chooseMaskImage});
        settingsGroup(page, new String[]{getString(R.string.ui_theme) + " · "
                        + getString(style.themeLabel())},
                new Runnable[]{() -> chooseUiTheme(dialog)});
        style.add(page, label(getString(R.string.settings_capture), 13, style.MUTED), 24);
        settingsGroup(page, new String[]{getString(R.string.choose_device), getString(R.string.device_info)},
                new Runnable[]{() -> chooseEar(true), this::showDeviceInfo});
        settingsGroup(page, new String[]{getString(macro ? R.string.macro_on : R.string.macro_off)},
                new Runnable[]{() -> chooseMacroMode(dialog)});
        style.add(page, label(getString(R.string.settings_general), 13, style.MUTED), 24);
        settingsGroup(page, new String[]{getString(R.string.language_title), getString(R.string.interaction_feedback)},
                new Runnable[]{() -> showLanguages(dialog), this::showInteractionFeedback});
        style.add(page, label(getString(R.string.settings_support), 13, style.MUTED), 24);
        settingsGroup(page, new String[]{getString(R.string.help), getString(R.string.feedback), getString(R.string.privacy)},
                new Runnable[]{this::showHelp, this::openFeedback, this::showPrivacy});
        style.add(page, label(getString(R.string.test_tools), 13, style.MUTED), 24);
        settingsGroup(page,
                new String[]{testStarted >= 0 ? getString(R.string.test_stop) : getString(R.string.test_label, TEST_SECONDS), getString(R.string.test_copy)},
                new Runnable[]{() -> {
                    dialog.dismiss();
                    if (testStarted >= 0) finishTest(getString(R.string.test_manual_end));
                    else startTest();
                }, this::copyReport});
        TextView version = label(getString(R.string.brand), 12, style.MUTED);
        version.setGravity(Gravity.CENTER);
        style.add(page, version, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // Match the album: the wallpaper belongs to the stationary viewport.
        scroll.setBackground(style.backdrop());
        page.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        scroll.addView(page);
        dialog.setContentView(scroll);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        style.decorateWindow(dialog.getWindow());
    }

    private void chooseMaskImage() {
        if (profileOperation || savingPicture || finishingVideo || recorder != null) {
            showMessage(getString(R.string.finish_recording_first));
            return;
        }
        startActivityForResult(new Intent(this, CoverPickerActivity.class)
                .putExtra(CareProfiles.EXTRA, profile), MASK_PICK);
    }

    private void showDeviceInfo() {
        new ScopeDialog.Builder(this).setTitle(R.string.device_info)
                .setMessage(getString(R.string.device_info_body))
                .setPositiveButton(R.string.close, null).show();
    }

    private void reloadMask() {
        if (lensCover != null) lensCover.setImage(ProfileMasks.load(this, profile));
    }

    private void showProfileManager() {
        Dialog manager = new Dialog(this);
        LinearLayout page = style.page();
        page.setPadding(dp(16), dp(16), dp(16), dp(16));
        Button back = style.iconButton("back", getString(R.string.back));
        back.setOnClickListener(view -> manager.dismiss());
        page.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        style.add(page, label(getString(R.string.manage_profiles), 18, style.TEXT), 16);
        style.add(page, label(getString(R.string.profile_sort_hint), 12, style.MUTED), 8);
        LinearLayout rows = new ProfileOrderList(this, style, profiles::move);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button add = style.button(getString(R.string.profile_add_person));
        add.setOnClickListener(view -> editProfile(null));
        style.add(page, add, 12);
        refreshProfileManager = () -> renderProfileRows(rows);
        manager.setOnDismissListener(window -> refreshProfileManager = null);
        refreshProfileManager.run();
        manager.setContentView(page);
        manager.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        manager.show();
        manager.getWindow().setLayout(-1, -1);
        style.decorateWindow(manager.getWindow());
    }

    private void renderProfileRows(LinearLayout rows) {
        rows.removeAllViews();
        rows.setEnabled(!profileOperation);
        for (String id : profiles.ids()) {
            LinearLayout row = new LinearLayout(this);
            row.setTag(id);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View person = ProfileOrderList.name(this, style, profiles.name(id));
            person.setOnClickListener(view -> editProfile(id));

            Button delete = style.iconButton("delete", getString(R.string.delete_profile));
            delete.setOnClickListener(view -> {
                if (profileOperation) return;
                if (profiles.ids().size() <= 1) {
                    showMessage(getString(R.string.keep_one_profile));
                    return;
                }
                if (savingPicture || finishingVideo || recorder != null) {
                    showMessage(getString(R.string.switch_after_stop));
                    return;
                }
                new ScopeDialog.Builder(this).setTitle(getString(R.string.delete_profile))
                        .setMessage(getString(R.string.delete_profile_confirm,
                                profiles.name(id), profiles.captureCount(id)))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete, (dialog, which) -> {
                            profileOperation = true;
                            rows.setEnabled(false);
                            delete.setEnabled(false);
                            new Thread(() -> {
                                boolean removed = profiles.remove(id);
                                runOnUiThread(() -> {
                                    profileOperation = false;
                                    if (isDestroyed()) return;
                                    if (removed) selectProfile(profiles.current());
                                    else showMessage(getString(R.string.profile_delete_failed));
                                    if (refreshProfileManager != null) refreshProfileManager.run();
                                });
                            }, "profile-remove").start();
                        }).show();
            });

            Button handle = style.iconButton("drag", getString(R.string.profile_reorder));
            handle.setLongClickable(false);
            row.setBackground(style.shape(style.GLASS, 24, style.EDGE));
            row.setPadding(dp(4), dp(4), dp(4), dp(4));
            for (View integrated : new View[]{handle, person, delete}) {
                integrated.setBackground(style.pill(0, 24, 0));
                integrated.setElevation(0);
            }
            row.addView(handle, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.addView(person, new LinearLayout.LayoutParams(0, dp(48), 1));
            row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
            style.add(rows, row, 8);
        }
    }

    private void updateFeedback(View view) {
        view.setSoundEffectsEnabled(false);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) updateFeedback(group.getChildAt(i));
        }
    }

    private void showInteractionFeedback() {
        android.content.SharedPreferences prefs = getSharedPreferences("appearance", MODE_PRIVATE);
        String[] keys = {"sound_feedback", "haptic_feedback"};
        String[] titles = {getString(R.string.sound_feedback), getString(R.string.haptic_feedback)};
        String[] options = new String[2];
        for (int i = 0; i < keys.length; i++) {
            options[i] = titles[i] + " · " + getString(prefs.getBoolean(keys[i], true)
                    ? R.string.feedback_on : R.string.feedback_off);
        }
        new ScopeDialog.Builder(this).setTitle(R.string.interaction_feedback)
                .setMessage(R.string.sound_volume_hint)
                .setItems(options, (dialog, which) -> {
                    prefs.edit().putBoolean(keys[which], !prefs.getBoolean(keys[which], true)).apply();
                    updateFeedback(getWindow().getDecorView());
                    showInteractionFeedback();
                }).setNegativeButton(R.string.close, null).show();
    }

    private void chooseUiTheme(Dialog settings) {
        String[] ids = ScopeUi.THEMES;
        String[] names = {getString(R.string.ui_dark), getString(R.string.ui_warm),
                getString(R.string.ui_orange), getString(R.string.ui_cyan)};
        int[] choice = {Math.max(0, java.util.Arrays.asList(ids).indexOf(ScopeUi.selectedTheme(this)))};
        new ScopeDialog.Builder(this).setTitle(R.string.ui_theme)
                .setSingleChoiceItems(names, choice[0], (dialog, which) -> choice[0] = which)
                .setPositiveButton(R.string.ui_set_default, (dialog, button) -> {
                    int which = choice[0];
                    if (ids[which].equals(ScopeUi.selectedTheme(this))) return;
                    if (recorder != null || finishingVideo || savingPicture) {
                        showMessage(getString(R.string.finish_recording_first));
                        return;
                    }
                    getSharedPreferences("appearance", MODE_PRIVATE).edit()
                            .putString("ui_theme", ids[which]).apply();
                    settings.dismiss();
                    recreate();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseMacroMode(Dialog settings) {
        if (recorder != null || finishingVideo || savingPicture) {
            showMessage(getString(R.string.finish_recording_first));
            return;
        }
        new ScopeDialog.Builder(this).setTitle(R.string.macro_mode)
                .setMessage(R.string.macro_explanation)
                .setPositiveButton(macro ? R.string.macro_disable : R.string.macro_enable, (dialog, which) -> {
                    preview.setFrozen(false);
                    macro = !macro;
                    lensCover.setMacro(macro);
                    macroRotated = false;
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
        new ScopeDialog.Builder(this).setTitle(R.string.language_title)
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
        group.setBackground(style.shape(style.GLASS, 26, style.EDGE));
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
        if (profileOperation || recorder != null || finishingVideo || savingPicture) {
            showMessage(getString(R.string.finish_recording_first));
            return;
        }
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
        ScopeDialog dialog = new ScopeDialog.Builder(this)
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
        dialog.setOnDismissListener(window -> {
            if (refreshProfileManager != null) refreshProfileManager.run();
        });
        name.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(galleryPreviewTimeout);
        String activeProfile = profiles.current();
        if (!activeProfile.equals(profile)) selectProfile(activeProfile);
        updateFeedback(getWindow().getDecorView());
        if (previewRequested) startPreview();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("previewRequested", previewRequested);
        state.putBoolean("viewfinderEnabled", viewfinderEnabled);
        state.putBoolean("macroRotated", macroRotated);
        if (selectedEar != null) state.putString("selectedEar", selectedEar.key());
        state.putString("profile", profile);
        state.putInt("earSide", earSide.ordinal());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStop() {
        if (testStarted >= 0) finishTest(getString(R.string.test_left));
        if (galleryOpen && session != null && !isFinishing() && !isChangingConfigurations()) {
            // Keep the current network request across short visits to the in-app gallery.
            ui.postDelayed(galleryPreviewTimeout, GALLERY_PREVIEW_GRACE_MS);
        } else {
            stopPreview();
        }
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GALLERY_REQUEST) galleryOpen = false;
        if (requestCode == MASK_PICK && resultCode == RESULT_OK) reloadMask();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(galleryPreviewTimeout);
        if (session != null) stopPreview();
        super.onDestroy();
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
                    finishPhotoBurst();
                    preview.resetReference();
                    preview.setFrame(null, 0);
                    connectedDevice = false;
                    showConnection(message, getString(R.string.waiting_image));
                });
            }

            @Override
            public void connected(CameraStream source, Network network) {
                runOnUiThread(() -> {
                    if (session != source) return;
                    earWifi.confirm(network);
                    connectedDevice = true;
                    if (!viewfinderEnabled) showConnection(getString(R.string.device_ready),
                            getString(R.string.idle_battery_hint));
                });
            }

            @Override
            public void frame(CameraStream source, Bitmap image, float rotation) {
                runOnUiThread(() -> {
                    if (session != source || !viewfinderEnabled) return;
                    connectedDevice = true;
                    if (empty.getVisibility() != View.GONE) {
                        showConnection(getString(R.string.live), null);
                        record("进入实时预览");
                    }
                    preview.setFrame(image, rotation);
                    if (testStarted >= 0 && hardwareCheck != null && image != null)
                        hardwareCheck.frame(SystemClock.elapsedRealtime(), image.getWidth(), image.getHeight(), rotation);
                    if (recorder != null || photoBurst != null) {
                        CaptureImage capture = preview.currentImage();
                        if (recorder != null) recorder.offer(capture);
                        if (photoBurst != null) {
                            photoBurst.frames.add(capture);
                            if (photoBurst.frames.size() >= PHOTO_BURST_FRAMES) finishPhotoBurst();
                        }
                    }
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
                    } else {
                        if (testStarted >= 0 && hardwareCheck != null) hardwareCheck.batteryReplies++;
                        String detail = reading.charging
                                ? getString(R.string.battery_charging_short, reading.percent)
                                : getString(R.string.battery_short, reading.percent, reading.estimatedMinutes());
                        showBattery(detail, reading.percent);
                    }
                });
            }
        });
        showConnection(getString(R.string.connecting), getString(R.string.waiting_image));
        if (viewfinderEnabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session.setPreviewEnabled(viewfinderEnabled);
        session.setStabilize(!macro && preview.stabilize);
        session.start();
    }

    private void setViewfinder(boolean enabled) {
        if (recorder != null || finishingVideo || savingPicture) {
            showMessage(getString(R.string.finish_recording_first));
            return;
        }
        viewfinderEnabled = enabled;
        if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preview.setFrozen(false);
        preview.setFrame(null, 0);
        if (session != null) session.setPreviewEnabled(enabled);
        showConnection(getString(enabled ? R.string.connecting : R.string.device_ready),
                getString(enabled ? R.string.waiting_image : R.string.idle_battery_hint));
    }

    private void messageWithCorrection(String message) {
        CaptureImage image = preview.currentImage();
        String correction = image == null ? "—" : String.format(Locale.ROOT, "%.1f°", image.rotation);
        record(message + " 显示补偿=" + correction);
    }

    private void stopRequestedPreview() {
        previewRequested = false;
        viewfinderEnabled = false;
        lensCover.restore(false);
        stopPreview();
        selectedEar = null;
        if (testStarted >= 0) finishTest(getString(R.string.test_preview_stopped));
    }

    private void stopPreview() {
        record("用户停止预览");
        finishPhotoBurst();
        stopVideo();
        if (session != null) session.stop();
        session = null;
        connectedDevice = false;
        earWifi.stop();
        preview.resetReference();
        preview.setFrame(null, 0);
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
        if (profileOperation) return;
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
            new ScopeDialog.Builder(this).setTitle(getString(R.string.wifi_discover))
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
            ScopeDialog.Builder dialog = new ScopeDialog.Builder(this).setTitle(getString(R.string.wifi_choose))
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
        if (enabled != preview.stabilize) {
            finishPhotoBurst();
            preview.setFrozen(false);
            preview.resetReference();
        }
        preview.stabilize = enabled;
        if (session != null) session.setStabilize(!macro && enabled);
        updateCaptureControls();
        preview.invalidate();
        record(enabled ? "稳向开启" : "稳向关闭");
    }

    private void setEarSide(EarSide side) {
        earSide = side;
        String kind = side == EarSide.LEFT ? "tag-left" : side == EarSide.RIGHT ? "tag-right" : "tag";
        style.setIcon(earSideButton, kind, getString(R.string.ear_current, side.label(this)));
        style.selectIcon(earSideButton, side != EarSide.NONE);
    }

    private void showWirelessReminder() {
        new ScopeDialog.Builder(this).setTitle(R.string.wireless_title)
                .setMessage(R.string.wireless_prompt)
                .setPositiveButton(R.string.wireless_settings, (dialog, which) -> openWifiSettings())
                .setNegativeButton(R.string.continue_app, null).show();
    }

    private void savePicture() {
        if (profileOperation) return;
        if (savingPicture) return;
        CaptureImage capture = preview.currentImage();
        if (capture == null) {
            showMessage(getString(R.string.wait_for_preview));
            return;
        }
        AppFeedback.get(this).play(shutter, "shutter");
        savingPicture = true;
        photoBurst = new PhotoBurst(capture, profile, macro, macro ? EarSide.NONE : earSide);
        ui.postDelayed(photoBurstTimeout, PHOTO_BURST_MS);
        preview.flashUntil = SystemClock.uptimeMillis() + 130;
        preview.invalidate();
        preview.postInvalidateDelayed(130);
    }

    private void finishPhotoBurst() {
        PhotoBurst burst = photoBurst;
        if (burst == null) return;
        photoBurst = null;
        ui.removeCallbacks(photoBurstTimeout);
        new Thread(() -> {
            File partial = null;
            String result;
            try {
                CaptureImage capture = burst.bestImage();
                File file = CaptureProvider.newFile(this, burst.owner, burst.fullFrame ? "macro" : "photo", "jpg");
                partial = new File(file.getPath() + ".part");
                try (FileOutputStream output = new FileOutputStream(partial)) {
                    Bitmap image = burst.fullFrame ? capture.createFullPhoto() : capture.createScreenshot();
                    try {
                        if (!image.compress(Bitmap.CompressFormat.JPEG, 94, output)) {
                            throw new IOException("图片编码失败");
                        }
                    } finally {
                        image.recycle();
                    }
                }
                if (!partial.renameTo(file)) throw new IOException("无法保存图片");
                burst.side.save(this, file);
                result = getString(R.string.photo_saved);
            } catch (IOException | RuntimeException error) {
                result = getString(R.string.photo_error);
            } finally {
                if (partial != null) partial.delete();
            }
            String message = result;
            runOnUiThread(() -> {
                savingPicture = false;
                if (!isDestroyed()) showMessage(message);
            });
        }, "earclean-photo").start();
    }

    private static final class PhotoBurst {
        final List<CaptureImage> frames = new ArrayList<>(PHOTO_BURST_FRAMES);
        final String owner;
        final boolean fullFrame;
        final EarSide side;

        PhotoBurst(CaptureImage first, String owner, boolean fullFrame, EarSide side) {
            frames.add(first);
            this.owner = owner;
            this.fullFrame = fullFrame;
            this.side = side;
        }

        CaptureImage bestImage() {
            CaptureImage best = frames.get(0);
            if (frames.size() == 1) return best;
            double bestScore = -1;
            int[] pixels = new int[0];
            for (CaptureImage candidate : frames) {
                Bitmap frame = candidate.frame;
                int width = frame.getWidth();
                int height = frame.getHeight();
                if (pixels.length < width * height) pixels = new int[width * height];
                frame.getPixels(pixels, 0, width, 0, 0, width, height);
                double score = PhotoQuality.score(pixels, width, height, fullFrame);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
            return best;
        }
    }

    private void startVideo() {
        if (profileOperation) return;
        CaptureImage image = preview.currentImage();
        if (image == null) {
            showMessage(getString(R.string.wait_for_preview));
            return;
        }
        File file = CaptureProvider.newFile(this, profile, macro ? "macro-video" : "video", "mp4");
        AppFeedback.get(this).play(videoButton, "record-start");
        recorder = new VideoCapture(file, image, macro, error -> runOnUiThread(() -> {
            recorder = null;
            finishingVideo = false;
            hideRecordingTime();
            updateCaptureControls();
            showMessage(error == null ? getString(R.string.video_saved) : getString(R.string.video_error, error));
        }));
        updateCaptureControls();
        recordingStarted = SystemClock.elapsedRealtime();
        recordingTime.setVisibility(View.VISIBLE);
        updateRecordingTime();
        recorder.offer(image);
        recorder.start();
    }

    private void stopVideo() {
        if (recorder == null) return;
        AppFeedback.get(this).play(videoButton, "record-stop");
        finishingVideo = true;
        recorder.stop();
        recorder = null;
        hideRecordingTime();
        updateCaptureControls();
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
        new ScopeDialog.Builder(this).setTitle(getString(R.string.help))
                .setMessage(R.string.help_body)
                .setPositiveButton(getString(R.string.feedback_action), (dialog, which) -> openFeedback())
                .setNeutralButton(getString(R.string.privacy), (dialog, which) -> showPrivacy())
                .setNegativeButton(getString(R.string.close), null).show();
    }

    private void showPrivacy() {
        new ScopeDialog.Builder(this).setTitle(getString(R.string.privacy))
                .setMessage(R.string.privacy_body)
                .setPositiveButton(getString(R.string.close), null).show();
    }

    private void openFeedback() {
        new ScopeDialog.Builder(this).setTitle(R.string.feedback)
                .setMessage(R.string.feedback_help)
                .setPositiveButton(R.string.feedback_action, (dialog, which) -> openFeedbackLink())
                .setNeutralButton(R.string.test_copy, (dialog, which) -> copyReport())
                .setNegativeButton(R.string.cancel, null).show();
    }

    private void openFeedbackLink() {
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
        if (profileOperation || recorder != null || finishingVideo || savingPicture) {
            showMessage(getString(R.string.finish_recording_first));
            return;
        }
        new ScopeDialog.Builder(this).setTitle(R.string.hardware_check)
                .setMessage(R.string.hardware_prepare)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.hardware_begin, (dialog, which) -> beginHardwareCheck()).show();
    }

    private void beginHardwareCheck() {
        if (macro) {
            showMessage(getString(R.string.macro_test_hint));
            return;
        }
        report.setLength(0);
        hardwareCheck = new HardwareCheck();
        testStarted = SystemClock.elapsedRealtime();
        testPhase = -1;
        lastStatus = "";
        record("微镜记诊断开始 / " + status);
        previewRequested = true;
        viewfinderEnabled = true;
        preview.setFrozen(false);
        lensCover.restore(true);
        if (session != null) session.setPreviewEnabled(true);
        startPreview();
        updateTest();
    }

    private void updateTest() {
        long elapsed = (SystemClock.elapsedRealtime() - testStarted) / 1000;
        if (elapsed >= TEST_SECONDS) {
            finishTest(getString(R.string.test_complete, TEST_SECONDS));
            return;
        }
        int phase = elapsed < 8 ? 0 : elapsed < 18 ? 1 : elapsed < 26 ? 2 : 3;
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
        }
        guide.setText(getString(R.string.test_countdown, TEST_SECONDS - elapsed, step));
        ui.postDelayed(testTick, 200);
    }

    private void finishTest(String reason) {
        ui.removeCallbacks(testTick);
        boolean completed = testStarted >= 0 && SystemClock.elapsedRealtime() - testStarted >= TEST_SECONDS * 1000L;
        if (hardwareCheck != null) {
            String measured = getString(R.string.hardware_measured, hardwareCheck.frames,
                    hardwareCheck.width, hardwareCheck.height, hardwareCheck.fps(),
                    hardwareCheck.gapAt(SystemClock.elapsedRealtime()) / 1000f, hardwareCheck.batteryReplies,
                    hardwareCheck.angleTravel);
            record(measured);
        }
        record(reason);
        savedReport = report.toString();
        getPreferences(MODE_PRIVATE).edit().putString("lastReport", savedReport).apply();
        testStarted = -1;
        guide.setText(getString(R.string.test_copy_hint, reason));
        if (completed && !isFinishing() && !isDestroyed()) showHardwareResult();
    }

    private void showHardwareResult() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        TextView result = label(getString(R.string.hardware_measured, hardwareCheck.frames,
                hardwareCheck.width, hardwareCheck.height, hardwareCheck.fps(),
                hardwareCheck.gapAt(SystemClock.elapsedRealtime()) / 1000f, hardwareCheck.batteryReplies,
                hardwareCheck.angleTravel), 14, style.TEXT);
        style.add(page, result, 4);
        style.add(page, label(getString(hardwareCheck.frames == 0 ? R.string.hardware_no_frames
                : R.string.hardware_observe), 14, style.MUTED), 12);
        android.widget.CheckBox clear = new android.widget.CheckBox(this);
        clear.setText(R.string.hardware_clear); clear.setTextColor(style.TEXT);
        android.widget.CheckBox light = new android.widget.CheckBox(this);
        light.setText(R.string.hardware_light); light.setTextColor(style.TEXT);
        style.add(page, clear, 8); style.add(page, light, 4);
        new ScopeDialog.Builder(this).setTitle(R.string.hardware_result).setView(page)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    savedReport += "\n" + getString(R.string.hardware_clear) + ": " + clear.isChecked()
                            + "\n" + getString(R.string.hardware_light) + ": " + light.isChecked()
                            + "\n" + getString(R.string.hardware_observe);
                    getPreferences(MODE_PRIVATE).edit().putString("lastReport", savedReport).apply();
                }).show();
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
        private static final float MAX_ZOOM = 4f;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mask = new Path();
        private final RectF bounds = new RectF();
        private Bitmap image;
        private long flashUntil;
        private float shapeProgress;
        private int frameWidth = getPreferences(MODE_PRIVATE).getInt("frameWidth", 640);
        private int frameHeight = getPreferences(MODE_PRIVATE).getInt("frameHeight", 480);
        private final RectF frameBounds = new RectF();
        private final RectF aperture = new RectF();
        private final Path aperturePath = new Path();
        private float rotation;
        private boolean stabilize;
        private boolean mirror;
        private float reference = Float.NaN;
        private CaptureImage frozenImage;
        private float zoom = 1f;
        private float panX;
        private float panY;
        private final ScaleGestureDetector pinch;
        private final GestureDetector drag;

        ImagePreview() {
            super(PreviewActivity.this);
            setContentDescription(getString(R.string.preview_description));
            pinch = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private float lastX;
                private float lastY;

                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                    lastX = detector.getFocusX();
                    lastY = detector.getFocusY();
                    return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float nextZoom = Math.max(1f, Math.min(MAX_ZOOM, zoom * detector.getScaleFactor()));
                    float ratio = nextZoom / zoom;
                    UiGeometry.Lens lens = UiGeometry.lens(getWidth(), getHeight(), style.orange);
                    float centerX = macro ? getWidth() / 2f : lens.x;
                    float centerY = macro ? getHeight() / 2f : lens.y;
                    float focusX = detector.getFocusX();
                    float focusY = detector.getFocusY();
                    panX = focusX - centerX + (panX + centerX - lastX) * ratio;
                    panY = focusY - centerY + (panY + centerY - lastY) * ratio;
                    zoom = nextZoom;
                    lastX = focusX;
                    lastY = focusY;
                    clampPan();
                    invalidate();
                    return true;
                }
            });
            pinch.setQuickScaleEnabled(false);
            drag = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent event) {
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent start, MotionEvent event, float dx, float dy) {
                    if (event.getPointerCount() != 1 || pinch.isInProgress()) return false;
                    panX -= dx;
                    panY -= dy;
                    clampPan();
                    invalidate();
                    return true;
                }
            });
        }

        void setFrame(Bitmap frame, float angle) {
            boolean resized = image == null || frame == null
                    || image.getWidth() != frame.getWidth() || image.getHeight() != frame.getHeight();
            image = frame;
            if (frame != null && (frameWidth != frame.getWidth() || frameHeight != frame.getHeight())) {
                frameWidth = frame.getWidth();
                frameHeight = frame.getHeight();
                getPreferences(MODE_PRIVATE).edit().putInt("frameWidth", frameWidth)
                        .putInt("frameHeight", frameHeight).apply();
            }
            rotation = angle;
            if (resized) positionMacroButton();
            if (!isFrozen()) invalidate();
        }

        boolean isFrozen() {
            return frozenImage != null;
        }

        void setFrozen(boolean frozen) {
            if (frozen == isFrozen()) return;
            frozenImage = frozen ? currentImage() : null;
            setContentDescription(getString(isFrozen() ? R.string.frozen_image_description : R.string.preview_description));
            resetZoom();
        }

        void resetZoom() {
            zoom = 1f;
            panX = panY = 0;
            positionMacroButton();
            invalidate();
        }

        void updateFrameBounds() {
            Bitmap source = isFrozen() ? frozenImage.frame : image;
            int width = source == null ? frameWidth : source.getWidth();
            int height = source == null ? frameHeight : source.getHeight();
            if (macroRotated) {
                int previousWidth = width;
                width = height;
                height = previousWidth;
            }
            float scale = Math.min((float) getWidth() / Math.max(1, width),
                    (float) getHeight() / Math.max(1, height));
            float left = (getWidth() - width * scale) / 2;
            float top = (getHeight() - height * scale) / 2;
            frameBounds.set(left, top, getWidth() - left, getHeight() - top);
        }

        void positionMacroButton() {
            if (rotateMacroButton == null || !macro) return;
            updateFrameBounds();
            rotateMacroButton.setX(frameBounds.right - dp(56));
            rotateMacroButton.setY(frameBounds.bottom - dp(56));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isFrozen()) return super.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            pinch.onTouchEvent(event);
            drag.onTouchEvent(event);
            return true;
        }

        private void clampPan() {
            if (macro) {
                updateFrameBounds();
                float maxX = Math.max(0, frameBounds.width() * (zoom - 1f) / 2);
                float maxY = Math.max(0, frameBounds.height() * (zoom - 1f) / 2);
                panX = Math.max(-maxX, Math.min(maxX, panX));
                panY = Math.max(-maxY, Math.min(maxY, panY));
            } else {
                float max = (zoom - 1f) * UiGeometry.lens(getWidth(), getHeight(), style.orange).radius;
                float distance = (float) Math.hypot(panX, panY);
                if (distance > max) {
                    panX *= max / distance;
                    panY *= max / distance;
                }
            }
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
            CaptureImage capture = isFrozen() ? frozenImage : currentImage();
            UiGeometry.Lens lens = UiGeometry.lens(getWidth(), getHeight(), style.orange);
            float centerX = lens.x;
            float centerY = lens.y;
            if (macro && shapeProgress > 0) {
                float radius = lens.radius;
                updateFrameBounds();
                float t = shapeProgress;
                aperture.set((centerX - radius) * (1 - t) + frameBounds.left * t,
                        (centerY - radius) * (1 - t) + frameBounds.top * t,
                        (centerX + radius) * (1 - t) + frameBounds.right * t,
                        (centerY + radius) * (1 - t) + frameBounds.bottom * t);
                aperturePath.rewind();
                aperturePath.addRoundRect(aperture, radius * (1 - t), radius * (1 - t), Path.Direction.CW);
                canvas.save();
                canvas.clipPath(aperturePath);
                canvas.drawColor(style.VIEWPORT);
                if (capture != null) {
                    canvas.translate(panX, panY);
                    canvas.scale(zoom, zoom, frameBounds.centerX(), frameBounds.centerY());
                    if (macroRotated) canvas.rotate(90, frameBounds.centerX(), frameBounds.centerY());
                    capture.renderFull(canvas, frameBounds.centerX(), frameBounds.centerY(),
                            macroRotated ? frameBounds.height() : frameBounds.width(),
                            macroRotated ? frameBounds.width() : frameBounds.height(), paint, bounds);
                }
                canvas.restore();
                if (style.cyan || style.orange) {
                    circle.setStyle(Paint.Style.STROKE);
                    circle.setStrokeWidth(dp(style.cyan ? 6 : 2));
                    circle.setColor(style.cyan ? style.SURFACE : style.TEXT);
                    canvas.drawRoundRect(aperture, radius * (1 - t), radius * (1 - t), circle);
                    circle.setStyle(Paint.Style.FILL);
                }
                if (SystemClock.uptimeMillis() < flashUntil) {
                    circle.setColor(0x55ffffff);
                    canvas.drawRoundRect(aperture, radius * (1 - t), radius * (1 - t), circle);
                }
                return;
            }
            float outer = Math.min(getWidth(), getHeight()) / 2f;
            float radius = lens.radius;
            float rim = style.cyan ? dp(6) : style.orange ? dp(3) : outer * .045f;
            if (radius <= 0) return;
            circle.setColor(style.MAT);
            if (style.orange) {
                canvas.drawCircle(lens.backX, lens.backY, lens.backRadius, circle);
                circle.setStyle(Paint.Style.STROKE);
                circle.setStrokeWidth(dp(2));
                circle.setColor(style.TEXT);
                canvas.drawCircle(lens.backX, lens.backY, lens.backRadius, circle);
                circle.setStyle(Paint.Style.FILL);
            } else {
                canvas.drawCircle(centerX, centerY, style.cyan ? radius + rim : outer * .96f, circle);
            }
            if (style.orange) {
                circle.setColor(style.TEXT);
                canvas.drawCircle(centerX, centerY + dp(4), radius + rim, circle);
            }
            circle.setColor(style.cyan ? style.SURFACE : style.light ? style.EDGE : 0x38ffffff);
            if (!style.light) circle.setShader(new android.graphics.LinearGradient(
                    centerX-radius, centerY-radius, centerX+radius, centerY+radius,
                    new int[]{0xffc2edff, 0xff6187a4, 0xffd0edff, 0xff41617c},
                    null, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawCircle(centerX, centerY, radius + rim, circle);
            circle.setShader(null);
            circle.setColor(style.VIEWPORT);
            canvas.drawCircle(centerX, centerY, radius, circle);
            if (capture != null) {
                capture.render(canvas, centerX, centerY, 2 * radius, paint, mask, bounds, zoom, panX, panY);
            }
            if (SystemClock.uptimeMillis() < flashUntil) {
                circle.setColor(0xbfffffff);
                canvas.drawCircle(centerX, centerY, radius, circle);
            }

        }
    }
}
