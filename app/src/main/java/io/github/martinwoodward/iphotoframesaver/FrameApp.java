package io.github.martinwoodward.iphotoframesaver;

import android.app.Application;
import android.content.Context;

public final class FrameApp extends Application {
    private Repository repository;
    @Override public void onCreate() {
        super.onCreate();
        repository = new Repository(this);
    }
    static Repository repo(Context context) {
        return ((FrameApp) context.getApplicationContext()).repository;
    }
}
