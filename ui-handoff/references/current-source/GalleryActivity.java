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
import android.view.MotionEvent;
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
import java.io.FileOutputStream;
import java.io.IOException;
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
    private final ScopeUi style = new ScopeUi(this);
    private static final int COLUMNS = 3;
    private ListView list;
    private TextView empty;
    private String profile;
    private Button owner;
    private TextView selectionCount;
    private Button picturesTab;
    private Button videosTab;
    private Button selectAll;
    private Button selectButton;
    private Button shareButton;
    private Button deleteButton;
    private LinearLayout selectionActions;
    private boolean videos;
    private boolean selecting;
    private EarSide filteredEar;
    private int filteredYear;
    private int filteredMonth;
    private Button filterButton;
    private TextView filterSummary;
    private Dialog mediaDialog;
    private Dialog markupDialog;
    private File[] captures = {};
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
        style.load();
        setTheme(style.light ? android.R.style.Theme_Material_Light_NoActionBar
                : android.R.style.Theme_Material_NoActionBar);
        super.onCreate(state);
        CareProfiles profiles = new CareProfiles(this);
        profile = profiles.resolve(getIntent().getStringExtra(CareProfiles.EXTRA));
        if (state != null) {
            videos = state.getBoolean("videos");
            filteredYear = state.getInt("filterYear");
            filteredMonth = state.getInt("filterMonth");
            profile = profiles.resolve(state.getString("profile", profile));
            int filter = state.getInt("earFilter", -1);
            if (filter >= 0 && filter < EarSide.values().length) filteredEar = EarSide.values()[filter];
        }
        LinearLayout page = style.page();
        style.fit(this, page, 16, 12);
        Button back = style.iconButton("back", getString(R.string.back));
        back.setContentDescription(getString(R.string.back_to_preview));
        back.setOnClickListener(view -> finish());
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        owner = style.button("");
        owner.setSingleLine(true);
        owner.setEllipsize(TextUtils.TruncateAt.END);
        owner.setText(profiles.name(profile));
        owner.setContentDescription(getString(R.string.profile_switch, profiles.name(profile)));
        owner.setOnClickListener(view -> style.showProfiles(profiles, profile, id -> {
            if (id.equals(profile)) return;
            profile = id;
            profiles.select(id);
            owner.setText(profiles.name(id));
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
        shareButton = style.iconButton("share", getString(R.string.share));
        shareButton.setOnClickListener(view -> share(selected.toArray(new File[0])));
        header.addView(shareButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(style.shape(style.GLASS, 23, style.EDGE));
        picturesTab = style.button(getString(R.string.photo));
        picturesTab.setOnClickListener(view -> showTab(false));
        videosTab = style.button(getString(R.string.video));
        videosTab.setOnClickListener(view -> showTab(true));
        tabs.addView(picturesTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        tabs.addView(videosTab, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams chooseSize = new LinearLayout.LayoutParams(dp(72), dp(48));
        chooseSize.rightMargin = dp(10);
        controls.addView(selectButton, chooseSize);
        controls.addView(tabs, new LinearLayout.LayoutParams(0, -2, 1));
        filterButton = style.button(getString(R.string.filter_title));
        filterButton.setOnClickListener(view -> chooseFilters());
        LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(dp(72), dp(48));
        filterSize.leftMargin = dp(10);
        controls.addView(filterButton, filterSize);
        style.add(page, controls, 12);
        filterSummary = style.label("", 12, style.MUTED);
        filterSummary.setPadding(dp(6), dp(6), dp(6), dp(4));
        page.addView(filterSummary);
        empty = style.label("", 15, style.MUTED);
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
        selectionActions.setBackground(style.shape(style.GLASS, 32, style.EDGE));
        selectionCount = style.label("", 14, style.TEXT);
        selectionActions.addView(selectionCount, new LinearLayout.LayoutParams(0, -2, 1));
        selectAll = style.button(getString(R.string.select_all));
        selectAll.setOnClickListener(view -> {
            if (selected.size() == captures.length) selected.clear();
            else selected.addAll(Arrays.asList(captures));
            updateSelection();
        });
        selectionActions.addView(selectAll, new LinearLayout.LayoutParams(0, dp(48), 1));
        deleteButton = style.iconButton("delete", getString(R.string.delete));
        deleteButton.setBackground(style.pill(0x58ff453a, 22, 0));
        deleteButton.setTextColor(style.light ? style.RED : 0xffffb4ae);
        deleteButton.setOnClickListener(view -> confirmDelete(selected.toArray(new File[0])));

        LinearLayout.LayoutParams deleteSize = new LinearLayout.LayoutParams(0, dp(48), 1);
        deleteSize.leftMargin = dp(8);
        FrameLayout deleteSlot = new FrameLayout(this);
        deleteSlot.addView(deleteButton, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END));
        selectionActions.addView(deleteSlot, deleteSize);
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
        state.putInt("filterYear", filteredYear);
        state.putInt("filterMonth", filteredMonth);
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
        if (markupDialog != null) markupDialog.dismiss();
        if (mediaDialog != null) mediaDialog.dismiss();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        decoder.shutdownNow();
        super.onDestroy();
    }

    private File[] profileFiles() {
        File[] files = CaptureProvider.directory(this, profile).listFiles(
                file -> file.isFile() && CaptureProvider.isCapture(file.getName()));
        return files == null ? new File[0] : files;
    }

    private boolean matchesFilters(File file) {
        EarSide side = isVideo(file) ? EarSide.NONE : EarSide.read(this, file);
        if (filteredEar != null && side != filteredEar) return false;
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(file.lastModified());
        return (filteredYear == 0 || date.get(Calendar.YEAR) == filteredYear)
                && (filteredMonth == 0 || date.get(Calendar.MONTH) + 1 == filteredMonth);
    }

    private void load() {
        ArrayList<File> visible = new ArrayList<>();
        int videoCount = 0;
        int photoCount = 0;
        for (File file : profileFiles()) {
            if (!matchesFilters(file)) continue;
            boolean video = isVideo(file);
            if (video) videoCount++;
            else photoCount++;
            if (video == videos) visible.add(file);
        }
        captures = visible.toArray(new File[0]);
        picturesTab.setText(getString(R.string.photos_count, photoCount));
        videosTab.setText(getString(R.string.videos_count, videoCount));
        Arrays.sort(captures, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        selected.retainAll(Arrays.asList(captures));
        String summary = filterDescription();
        empty.setText(summary.isEmpty() ? (videos ? getString(R.string.empty_videos) : getString(R.string.empty_photos))
                + "\n" + getString(R.string.empty_capture_hint) : getString(R.string.no_filter_results));
        empty.setVisibility(captures.length == 0 ? View.VISIBLE : View.GONE);
        filterSummary.setText(summary);
        filterSummary.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        filterButton.setText(getString(R.string.filter_title) + (summary.isEmpty() ? "" : " ·"));
        filterButton.setContentDescription(getString(R.string.filter_title) + " " + summary);
        styleTab(picturesTab, !videos);
        styleTab(videosTab, videos);
        tiles.group();
        updateSelection();
    }

    private String filterDescription() {
        ArrayList<String> parts = new ArrayList<>();
        if (filteredYear != 0) parts.add(String.valueOf(filteredYear));
        if (filteredMonth != 0) parts.add(getString(R.string.filter_month_value, filteredMonth));
        if (filteredEar != null) parts.add(filteredEar.label(this));
        return TextUtils.join(" · ", parts);
    }

    private void applyFilters(Button year, Button month, Button ear) {
        year.setText(getString(R.string.filter_year) + " · "
                + (filteredYear == 0 ? getString(R.string.all_photos) : String.valueOf(filteredYear)));
        month.setText(getString(R.string.filter_month) + " · "
                + (filteredMonth == 0 ? getString(R.string.all_photos) : getString(R.string.filter_month_value, filteredMonth)));
        ear.setText(getString(R.string.ear_choose) + " · "
                + (filteredEar == null ? getString(R.string.all_photos) : filteredEar.label(this)));
        selected.clear();
        selecting = false;
        load();
        list.setSelection(0);
    }

    private void chooseFilters() {
        Dialog sheet = new Dialog(this);
        LinearLayout page = style.page();
        page.setPadding(dp(20), dp(20), dp(20), dp(24));
        page.addView(style.label(getString(R.string.filter_title), 22, style.TEXT));
        Button year = style.button("");
        Button month = style.button("");
        Button ear = style.button("");
        style.add(page, year, 16);
        style.add(page, month, 8);
        style.add(page, ear, 8);
        year.setOnClickListener(view -> {
            java.util.TreeSet<Integer> years = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
            for (File file : profileFiles()) {
                Calendar date = Calendar.getInstance();
                date.setTimeInMillis(file.lastModified());
                years.add(date.get(Calendar.YEAR));
            }
            if (filteredYear != 0) years.add(filteredYear);
            ArrayList<Integer> values = new ArrayList<>();
            values.add(0);
            values.addAll(years);
            String[] labels = new String[values.size()];
            labels[0] = getString(R.string.all_photos);
            for (int i = 1; i < labels.length; i++) labels[i] = String.valueOf(values.get(i));
            new ScopeDialog.Builder(this).setTitle(R.string.filter_year)
                    .setSingleChoiceItems(labels, values.indexOf(filteredYear), (dialog, which) -> {
                        filteredYear = values.get(which);
                        applyFilters(year, month, ear);
                        dialog.dismiss();
                    }).setNegativeButton(R.string.cancel, null).show();
        });
        month.setOnClickListener(view -> {
            String[] labels = new String[13];
            labels[0] = getString(R.string.all_photos);
            for (int i = 1; i <= 12; i++) labels[i] = getString(R.string.filter_month_value, i);
            new ScopeDialog.Builder(this).setTitle(R.string.filter_month)
                    .setSingleChoiceItems(labels, filteredMonth, (dialog, which) -> {
                        filteredMonth = which;
                        applyFilters(year, month, ear);
                        dialog.dismiss();
                    }).setNegativeButton(R.string.cancel, null).show();
        });
        ear.setOnClickListener(view -> {
            EarSide[] values = {null, EarSide.LEFT, EarSide.RIGHT, EarSide.NONE};
            String[] labels = {getString(R.string.all_photos), EarSide.LEFT.label(this),
                    EarSide.RIGHT.label(this), EarSide.NONE.label(this)};
            new ScopeDialog.Builder(this).setTitle(R.string.ear_choose)
                    .setSingleChoiceItems(labels, Arrays.asList(values).indexOf(filteredEar), (dialog, which) -> {
                        filteredEar = values[which];
                        applyFilters(year, month, ear);
                        dialog.dismiss();
                    }).setNegativeButton(R.string.cancel, null).show();
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button clear = style.button(getString(R.string.clear_filters));
        clear.setGravity(Gravity.CENTER);
        clear.setOnClickListener(view -> {
            filteredYear = filteredMonth = 0;
            filteredEar = null;
            applyFilters(year, month, ear);
        });
        Button done = style.button(getString(R.string.done));
        done.setGravity(Gravity.CENTER);
        done.setOnClickListener(view -> sheet.dismiss());
        LinearLayout.LayoutParams clearSize = new LinearLayout.LayoutParams(0, dp(48), 1);
        clearSize.rightMargin = dp(8);
        actions.addView(clear, clearSize);
        actions.addView(done, new LinearLayout.LayoutParams(0, dp(48), 1));
        style.add(page, actions, 18);
        applyFilters(year, month, ear);
        sheet.setContentView(page);
        sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sheet.show();
        sheet.getWindow().setGravity(Gravity.BOTTOM);
        sheet.getWindow().setLayout(-1, -2);
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
        tab.setTextColor(active ? style.TEXT : style.MUTED);
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
                TextView heading = recycled instanceof TextView ? (TextView) recycled : style.label("", 16, style.TEXT);
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
            tile.caption.setVisibility(View.GONE);
            boolean checked = selected.contains(file);
            tile.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            tile.check.setText(checked ? "✓" : "○");
            tile.check.setTextColor(checked ? style.INK : style.TEXT);
            tile.check.setBackground(style.shape(checked ? style.ACCENT : 0xb5070b12, 12, 0));
            tile.setBackground(style.shape(checked ? 0x397dd3fc : 0x16ffffff, 19,
                    checked ? style.ACCENT : 0x30ffffff));
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
            duration.setTextColor(style.TEXT);
            duration.setTextSize(11);
            duration.setBackground(style.shape(0xd31b2132, 12, style.EDGE));
            duration.setPadding(dp(6), dp(2), dp(6), dp(2));
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.BOTTOM | Gravity.END);
            cover.addView(duration, badge);
            check.setTextSize(14);
            check.setGravity(Gravity.CENTER);
            cover.addView(check, new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.TOP | Gravity.END));
            caption.setTextColor(style.MUTED);
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
        new MediaDetails(file).show();
    }

    private final class MediaDetails {
        private File current;
        private final ArrayList<File> photos = new ArrayList<>();
        private int index;
        private ImageView picture;
        private TextView type, date;
        private Button side;
        private FrameLayout stage;
        private PhotoPager pager;

        MediaDetails(File file) {
            current = file;
            for (File candidate : captures) if (!isVideo(candidate)) photos.add(candidate);
            index = photos.indexOf(file);
            if (index < 0 && !isVideo(file)) {
                photos.add(file);
                index = photos.size() - 1;
            }
        }

        private boolean navigate(int direction) {
            return pager != null && pager.page(direction);
        }

        private int nextIndex(int direction) {
            int next = index + direction;
            while (next >= 0 && next < photos.size() && !photos.get(next).isFile()) next += direction;
            return next < 0 || next >= photos.size() ? -1 : next;
        }

        private void applyPhoto(File target, Bitmap bitmap) {
            index = photos.indexOf(target);
            current = target;
            picture.animate().cancel();
            picture.setImageBitmap(bitmap);
            configurePhoto(picture, current);
            picture.setContentDescription(getString(R.string.photo_position, index + 1, photos.size()));
            type.setText(label(current) + " · " + (index + 1) + " / " + photos.size());
            date.setText(stamp(current));
            side.setVisibility(CaptureProvider.isMacro(current) ? View.GONE : View.VISIBLE);
            side.setText(EarSide.read(GalleryActivity.this, current).label(GalleryActivity.this));
            picture.post(() -> positionDate(picture));
        }

        private void positionDate(View media) {
            int width = stage.getWidth(), height = stage.getHeight();
            float imageHeight = Math.min(width, height);
            if (media instanceof ImageView && ((ImageView) media).getDrawable() != null) {
                android.graphics.drawable.Drawable drawable = ((ImageView) media).getDrawable();
                float scale = Math.min((float) width / drawable.getIntrinsicWidth(),
                        (float) height / drawable.getIntrinsicHeight());
                imageHeight = drawable.getIntrinsicHeight() * scale;
            }
            float imageBottom = (height + imageHeight) / 2f;
            date.setTranslationY(Math.min(height - date.getHeight(),
                    (imageBottom + height + dp(12) - date.getHeight()) / 2f));
        }

        void show() {
            File file = current;
            boolean video = isVideo(file);
            VideoPlayback playback = video ? new VideoPlayback(GalleryActivity.this, CaptureProvider.uri(file),
                    !CaptureProvider.isMacro(file), () -> Toast.makeText(GalleryActivity.this, R.string.playback_error, Toast.LENGTH_SHORT).show()) : null;
            View media = playback == null ? photoView(file) : playback;
            Dialog dialog = new Dialog(GalleryActivity.this);
            LinearLayout content = style.page();
            content.setPadding(dp(16), dp(12), dp(16), dp(32));
            LinearLayout header = new LinearLayout(GalleryActivity.this);
            header.setGravity(Gravity.CENTER_VERTICAL);
            Button back = style.iconButton("back", getString(R.string.back));
            back.setContentDescription(getString(R.string.back_to_media));
            back.setOnClickListener(view -> dialog.dismiss());
            header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
            type = style.label(label(file) + (video ? "" : " · " + (index + 1) + " / " + photos.size()), 16, style.TEXT);
            type.setGravity(Gravity.CENTER);
            header.addView(type, new LinearLayout.LayoutParams(0, -2, 1));
            Button share = style.iconButton("share", getString(R.string.share));
            share.setOnClickListener(view -> share(current));
            header.addView(share);
            content.addView(header);
            if (!video) {
                side = style.button(EarSide.read(GalleryActivity.this, current).label(GalleryActivity.this));
                side.setVisibility(CaptureProvider.isMacro(current) ? View.GONE : View.VISIBLE);
                side.setOnClickListener(view -> chooseSide(current, side));
                style.add(content, side, 6);
            }
            stage = new FrameLayout(GalleryActivity.this);
            content.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
            if (!video) {
                picture = (ImageView) media;
                pager = new PhotoPager(GalleryActivity.this, picture, new PhotoPager.Source() {
                    @Override public File neighbor(int direction) {
                        int next = nextIndex(direction); return next < 0 ? null : photos.get(next);
                    }
                    @Override public void configure(ImageView image, File file) { configurePhoto(image, file); }
                    @Override public void changed(File file, Bitmap bitmap) { applyPhoto(file, bitmap); }
                });
                stage.addView(pager, new FrameLayout.LayoutParams(-1, -1));
            } else stage.addView(media, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            if (!video) {
                picture = (ImageView) media;
                picture.setContentDescription(getString(R.string.photo_position, index + 1, photos.size()));
                picture.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host,
                            android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        if (index > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                        if (index < photos.size() - 1) info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    }
                    @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return navigate(1);
                        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return navigate(-1);
                        return super.performAccessibilityAction(host, action, arguments);
                    }
                });
            }
            date = style.label(stamp(file), 17, style.TEXT);
            date.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams timeSize = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            stage.addView(date, timeSize);
            stage.addOnLayoutChangeListener((view, left, top, right, bottom, a, b, c, d) ->
                    date.post(() -> positionDate(video ? media : picture)));
            PlaybackControls controls = playback == null ? null : new PlaybackControls(playback.player);
            if (controls != null) style.add(content, controls, 14);
            Button delete = style.iconButton("delete", getString(R.string.delete));
            delete.setTextColor(style.light ? style.RED : 0xffffb4ae);
            delete.setOnClickListener(view -> {
                dialog.dismiss();
                confirmDelete(current);
            });
            LinearLayout bottom = new LinearLayout(GalleryActivity.this);
            bottom.setGravity(Gravity.CENTER);
            if (!video) {
                Button markup = style.iconButton("markup", getString(R.string.markup));
                markup.setOnClickListener(view -> editMarkup(current, dialog));
                LinearLayout.LayoutParams markupSize = new LinearLayout.LayoutParams(dp(48), dp(48));
                markupSize.rightMargin = dp(16);
                bottom.addView(markup, markupSize);
            }
            bottom.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
            style.add(content, bottom, 12);
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
            style.decorateWindow(dialog.getWindow());
        }

    }

    private void chooseSide(File file, Button button) {
        EarSide[] sides = EarSide.values();
        String[] labels = EarSide.labels(this);
        new ScopeDialog.Builder(this).setTitle(R.string.ear_choose)
                .setSingleChoiceItems(labels, EarSide.read(this, file).ordinal(), (dialog, which) -> {
                    if (file.isFile()) {
                        sides[which].save(this, file);
                        button.setText(labels[which]);
                        load();
                    }
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void editMarkup(File file, Dialog details) {
        Bitmap photo = BitmapFactory.decodeFile(file.getPath());
        if (photo == null) {
            Toast.makeText(this, R.string.markup_error, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean fullFrame = CaptureProvider.isMacro(file);
        String owner = profile;
        EarSide side = EarSide.read(this, file);
        Dialog editor = new Dialog(this);
        LinearLayout page = style.page();
        page.setPadding(dp(16), dp(12), dp(16), dp(32));
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER);
        Button circle = style.iconButton("circle", getString(R.string.markup_circle));
        Button arrow = style.iconButton("arrow", getString(R.string.markup_arrow));
        Button color = style.iconButton("color", getString(R.string.markup_color));
        style.setColorIcon(color, 0xffff6545, getString(R.string.markup_color) + " · " + getString(R.string.color_vermilion));
        Button undo = style.iconButton("undo", getString(R.string.markup_undo));
        Button redo = style.iconButton("redo", getString(R.string.markup_redo));
        for (Button tool : new Button[]{circle, arrow, color, undo, redo}) {
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(dp(48), dp(48));
            spacing.setMargins(dp(4), 0, dp(4), 0);
            tools.addView(tool, spacing);
        }
        page.addView(tools);
        Button cancel = style.iconButton("cancel", getString(R.string.cancel));
        Button save = style.iconButton("save", getString(R.string.markup_save_copy));
        PhotoMarkup drawing = new PhotoMarkup(this, photo, !fullFrame, getString(R.string.markup_hint), (changed, canRedo) -> {
            undo.setEnabled(changed);
            redo.setEnabled(canRedo);
            save.setEnabled(changed);
        });
        color.setOnClickListener(view -> chooseMarkupColor(drawing, color));
        style.selectIcon(circle, true);
        style.selectIcon(arrow, false);
        circle.setOnClickListener(view -> {
            drawing.setArrow(false);
            style.selectIcon(circle, true);
            style.selectIcon(arrow, false);
        });
        arrow.setOnClickListener(view -> {
            drawing.setArrow(true);
            style.selectIcon(circle, false);
            style.selectIcon(arrow, true);
        });
        undo.setEnabled(false);
        redo.setEnabled(false);
        undo.setOnClickListener(view -> drawing.undo());
        redo.setOnClickListener(view -> drawing.redo());
        page.addView(drawing, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = style.label(getString(R.string.markup_hint), 13, style.MUTED);
        hint.setGravity(Gravity.CENTER);
        page.addView(hint);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.addView(cancel);
        LinearLayout.LayoutParams saveSpacing = new LinearLayout.LayoutParams(dp(48), dp(48));
        saveSpacing.leftMargin = dp(12);
        actions.addView(save, saveSpacing);
        style.add(page, actions, 12);
        cancel.setOnClickListener(view -> editor.dismiss());
        save.setEnabled(false);
        save.setOnClickListener(view -> {
            Bitmap copy = drawing.createCopy();
            if (copy == null) {
                Toast.makeText(this, R.string.markup_error, Toast.LENGTH_SHORT).show();
                return;
            }
            drawing.setEnabled(false);
            for (Button button : new Button[]{circle, arrow, color, undo, redo, cancel, save}) button.setEnabled(false);
            editor.setCancelable(false);
            new Thread(() -> {
                boolean saved = saveMarkupCopy(copy, owner, fullFrame, side);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    Toast.makeText(this, saved ? R.string.markup_saved : R.string.markup_error, Toast.LENGTH_SHORT).show();
                    if (saved) {
                        editor.dismiss();
                        details.dismiss();
                        load();
                    } else {
                        editor.setCancelable(true);
                        drawing.setEnabled(true);
                        circle.setEnabled(true);
                        arrow.setEnabled(true);
                        undo.setEnabled(true);
                        redo.setEnabled(drawing.canRedo());
                        color.setEnabled(true);
                        cancel.setEnabled(true);
                        save.setEnabled(true);
                    }
                });
            }, "earclean-markup").start();
        });
        editor.setContentView(page);
        editor.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        markupDialog = editor;
        editor.setOnDismissListener(window -> {
            if (markupDialog == editor) markupDialog = null;
        });
        editor.show();
        editor.getWindow().setLayout(-1, -1);
        style.decorateWindow(editor.getWindow());
    }

    private boolean saveMarkupCopy(Bitmap copy, String owner, boolean fullFrame, EarSide side) {
        File partial = null;
        try {
            File file = CaptureProvider.newFile(this, owner, fullFrame ? "marked-macro" : "marked-photo", "jpg");
            partial = new File(file.getPath() + ".part");
            try (FileOutputStream output = new FileOutputStream(partial)) {
                if (!copy.compress(Bitmap.CompressFormat.JPEG, 94, output)) return false;
            }
            if (file.exists() || !partial.renameTo(file)) return false;
            side.save(this, file);
            return true;
        } catch (IOException | RuntimeException error) {
            return false;
        } finally {
            copy.recycle();
            if (partial != null) partial.delete();
        }
    }

    private void chooseMarkupColor(PhotoMarkup drawing, Button current) {
        int[] colors = {0xffff6545, 0xffffc857, 0xff5dd9cf, 0xfff4f5f7};
        int[] names = {R.string.color_vermilion, R.string.color_amber, R.string.color_teal, R.string.color_white};
        Dialog palette = new Dialog(this);
        LinearLayout page = style.page();
        page.setPadding(dp(20), dp(20), dp(20), dp(20));
        page.addView(style.label(getString(R.string.markup_color), 18, style.TEXT));
        LinearLayout swatches = new LinearLayout(this);
        swatches.setGravity(Gravity.CENTER);
        for (int i = 0; i < colors.length; i++) {
            int color = colors[i];
            String name = getString(names[i]);
            Button swatch = style.iconButton("color", name);
            style.setColorIcon(swatch, color, name);
            swatch.setOnClickListener(view -> {
                drawing.setColor(color);
                style.setColorIcon(current, color, getString(R.string.markup_color) + " · " + name);
                palette.dismiss();
            });
            swatches.addView(swatch, new LinearLayout.LayoutParams(dp(52), dp(52)));
        }
        style.add(page, swatches, 14);
        palette.setContentView(page);
        palette.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        palette.show();
    }

    private ImageView photoView(File file) {
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setImageBitmap(BitmapFactory.decodeFile(file.getPath()));
        configurePhoto(picture, file);
        return picture;
    }

    private void configurePhoto(ImageView picture, File file) {
        picture.setClipToOutline(!CaptureProvider.isMacro(file));
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
        } else {
            picture.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        }
        picture.invalidateOutline();
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
            setBackground(style.shape(style.GLASS, 28, style.EDGE));
            position.setProgressTintList(ColorStateList.valueOf(style.ACCENT));
            position.setThumbTintList(ColorStateList.valueOf(style.ACCENT));
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
            time.setTextColor(style.MUTED);
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
        new ScopeDialog.Builder(this).setTitle(getString(R.string.delete_count, files.length))
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
        if (file.getName().startsWith("marked-")) return getString(R.string.markup_copy);
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
