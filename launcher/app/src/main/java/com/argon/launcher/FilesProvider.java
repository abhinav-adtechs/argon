package com.argon.launcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

// The launcher's viewing stats database for adb, now that the launcher isn't debuggable and run-as is gone:
//   adb shell content read --uri content://com.argon.launcher.files/watch.db > watch.db
// and back into a fresh install, before anything in the launcher has opened it (force-stop it first):
//   adb shell content write --uri content://com.argon.launcher.files/watch.db < watch.db
// Shell and root only (DUMP), like the other receivers and providers.
public class FilesProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (path.size() == 1 && "watch.db".equals(path.get(0)) && "r".equals(mode)) {
            return ParcelFileDescriptor.open(getContext().getDatabasePath("watch.db"), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (path.size() == 1 && "watch.db".equals(path.get(0)) && mode.startsWith("w")) {
            if (WatchLog.started()) throw new FileNotFoundException("watch.db is open; force-stop the launcher first");
            File db = getContext().getDatabasePath("watch.db");
            db.getParentFile().mkdirs();
            return ParcelFileDescriptor.open(db, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        // The lights' and plugs' keys (smarthome/SmartHome), to keep a copy or put one back; read when the remote's
        // process starts, so force-stop the launcher after writing it.
        if (path.size() == 1 && "smarthome.json".equals(path.get(0))) {
            File f = new File(getContext().getFilesDir(), "smarthome.json");
            if ("r".equals(mode)) return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        throw new FileNotFoundException(uri.toString());
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
