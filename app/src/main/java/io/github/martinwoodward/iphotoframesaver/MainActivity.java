package io.github.martinwoodward.iphotoframesaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Toast;
import io.github.martinwoodward.iphotoframesaver.core.AlbumAddress;
import io.github.martinwoodward.iphotoframesaver.core.PlaybackPolicy;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity {
    private static final int TREE_REQUEST = 41;
    private static final int INK = 0xff101923;
    private static final int CARD = 0xff1b2a38;
    private static final int MINT = 0xff76ddcf;
    private static final int WHITE = 0xfff1f6f7;
    private static final int MUTED = 0xffadc1ce;
    private Repository repo;
    private TextView status;
    private TextView albumSummary;
    private TextView downloadStatus;
    private EditText interval;
    private Spinner refresh;
    private RadioGroup storage;
    private Button download;
    private Button cancel;
    private Button check;
    private boolean checking;
    private final Handler handler = new Handler();
    private final Runnable update = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 1000); }
    };

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = FrameApp.repo(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(INK);
        LinearLayout page = column();
        page.setFocusableInTouchMode(true);
        page.setPadding(dp(40), dp(28), dp(40), dp(24));
        scroll.addView(page);
        TextView eyebrow = text("P H O T O   F R A M E   S A V E R", 13, MINT);
        eyebrow.setTypeface(null, Typeface.BOLD);
        page.addView(eyebrow);
        TextView title = text("Your moments.\nAlways in view.", 36, WHITE);
        title.setTypeface(Typeface.create("sans-serif-light", 0));
        title.setLineSpacing(0, 1.05f);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(12);
        page.addView(title, titleParams);
        status = text("Preparing your frame...", 16, MINT);
        status.setMinHeight(dp(40));
        status.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(status);

        LinearLayout panels = new LinearLayout(this);
        boolean wide = getResources().getConfiguration().screenWidthDp >= 800;
        panels.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.addView(panels, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout left = card();
        LinearLayout right = card();
        LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
        if (wide) leftParams.setMarginEnd(dp(16));
        panels.addView(left, leftParams);
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(wide ? 0 : -1, -2, wide ? 1 : 0);
        if (!wide) rightParams.topMargin = dp(16);
        panels.addView(right, rightParams);

        heading(left, "01  /  Your album");
        albumSummary = text("", 16, WHITE);
        left.addView(albumSummary);
        button(left, "Change album link", false, v -> albumDialog(false));
        label(left, "TIME BETWEEN PHOTOS");
        LinearLayout duration = new LinearLayout(this);
        duration.setGravity(Gravity.CENTER_VERTICAL);
        interval = new EditText(this);
        interval.setTextColor(WHITE);
        interval.setTextSize(20);
        interval.setInputType(InputType.TYPE_CLASS_NUMBER);
        interval.setSingleLine(true);
        interval.setSelectAllOnFocus(true);
        interval.setText(Integer.toString(repo.settings.interval()));
        interval.setContentDescription("Slideshow interval in seconds");
        duration.addView(interval, new LinearLayout.LayoutParams(dp(130), dp(52)));
        duration.addView(text("seconds  /  default 30", 14, MUTED));
        left.addView(duration);
        label(left, "CHECK FOR NEW PHOTOS");
        refresh = new Spinner(this);
        String[] frequencies = {"Hourly", "Daily", "Weekly", "Manual only"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, frequencies);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refresh.setAdapter(adapter);
        String[] values = {"hourly", "daily", "weekly", "manual"};
        for (int i = 0; i < values.length; i++) if (values[i].equals(repo.settings.refresh())) refresh.setSelection(i);
        left.addView(refresh, new LinearLayout.LayoutParams(-1, dp(52)));
        Switch clock = new Switch(this);
        clock.setText("Show clock and date");
        clock.setTextSize(16);
        clock.setTextColor(WHITE);
        clock.setMinHeight(dp(52));
        clock.setThumbTintList(ColorStateList.valueOf(MINT));
        clock.setChecked(repo.settings.clock());
        clock.setOnCheckedChangeListener((button, enabled) ->
                repo.settings.prefs.edit().putBoolean("clock", enabled).apply());
        left.addView(clock, new LinearLayout.LayoutParams(-1, dp(52)));
        left.addView(text("Uses the device's time, date and 12/24-hour formats.", 13, MUTED));
        button(left, "Save playback settings", false, v -> savePlayback());
        check = button(left, "Force check...", false, v -> checkAlbum());

        heading(right, "02  /  Make it a frame");
        TextView description = text("Full-screen photos, a fresh shuffle, and gentle cross-fades. Tap the slideshow for controls.", 15, MUTED);
        right.addView(description);
        button(right, "Start slideshow", true, v -> {
            if (repo.settings.album().isEmpty()) albumDialog(false);
            else if (savePlayback()) startActivity(new Intent(this, SlideshowActivity.class));
        });
        button(right, "Choose Android screensaver", false, v -> {
            if (!savePlayback()) return;
            try { startActivity(new Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)); }
            catch (ActivityNotFoundException e) { notifyUser("This device does not expose screensaver settings. See the setup guide for ADB configuration."); }
        });
        TextView dreamTip = text("Select Photo Frame Saver in Android's screensaver list. Touch the screensaver to exit.", 13, MUTED);
        right.addView(dreamTip);
        label(right, "PHOTO STORAGE");
        storage = new RadioGroup(this);
        String[] modes = {"Stream / keep next 5 photos", "Offline / app-local storage", "Offline / attached storage"};
        String[] modeValues = {"stream", "internal", "attached"};
        for (int i = 0; i < modes.length; i++) {
            RadioButton option = new RadioButton(this);
            option.setId(100 + i);
            option.setText(modes[i]);
            option.setTextColor(WHITE);
            option.setTextSize(15);
            option.setButtonTintList(ColorStateList.valueOf(MINT));
            option.setMinHeight(dp(46));
            storage.addView(option);
            if (modeValues[i].equals(repo.settings.storage())) storage.check(100 + i);
        }
        right.addView(storage);
        storage.setOnCheckedChangeListener((group, checkedId) -> {
            String mode = modeValues[checkedId - 100];
            repo.settings.prefs.edit().putString("storage", mode).apply();
            if ("attached".equals(mode) && repo.settings.tree().isEmpty()) chooseStorage();
            updateStatus();
        });
        button(right, "Choose attached folder...", false, v -> chooseStorage());
        download = button(right, "Download / update offline album", false, v -> {
            if (repo.settings.album().isEmpty()) { albumDialog(false); return; }
            if ("attached".equals(repo.settings.storage()) && repo.settings.tree().isEmpty()) { chooseStorage(); return; }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 42);
            }
            Intent intent = new Intent(this, OfflineService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            notifyUser("Offline download started. You can leave this screen.");
        });
        cancel = button(right, "Stop download", false, v -> {
            repo.cancelDownload = true;
            notifyUser("Stopping after the current transfer. Saved photos will be kept.");
        });
        downloadStatus = text("", 14, MUTED);
        right.addView(downloadStatus);
        TextView privacy = text("PRIVATE BY DESIGN  /  No account sign-in. No tracking. Your album link and photos stay on this device and storage you choose. Unofficial; not affiliated with Apple.", 12, MUTED);
        LinearLayout.LayoutParams footer = new LinearLayout.LayoutParams(-1, -2);
        footer.topMargin = dp(22);
        page.addView(privacy, footer);
        setContentView(scroll);
        page.requestFocus();
        repo.background.execute(repo::load);
        if (repo.settings.album().isEmpty() && state == null) scroll.post(() -> albumDialog(true));
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setPadding(dp(24), dp(22), dp(24), dp(22));
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(16));
        layout.setBackground(background);
        return layout;
    }
    private TextView text(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        return view;
    }
    private void heading(LinearLayout parent, String text) {
        TextView view = text(text, 21, WHITE);
        view.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        parent.addView(view, params);
    }
    private void label(LinearLayout parent, String text) {
        TextView view = text(text, 12, MINT);
        view.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(18);
        params.bottomMargin = dp(4);
        parent.addView(view, params);
    }
    private Button button(LinearLayout parent, String text, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? INK : WHITE);
        button.setMinHeight(dp(52));
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? MINT : 0xff304656));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(54));
        params.topMargin = dp(10);
        parent.addView(button, params);
        return button;
    }

    private boolean savePlayback() {
        if (repo.settings.album().isEmpty()) { albumDialog(false); return false; }
        try {
            int seconds = PlaybackPolicy.intervalSeconds(interval.getText().toString());
            String frequency = new String[] {"hourly", "daily", "weekly", "manual"}[refresh.getSelectedItemPosition()];
            repo.settings.save(repo.settings.album(), seconds, frequency);
            RefreshJobService.schedule(this);
            interval.clearFocus();
            notifyUser("Playback settings saved.");
            return true;
        } catch (IllegalArgumentException e) {
            interval.setError(e.getMessage());
            return false;
        }
    }

    private void albumDialog(boolean initial) {
        LinearLayout content = column();
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        content.addView(text("Paste a public iCloud shared album link. No Apple account or password is needed.", 16, MUTED));
        EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        url.setSingleLine(true);
        url.setTextColor(WHITE);
        url.setHint("https://photos.icloud.com/shared/album/...");
        url.setHintTextColor(MUTED);
        url.setTextSize(15);
        url.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) url.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        content.addView(url, new LinearLayout.LayoutParams(-1, dp(64)));
        content.addView(text("Seconds between photos", 14, MUTED));
        EditText seconds = new EditText(this);
        seconds.setInputType(InputType.TYPE_CLASS_NUMBER);
        seconds.setSingleLine(true);
        seconds.setTextColor(WHITE);
        seconds.setText(Integer.toString(repo.settings.interval()));
        seconds.setSelectAllOnFocus(true);
        content.addView(seconds, new LinearLayout.LayoutParams(-1, dp(56)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(initial ? "Welcome to your photo frame" : "Change shared album")
                .setView(content).setPositiveButton("Save & check album", null)
                .setNegativeButton(initial ? "Set up later" : "Cancel", null).create();
        dialog.setOnShowListener(d -> {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String album = AlbumAddress.parse(url.getText().toString()).canonical;
                    int value = PlaybackPolicy.intervalSeconds(seconds.getText().toString());
                    boolean changed = !album.equals(repo.settings.album());
                    repo.settings.save(album, value, repo.settings.refresh());
                    if (changed) repo.settings.prefs.edit().remove("last_displayed").apply();
                    interval.setText(Integer.toString(value));
                    RefreshJobService.schedule(this);
                    dialog.dismiss();
                    checkAlbum();
                } catch (IllegalArgumentException e) { notifyUser(e.getMessage()); }
            });
        });
        dialog.show();
    }

    private void checkAlbum() {
        if (repo.settings.album().isEmpty()) { albumDialog(false); return; }
        if (checking) return;
        checking = true;
        updateStatus();
        repo.background.execute(() -> {
            try { repo.refresh(true); }
            catch (FrameException e) { repo.status = e.getMessage(); }
            runOnUiThread(() -> { checking = false; updateStatus(); });
        });
    }

    private void chooseStorage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try { startActivityForResult(intent, TREE_REQUEST); }
        catch (ActivityNotFoundException e) { notifyUser("This device has no storage folder picker. Use app-local offline storage."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != TREE_REQUEST || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
            notifyUser("The selected folder is read-only. Choose a writable folder.");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(tree, flags);
            repo.settings.prefs.edit().putString("tree", tree.toString()).putString("storage", "attached").apply();
            storage.check(102);
            notifyUser("Attached folder selected. Tap Download to save the album.");
        } catch (SecurityException e) { notifyUser("Folder access could not be retained. Choose the folder again."); }
    }

    private void updateStatus() {
        if (status == null) return;
        status.setText(checking ? "Checking your shared album..." : repo.status);
        Repository.Snapshot snapshot = repo.current;
        if (repo.settings.album().isEmpty()) albumSummary.setText("No album yet. Add a public link to begin.");
        else {
            String last = snapshot.refreshed == 0 ? "Not checked yet"
                    : "Last checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(snapshot.refreshed));
            albumSummary.setText("Private album link saved on this device.\n" + snapshot.photos.size()
                    + " photos  /  " + last);
        }
        check.setEnabled(!checking);
        download.setEnabled(!repo.downloading && !"stream".equals(repo.settings.storage()));
        cancel.setVisibility(repo.downloading ? View.VISIBLE : View.GONE);
        downloadStatus.setText(repo.downloadStatus.isEmpty()
                ? "Streaming downloads only the current photo and the next five. Offline downloads are optional."
                : repo.downloadStatus);
    }

    private void notifyUser(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    @Override public void onResume() {
        super.onResume();
        handler.post(update);
        RefreshJobService.schedule(this);
        if (!repo.settings.album().isEmpty()) repo.background.execute(() -> {
            try { repo.refresh(false); } catch (FrameException e) { repo.status = e.getMessage(); }
        });
    }
    @Override public void onPause() { handler.removeCallbacksAndMessages(null); super.onPause(); }
}
