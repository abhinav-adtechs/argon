package com.argon.launcher;

import android.service.dreams.DreamService;

// The screensaver: the TMDB picks' photos with a big glass clock (DreamView). Turned on by tools/build_install.sh with
// `settings put secure screensaver_components`; the system still decides when it starts. Any key exits.
public class Screensaver extends DreamService {
    private DreamView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        view = new DreamView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (view != null) view.start();
    }

    @Override
    public void onDreamingStopped() {
        if (view != null) view.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
