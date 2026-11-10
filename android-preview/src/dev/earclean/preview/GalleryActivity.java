package dev.earclean.preview;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.TextUtils;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.SectionIndexer;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GalleryActivity extends Activity {
    private static final int TEXT = ScopeUi.TEXT;
    private static final int ACCENT = ScopeUi.ACCENT;
    private final ScopeUi style = new ScopeUi(this);
    private static final int COLUMNS = 3;
    private ListView list;
    private TextView empty;
    private String profile;
    private Button owner;
    private TextView selectionCount;
    private Button picturesTab;
    private Button videosTab;
    private Button compareButton;
    private Button earFilter;
    private Button monthIndex;
    private Button selectAll;
    private Button selectButton;
    private Button shareButton;
    private Button deleteButton;
    private LinearLayout selectionActions;
    private boolean videos;
    private boolean selecting;
    private EarSide filteredEar;
    private Dialog mediaDialog;
    private File[] captures = {};
    private final ArrayList<File> photosInProfile = new ArrayList<>();
    private final Set<File> selected = new LinkedHashSet<>();
    private final LruCache<File, Thumbnail> thumbnails = new LruCache<>(36);
    private final Set<File> loading = new HashSet<>();
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Tiles tiles = new Tiles();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.apply(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        CareProfiles profiles = new CareProfiles(this);
        profile = profiles.resolve(getIntent().getStringExtra(CareProfiles.EXTRA));
        if (state != null) {
            videos = state.getBoolean("videos");
            profile = profiles.resolve(state.getString("profile", profile));
            int filter = state.getInt("earFilter", -1);
            if (filter >= 0 && filter < EarSide.values().length) filteredEar = EarSide.values()[filter];
        }
        LinearLayout page = style.page();
        style.fit(this, page, 16, 12);
        Button back = style.button("‹");
        back.setContentDescription(getString(R.string.back_to_preview));
        back.setOnClickListener(view -> finish());
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        owner = style.button("");
        owner.setSingleLine(true);
        owner.setEllipsize(TextUtils.TruncateAt.END);
        owner.setText(getString(R.string.profile_album, profiles.name(profile)));
        owner.setContentDescription(getString(R.string.profile_switch, profiles.name(profile)));
        owner.setOnClickListener(view -> style.showProfiles(profiles, profile, id -> {
            if (id.equals(profile)) return;
            profile = id;
            profiles.select(id);
            owner.setText(getString(R.string.profile_album, profiles.name(id)));
            owner.setContentDescription(getString(R.string.profile_switch, profiles.name(id)));
            selected.clear();
            selecting = false;
            load();
            list.setSelection(0);
        }, null, null));
        FrameLayout center = new FrameLayout(this);
        center.addView(owner, new FrameLayout.LayoutParams(-2, dp(44), Gravity.CENTER));
        LinearLayout.LayoutParams centerSize = new LinearLayout.LayoutParams(0, dp(44), 1);
        centerSize.setMargins(dp(8), 0, dp(8), 0);
        header.addView(center, centerSize);
        selectButton = style.button(getString(R.string.select));
        selectButton.setOnClickListener(view -> {
            selecting = !selecting;
            selected.clear();
            updateSelection();
        });
        header.addView(selectButton);
        page.addView(header);
        TextView title = style.label(getString(R.string.media_title), 30, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        compareButton = style.button(getString(R.string.ear_compare));
        compareButton.setOnClickListener(view -> chooseComparison());
        titleRow.addView(compareButton);
        style.add(page, titleRow, 18);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(style.shape(ScopeUi.GLASS, 23, ScopeUi.EDGE));
        picturesTab = style.button(getString(R.string.photo));
        picturesTab.setOnClickListener(view -> showTab(false));
        videosTab = style.button(getString(R.string.video));
        videosTab.setOnClickListener(view -> showTab(true));
        tabs.addView(picturesTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        tabs.addView(videosTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        style.add(page, tabs, 12);
        LinearLayout filters = new LinearLayout(this);
        earFilter = style.button("");
        earFilter.setOnClickListener(view -> chooseEarFilter());
        filters.addView(earFilter, new LinearLayout.LayoutParams(0, dp(42), 1));
        monthIndex = style.button(getString(R.string.month_index));
        monthIndex.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.month_index).setItems(tiles.months(), (dialog, index) ->
                        list.setSelection(tiles.getPositionForSection(index)))
                .setNegativeButton(R.string.cancel, null).show());
        filters.addView(monthIndex, new LinearLayout.LayoutParams(-2, dp(42)));
        style.add(page, filters, 6);
        empty = style.label("", 15, ScopeUi.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(5), 1);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setFastScrollEnabled(true);
        list.setItemsCanFocus(true);
        list.setAdapter(tiles);
        FrameLayout body = new FrameLayout(this);
        body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        selectionActions = new LinearLayout(this);
        selectionActions.setGravity(Gravity.CENTER_VERTICAL);
        selectionActions.setPadding(dp(16), dp(7), dp(8), dp(7));
        selectionActions.setBackground(style.shape(ScopeUi.GLASS, 32, ScopeUi.EDGE));
        selectionCount = style.label("", 14, TEXT);
        selectionActions.addView(selectionCount, new LinearLayout.LayoutParams(0, -2, 1));
        selectAll = style.button(getString(R.string.select_all));
        selectAll.setOnClickListener(view -> {
            if (selected.size() == captures.length) selected.clear();
            else selected.addAll(Arrays.asList(captures));
            updateSelection();
        });
        selectionActions.addView(selectAll);
        shareButton = style.button(getString(R.string.share));
        shareButton.setOnClickListener(view -> share(selected.toArray(new File[0])));
        deleteButton = style.button(getString(R.string.delete));
        deleteButton.setBackground(style.pill(0x58ff453a, 22, 0));
        deleteButton.setTextColor(0xffffb4ae);
        deleteButton.setOnClickListener(view -> confirmDelete(selected.toArray(new File[0])));
        selectionActions.addView(shareButton);
        LinearLayout.LayoutParams deleteSize = new LinearLayout.LayoutParams(-2, dp(44));
        deleteSize.leftMargin = dp(8);
        selectionActions.addView(deleteButton, deleteSize);
        style.add(page, selectionActions, 8);
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("videos", videos);
        state.putString("profile", profile);
        state.putInt("earFilter", filteredEar == null ? -1 : filteredEar.ordinal());
        super.onSaveInstanceState(state);
    }

    @Override
    public void onBackPressed() {
        if (!selecting) {
            super.onBackPressed();
            return;
        }
        selecting = false;
        selected.clear();
        updateSelection();
    }

    @Override
    protected void onStop() {
        if (mediaDialog != null) mediaDialog.dismiss();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        File[] files = CaptureProvider.directory(this, profile).listFiles(
                file -> file.isFile() && CaptureProvider.isCapture(file.getName()));
        if (files == null) files = new File[0];
        ArrayList<File> visible = new ArrayList<>();
        photosInProfile.clear();
        int videoCount = 0;
        for (File file : files) {
            boolean video = isVideo(file);
            if (video) videoCount++;
            else photosInProfile.add(file);
            if (video == videos && (video || filteredEar == null
                    || EarSide.read(this, file) == filteredEar)) visible.add(file);
        }
        captures = visible.toArray(new File[0]);
        picturesTab.setText(getString(R.string.photos_count, files.length - videoCount));
        videosTab.setText(getString(R.string.videos_count, videoCount));
        Arrays.sort(captures, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        selected.retainAll(Arrays.asList(captures));
        empty.setText(!videos && filteredEar != null ? getString(R.string.no_filter_results)
                : (videos ? getString(R.string.empty_videos) : getString(R.string.empty_photos))
                        + "\n" + getString(R.string.empty_capture_hint));
        empty.setVisibility(captures.length == 0 ? View.VISIBLE : View.GONE);
        styleTab(picturesTab, !videos);
        styleTab(videosTab, videos);
        compareButton.setVisibility(videos ? View.GONE : View.VISIBLE);
        earFilter.setVisibility(videos ? View.GONE : View.VISIBLE);
        earFilter.setText(getString(R.string.ear_filter,
                filteredEar == null ? getString(R.string.all_photos) : filteredEar.label(this)));
        tiles.group();
        monthIndex.setEnabled(captures.length > 0);
        updateSelection();
    }

    private void chooseEarFilter() {
        EarSide[] sides = EarSide.values();
        String[] options = {getString(R.string.all_photos), sides[1].label(this),
                sides[2].label(this), sides[0].label(this)};
        EarSide[] values = {null, EarSide.LEFT, EarSide.RIGHT, EarSide.NONE};
        new AlertDialog.Builder(this).setTitle(R.string.filter_title)
                .setSingleChoiceItems(options, Arrays.asList(values).indexOf(filteredEar), (dialog, which) -> {
                    filteredEar = values[which];
                    selected.clear();
                    selecting = false;
                    load();
                    list.setSelection(0);
                    dialog.dismiss();
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void showTab(boolean showVideos) {
        if (videos == showVideos) return;
        videos = showVideos;
        selected.clear();
        selecting = false;
        load();
        list.setSelection(0);
    }

    private void styleTab(Button tab, boolean active) {
        tab.setSelected(active);
        tab.setBackground(style.pill(active ? 0x48ffffff : 0, 20, 0));
        tab.setTextColor(active ? TEXT : ScopeUi.MUTED);
    }

    private void toggle(File file) {
        if (!selected.remove(file)) selected.add(file);
        updateSelection();
    }

    private void updateSelection() {
        if (captures.length == 0) selecting = false;
        selectButton.setEnabled(captures.length > 0);
        selectButton.setText(selecting ? getString(R.string.done) : getString(R.string.select));
        selectionCount.setText(getString(R.string.selection_count, selected.size()));
        selectAll.setText(selected.size() == captures.length
                ? getString(R.string.clear_selection) : getString(R.string.select_all));
        selectionActions.setVisibility(selecting ? View.VISIBLE : View.GONE);
        shareButton.setAlpha(selected.isEmpty() ? .5f : 1f);
        deleteButton.setAlpha(selected.isEmpty() ? .5f : 1f);
        shareButton.setEnabled(!selected.isEmpty());
        deleteButton.setEnabled(!selected.isEmpty());
        tiles.notifyDataSetChanged();
    }

    private final class Tiles extends BaseAdapter implements SectionIndexer {
        private final ArrayList<Object> rows = new ArrayList<>();
        private final ArrayList<String> months = new ArrayList<>();
        private final ArrayList<Integer> headings = new ArrayList<>();

        void group() {
            rows.clear();
            months.clear();
            headings.clear();
            Locale locale = getResources().getConfiguration().locale;
            SimpleDateFormat format = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale);
            Calendar date = Calendar.getInstance();
            int lastMonth = -1;
            ArrayList<File> line = null;
            for (File file : captures) {
                date.setTimeInMillis(file.lastModified());
                int month = date.get(Calendar.YEAR) * 12 + date.get(Calendar.MONTH);
                if (lastMonth != month) {
                    String title = format.format(date.getTime());
                    headings.add(rows.size());
                    months.add(title);
                    rows.add(title);
                    lastMonth = month;
                    line = null;
                }
                if (line == null || line.size() == COLUMNS) {
                    line = new ArrayList<>(COLUMNS);
                    rows.add(line);
                }
                line.add(file);
            }
        }

        String[] months() { return months.toArray(new String[0]); }
        @Override public Object[] getSections() { return months(); }
        @Override public int getPositionForSection(int section) {
            return section >= 0 && section < headings.size() ? headings.get(section) : 0;
        }
        @Override public int getSectionForPosition(int position) {
            int section = java.util.Collections.binarySearch(headings, position);
            return section >= 0 ? section : Math.max(0, -section - 2);
        }
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return getItem(position) instanceof String ? 0 : 1; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            Object item = getItem(position);
            if (item instanceof String) {
                TextView heading = recycled instanceof TextView ? (TextView) recycled : style.label("", 16, TEXT);
                heading.setText((String) item);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setPadding(0, dp(14), 0, dp(8));
                return heading;
            }
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(GalleryActivity.this);
            List<?> files = (List<?>) item;
            if (row.getChildCount() == 0) {
                row.setPadding(0, 0, 0, dp(9));
                for (int i = 0; i < COLUMNS; i++) {
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                    size.leftMargin = i == 0 ? 0 : dp(8);
                    row.addView(new Tile(), size);
                }
            }
            for (int i = 0; i < COLUMNS; i++) {
                Tile tile = (Tile) row.getChildAt(i);
                tile.setVisibility(i < files.size() ? View.VISIBLE : View.INVISIBLE);
                if (i < files.size()) bind(tile, (File) files.get(i));
            }
            return row;
        }

        private void bind(Tile tile, File file) {
            tile.setOnClickListener(view -> {
                if (selecting) toggle(file);
                else open(file);
            });
            tile.setOnLongClickListener(view -> {
                selecting = true;
                toggle(file);
                return true;
            });
            String stamp = stamp(file);
            EarSide side = isVideo(file) ? EarSide.NONE : EarSide.read(GalleryActivity.this, file);
            tile.caption.setText(side == EarSide.NONE ? stamp : side.label(GalleryActivity.this) + " · " + stamp);
            boolean checked = selected.contains(file);
            tile.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            tile.check.setText(checked ? "✓" : "○");
            tile.check.setTextColor(checked ? ScopeUi.INK : TEXT);
            tile.check.setBackground(style.shape(checked ? ACCENT : 0xb5070b12, 12, 0));
            tile.setBackground(style.shape(checked ? 0x397dd3fc : 0x16ffffff, 19,
                    checked ? ACCENT : 0x30ffffff));
            tile.setContentDescription(label(file) + " " + stamp
                    + (selecting ? " " + getString(checked ? R.string.selected_description : R.string.not_selected_description) : ""));
            Thumbnail thumbnail = thumbnails.get(file);
            tile.image.setImageBitmap(thumbnail == null ? null : thumbnail.bitmap);
            tile.image.setClipToOutline(!CaptureProvider.isMacro(file));
            tile.image.setBackground(CaptureProvider.isMacro(file) ? null : tile.circleBackground);
            String duration = thumbnail == null ? null : thumbnail.duration;
            tile.duration.setVisibility(duration == null ? View.GONE : View.VISIBLE);
            tile.duration.setText(duration);
            if (thumbnail == null) loadThumbnail(file);
        }
    }

    private final class Tile extends LinearLayout {
        final FrameLayout cover = new FrameLayout(GalleryActivity.this);
        final ImageView image = new ImageView(GalleryActivity.this);
        final TextView duration = new TextView(GalleryActivity.this);
        final TextView check = new TextView(GalleryActivity.this);
        final TextView caption = new TextView(GalleryActivity.this);
        final android.graphics.drawable.Drawable circleBackground = style.shape(0xff0a0f16, 100, 0);

        Tile() {
            super(GalleryActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(7), dp(7), dp(7), dp(7));
            addView(cover, new LinearLayout.LayoutParams(-1, dp(94)));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(circleBackground);
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            image.setClipToOutline(true);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            duration.setTextColor(TEXT);
            duration.setTextSize(11);
            duration.setBackground(style.shape(0xd31b2132, 12, ScopeUi.EDGE));
            duration.setPadding(dp(6), dp(2), dp(6), dp(2));
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.BOTTOM | Gravity.END);
            cover.addView(duration, badge);
            check.setTextSize(14);
            check.setGravity(Gravity.CENTER);
            cover.addView(check, new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.TOP | Gravity.END));
            caption.setTextColor(ScopeUi.MUTED);
            caption.setTextSize(11);
            caption.setMaxLines(2);
            caption.setPadding(dp(2), dp(5), dp(2), 0);
            addView(caption);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            int size = Math.min(Math.max(0, width), dp(145));
            cover.getLayoutParams().height = size;
            image.getLayoutParams().width = size;
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private String stamp(File file) {
        Date date = new Date(file.lastModified());
        return DateFormat.getMediumDateFormat(this).format(date) + " "
                + DateFormat.getTimeFormat(this).format(date);
    }

    private void loadThumbnail(File file) {
        if (!loading.add(file)) return;
        decoder.execute(() -> {
            Thumbnail result = readThumbnail(file);
            runOnUiThread(() -> {
                loading.remove(file);
                if (!isDestroyed() && file.isFile()) {
                    thumbnails.put(file, result);
                    tiles.notifyDataSetChanged();
                }
            });
        });
    }

    private static Thumbnail readThumbnail(File file) {
        if (!isVideo(file)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            return new Thumbnail(BitmapFactory.decodeFile(file.getPath(), options), null);
        }
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        Bitmap image = null;
        String duration = null;
        try {
            reader.setDataSource(file.getPath());
            String millis = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (millis != null) duration = formatTime((Long.parseLong(millis) + 500) / 1000);
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                image = reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240);
            } else {
                image = reader.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
        } catch (RuntimeException ignored) {
            // A damaged file can still be listed and deleted.
        } finally {
            try {
                reader.release();
            } catch (Exception ignored) {
            }
        }
        return new Thumbnail(image, duration);
    }

    static String formatTime(long seconds) {
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static final class Thumbnail {
        final Bitmap bitmap;
        final String duration;

        Thumbnail(Bitmap bitmap, String duration) {
            this.bitmap = bitmap;
            this.duration = duration;
        }
    }

    private void open(File file) {
        boolean video = isVideo(file);
        VideoPlayback playback = video ? new VideoPlayback(this, CaptureProvider.uri(file),
                !CaptureProvider.isMacro(file), () -> Toast.makeText(this, R.string.playback_error, Toast.LENGTH_SHORT).show()) : null;
        View media = playback == null ? photoView(file) : playback;
        Dialog dialog = new Dialog(this);
        LinearLayout content = style.page();
        content.setPadding(dp(16), dp(12), dp(16), dp(16));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = style.button("‹");
        back.setContentDescription(getString(R.string.back_to_media));
        back.setOnClickListener(view -> dialog.dismiss());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView type = style.label(label(file), 16, TEXT);
        type.setGravity(Gravity.CENTER);
        header.addView(type, new LinearLayout.LayoutParams(0, -2, 1));
        Button share = style.button(getString(R.string.share));
        share.setOnClickListener(view -> share(file));
        header.addView(share);
        content.addView(header);
        if (!video && !CaptureProvider.isMacro(file)) {
            Button side = style.button(EarSide.read(this, file).label(this));
            side.setOnClickListener(view -> chooseSide(file, side));
            style.add(content, side, 6);
        }
        FrameLayout stage = new FrameLayout(this);
        content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        stage.addView(media, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        TextView date = style.label(stamp(file), 17, TEXT);
        date.setGravity(Gravity.CENTER);
        style.add(content, date, 8);
        PlaybackControls controls = playback == null ? null : new PlaybackControls(playback.player);
        if (controls != null) style.add(content, controls, 14);
        Button delete = style.button(getString(R.string.delete));
        delete.setTextColor(0xffffb4ae);
        delete.setOnClickListener(view -> {
            dialog.dismiss();
            confirmDelete(file);
        });
        LinearLayout.LayoutParams deleteSize = new LinearLayout.LayoutParams(-2, dp(44));
        deleteSize.gravity = Gravity.CENTER_HORIZONTAL;
        deleteSize.topMargin = dp(12);
        content.addView(delete, deleteSize);
        dialog.setContentView(content);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        mediaDialog = dialog;
        dialog.setOnDismissListener(window -> {
            if (mediaDialog == dialog) mediaDialog = null;
            if (controls != null) controls.close();
            if (playback != null) playback.close();
        });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
    }

    private void chooseSide(File file, Button button) {
        EarSide[] sides = EarSide.values();
        String[] labels = EarSide.labels(this);
        new AlertDialog.Builder(this).setTitle(R.string.ear_choose)
                .setSingleChoiceItems(labels, EarSide.read(this, file).ordinal(), (dialog, which) -> {
                    if (file.isFile()) {
                        sides[which].save(this, file);
                        button.setText(labels[which]);
                        load();
                    }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void chooseComparison() {
        EarSide[] sides = {EarSide.LEFT, EarSide.RIGHT};
        new AlertDialog.Builder(this).setTitle(R.string.ear_compare)
                .setItems(new String[]{sides[0].label(this), sides[1].label(this)}, (dialog, which) -> {
                    EarSide side = sides[which];
                    ArrayList<File> photos = new ArrayList<>();
                    for (File file : photosInProfile) {
                        if (EarSide.read(this, file) == side) photos.add(file);
                    }
                    photos.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
                    if (photos.size() < 2) {
                        new AlertDialog.Builder(this).setMessage(R.string.ear_compare_need_two)
                                .setPositiveButton(android.R.string.ok, null).show();
                    } else {
                        pickComparisonPhoto(side, photos, null);
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickComparisonPhoto(EarSide side, ArrayList<File> photos, File first) {
        String[] dates = new String[photos.size()];
        for (int i = 0; i < dates.length; i++) dates[i] = stamp(photos.get(i));
        new AlertDialog.Builder(this)
                .setTitle(first == null ? R.string.ear_compare_first : R.string.ear_compare_second)
                .setItems(dates, (dialog, which) -> {
                    File picked = photos.remove(which);
                    if (first == null) pickComparisonPhoto(side, photos, picked);
                    else showComparison(side, first, picked);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showComparison(EarSide side, File first, File second) {
        File older = first.lastModified() <= second.lastModified() ? first : second;
        File newer = older.equals(first) ? second : first;
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(12), dp(8), dp(12), 0);
        for (File file : new File[]{older, newer}) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(dp(4), 0, dp(4), 0);
            ImageView image = photoView(file);
            image.setContentDescription(stamp(file));
            column.addView(image, new LinearLayout.LayoutParams(-1, dp(170)));
            TextView date = style.label(stamp(file), 13, TEXT);
            date.setGravity(Gravity.CENTER);
            column.addView(date);
            row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        }
        new AlertDialog.Builder(this).setTitle(getString(R.string.ear_compare_side, side.label(this)))
                .setView(row).setPositiveButton(android.R.string.ok, null).show();
    }

    private ImageView photoView(File file) {
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setImageBitmap(BitmapFactory.decodeFile(file.getPath()));
        if (!CaptureProvider.isMacro(file)) {
            picture.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int size = Math.min(view.getWidth(), view.getHeight());
                    int left = (view.getWidth() - size) / 2;
                    int top = (view.getHeight() - size) / 2;
                    if (size > 0) outline.setOval(left, top, left + size, top + size);
                }
            });
            picture.setClipToOutline(true);
        }
        return picture;
    }

    private final class PlaybackControls extends LinearLayout {
        private final MediaPlayer player;
        private final SeekBar position = new SeekBar(GalleryActivity.this);
        private final TextView time = new TextView(GalleryActivity.this);
        private final Button play = style.button(getString(R.string.play));
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int duration;
        private boolean dragging;
        private boolean ended;
        private final Runnable refresh = this::update;

        PlaybackControls(MediaPlayer player) {
            super(GalleryActivity.this);
            this.player = player;
            setOrientation(VERTICAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            setBackground(style.shape(ScopeUi.GLASS, 28, ScopeUi.EDGE));
            position.setProgressTintList(ColorStateList.valueOf(ACCENT));
            position.setThumbTintList(ColorStateList.valueOf(ACCENT));
            position.setContentDescription(getString(R.string.playback_progress));
            position.setEnabled(false);
            position.setSplitTrack(false);
            addView(position, new LinearLayout.LayoutParams(-1, dp(38)));

            LinearLayout actions = new LinearLayout(GalleryActivity.this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            play.setText(getString(R.string.play));
            play.setEnabled(false);
            play.setOnClickListener(view -> {
                if (player.isPlaying()) {
                    player.pause();
                } else {
                    if (ended) player.seekTo(0);
                    ended = false;
                    player.start();
                }
                update();
            });
            actions.addView(play);
            time.setTextColor(ScopeUi.MUTED);
            time.setGravity(Gravity.END);
            actions.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
            addView(actions, new LinearLayout.LayoutParams(-1, -2));
            showTime(0);

            position.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onStartTrackingTouch(SeekBar bar) { dragging = true; }

                @Override
                public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    if (!fromUser) return;
                    showTime(value);
                    if (!dragging) seek(value);
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    seek(bar.getProgress());
                    dragging = false;
                    handler.removeCallbacks(refresh);
                    handler.postDelayed(refresh, 250);
                }
            });
            player.setOnPreparedListener(media -> {
                duration = Math.max(0, player.getDuration());
                position.setMax(duration);
                position.setEnabled(duration > 0);
                play.setEnabled(true);
                player.start();
                update();
            });
            player.setOnCompletionListener(media -> {
                ended = true;
                update();
            });
            player.setOnErrorListener((media, what, extra) -> {
                handler.removeCallbacks(refresh);
                position.setEnabled(false);
                play.setEnabled(false);
                time.setText(getString(R.string.playback_error));
                return true;
            });
        }

        private void seek(int value) {
            ended = false;
            player.seekTo(value);
        }

        private void showTime(int millis) {
            long elapsed = millis >= duration ? (duration + 500L) / 1000 : millis / 1000;
            time.setText(formatTime(elapsed) + " / " + formatTime((duration + 500L) / 1000));
        }

        private void update() {
            handler.removeCallbacks(refresh);
            if (!dragging) {
                int current = ended ? duration : Math.max(0, player.getCurrentPosition());
                position.setProgress(current);
                showTime(current);
            }
            play.setText(player.isPlaying() ? getString(R.string.pause) : getString(R.string.play));
            if (!ended) handler.postDelayed(refresh, 200);
        }

        void close() {
            handler.removeCallbacks(refresh);
        }
    }

    private void share(File... files) {
        if (files.length == 0) return;
        ArrayList<Uri> uris = new ArrayList<>(files.length);
        for (File file : files) uris.add(CaptureProvider.uri(file));
        boolean multiple = files.length > 1;
        Intent intent = new Intent(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        intent.setType(CaptureProvider.mime(files[0]));
        if (multiple) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        else intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        ClipData clip = ClipData.newUri(getContentResolver(), getString(R.string.share_label), uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_title)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, getString(R.string.share_unavailable), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(File... files) {
        if (files.length == 0) return;
        new AlertDialog.Builder(this).setTitle(getString(R.string.delete_count, files.length))
                .setMessage(getString(R.string.delete_warning))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.delete), (dialog, which) -> {
                    int failures = 0;
                    for (File file : files) {
                        if (file.delete() || !file.exists()) {
                            if (!isVideo(file)) EarSide.NONE.save(this, file);
                            revokeUriPermission(CaptureProvider.uri(file), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            thumbnails.remove(file);
                            selected.remove(file);
                        } else {
                            failures++;
                        }
                    }
                    if (failures > 0) {
                        Toast.makeText(this, getString(R.string.delete_failed), Toast.LENGTH_SHORT).show();
                    } else selecting = false;
                    load();
                }).show();
    }

    private String label(File file) {
        if (isVideo(file)) return getString(R.string.video);
        if (CaptureProvider.isMacro(file)) return getString(R.string.macro_photo);
        return getString(R.string.photo);
    }

    private static boolean isVideo(File file) {
        return file.getName().endsWith(".mp4");
    }

    private int dp(int size) {
        return style.dp(size);
    }
}
