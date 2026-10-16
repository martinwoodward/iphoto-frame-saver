package io.github.martinwoodward.iphotoframesaver;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.martinwoodward.iphotoframesaver.core.AlbumAddress;
import io.github.martinwoodward.iphotoframesaver.core.PlaybackPolicy;

final class Settings {
    final SharedPreferences prefs;

    Settings(Context context) { prefs = context.getSharedPreferences("frame", Context.MODE_PRIVATE); }

    String album() { return prefs.getString("album", ""); }
    int interval() { return prefs.getInt("interval", 30); }
    String refresh() { return prefs.getString("refresh", "hourly"); }
    String storage() { return prefs.getString("storage", "stream"); }
    String tree() { return prefs.getString("tree", ""); }
    boolean clock() { return prefs.getBoolean("clock", false); }

    void save(String album, int seconds, String refresh) {
        String canonical = AlbumAddress.parse(album).canonical;
        PlaybackPolicy.intervalSeconds(Integer.toString(seconds));
        prefs.edit().putString("album", canonical).putInt("interval", seconds)
                .putString("refresh", refresh).apply();
    }
}
