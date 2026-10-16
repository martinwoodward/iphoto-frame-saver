package io.github.martinwoodward.iphotoframesaver;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class SlideshowActivity extends Activity {
    private SlideshowController slideshow;
    private LinearLayout controls;
    private final Handler handler = new Handler();
    private final Runnable hide = () -> controls.setVisibility(View.GONE);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        slideshow = new SlideshowController(this, "activity");
        FrameLayout root = new FrameLayout(this);
        root.addView(slideshow.view(), new FrameLayout.LayoutParams(-1, -1));
        slideshow.view().setOnClickListener(v -> {
            controls.setVisibility(View.VISIBLE);
            controls.bringToFront();
            handler.removeCallbacks(hide);
            handler.postDelayed(hide, 6000);
        });
        controls = new LinearLayout(this);
        controls.setPadding(20, 12, 20, 12);
        controls.setBackgroundColor(0xdd101923);
        Button next = new Button(this);
        next.setText("Next photo");
        next.setOnClickListener(v -> slideshow.next());
        controls.addView(next);
        Button settings = new Button(this);
        settings.setText("Settings");
        settings.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        controls.addView(settings);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = 24;
        root.addView(controls, params);
        controls.setVisibility(View.GONE);
        setContentView(root);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) immersive();
    }
    @Override public void onStart() { super.onStart(); slideshow.start(); }
    @Override public void onStop() { handler.removeCallbacksAndMessages(null); slideshow.stop(); super.onStop(); }
    @Override public void onDestroy() { slideshow.close(); super.onDestroy(); }
}
