package io.github.martinwoodward.iphotoframesaver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

/** Uses Android's formatters rather than imposing an app-specific time or date pattern. */
final class ClockOverlay extends LinearLayout {
    private final Settings settings;
    private final TextView time;
    private final TextView date;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean started;
    private final Runnable tick = this::update;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (prefs, key) -> {
        if ("clock".equals(key)) handler.post(this::update);
    };
    private final ContentObserver formats = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { update(); }
    };
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { update(); }
    };

    ClockOverlay(Context context, Settings settings) {
        super(context);
        this.settings = settings;
        setOrientation(VERTICAL);
        setPadding(dp(24), dp(14), dp(24), dp(18));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x99000000);
        background.setCornerRadius(dp(24));
        setBackground(background);
        setClickable(false);
        setFocusable(false);
        time = new TextView(context);
        time.setTextColor(Color.WHITE);
        time.setTextSize(64);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setIncludeFontPadding(false);
        time.setSingleLine(true);
        addView(time, new LinearLayout.LayoutParams(-2, -2));
        date = new TextView(context);
        date.setTextColor(0xffe3e7eb);
        date.setTextSize(18);
        date.setIncludeFontPadding(false);
        date.setSingleLine(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(6);
        addView(date, params);
        setVisibility(GONE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    void start() {
        if (started) return;
        started = true;
        settings.prefs.registerOnSharedPreferenceChangeListener(preferences);
        getContext().getContentResolver().registerContentObserver(
                android.provider.Settings.System.getUriFor(android.provider.Settings.System.TIME_12_24),
                false, formats);
        getContext().getContentResolver().registerContentObserver(
                android.provider.Settings.System.getUriFor(android.provider.Settings.System.DATE_FORMAT),
                false, formats);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED);
        else getContext().registerReceiver(changes, filter);
        update();
    }

    void stop() {
        if (!started) return;
        started = false;
        settings.prefs.unregisterOnSharedPreferenceChangeListener(preferences);
        getContext().getContentResolver().unregisterContentObserver(formats);
        getContext().unregisterReceiver(changes);
        handler.removeCallbacksAndMessages(null);
        setVisibility(GONE);
    }

    void update() {
        handler.removeCallbacks(tick);
        if (!started) return;
        boolean enabled = settings.clock();
        setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) return;
        long now = System.currentTimeMillis();
        Date instant = new Date(now);
        time.setText(DateFormat.getTimeFormat(getContext()).format(instant));
        String dateSetting = android.provider.Settings.System.getString(
                getContext().getContentResolver(), android.provider.Settings.System.DATE_FORMAT);
        date.setText(dateFormatter(getContext(), dateSetting).format(instant));
        long remainder = now % 60_000L;
        if (remainder < 0) remainder += 60_000L;
        handler.postDelayed(tick, 60_000L - remainder + 20);
    }

    static java.text.DateFormat dateFormatter(Context context, String setting) {
        // Some older frame firmware retains this setting even when Android's formatter ignores it.
        if (setting != null && !setting.trim().isEmpty()) {
            try { return new SimpleDateFormat(setting, Locale.getDefault()); }
            catch (IllegalArgumentException invalid) {
                android.util.Log.w("FrameSaver", "Invalid system date format; using the locale date format.");
            }
        }
        return DateFormat.getDateFormat(context);
    }

    String timeText() { return time.getText().toString(); }
    String dateText() { return date.getText().toString(); }
}
