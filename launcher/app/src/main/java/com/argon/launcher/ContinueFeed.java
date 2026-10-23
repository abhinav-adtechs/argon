package com.argon.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

// Continue Watching for the web remote (its server runs in the :remote process of this app, so it can read the TV
// provider itself): what's on the home screen's widget, with the intent that resumes each one.
public final class ContinueFeed {
    private ContinueFeed() {}

    public static final class Item {
        public final String title, subtitle, app, pkg, image, intentUri;
        public final float progress;

        Item(String title, String subtitle, String app, String pkg, String image, String intentUri, float progress) {
            this.title = title;
            this.subtitle = subtitle;
            this.app = app;
            this.pkg = pkg;
            this.image = image;
            this.intentUri = intentUri;
            this.progress = progress;
        }
    }

    public static List<Item> load(Context c, int max) {
        PackageManager pm = c.getPackageManager();
        List<Item> out = new ArrayList<>();
        for (Shows.Show s : new Shows(c).continueWatching()) {
            if (out.size() >= max) break;
            String app = s.pkg;
            try {
                app = pm.getApplicationLabel(pm.getApplicationInfo(s.pkg, 0)).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            out.add(new Item(s.title, s.subtitle, app, s.pkg, s.image, s.intent.toUri(Intent.URI_INTENT_SCHEME), s.progress));
        }
        return out;
    }
}
