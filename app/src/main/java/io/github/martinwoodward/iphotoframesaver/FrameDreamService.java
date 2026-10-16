package io.github.martinwoodward.iphotoframesaver;

import android.service.dreams.DreamService;

public final class FrameDreamService extends DreamService {
    private SlideshowController slideshow;
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        slideshow = new SlideshowController(this, "dream");
        setContentView(slideshow.view());
    }
    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (slideshow != null) slideshow.start();
        DreamStateProvider.setDreaming(this, true);
    }
    @Override public void onDreamingStopped() {
        DreamStateProvider.setDreaming(this, false);
        if (slideshow != null) slideshow.stop();
        super.onDreamingStopped();
    }
    @Override public void onDetachedFromWindow() {
        DreamStateProvider.setDreaming(this, false);
        if (slideshow != null) slideshow.close();
        slideshow = null;
        super.onDetachedFromWindow();
    }
}
