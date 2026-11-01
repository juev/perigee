package org.evsyukov.perigee.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Another app's "Share", as a file manager sends it: from its own task and
 * user ID, to whatever activity of the app takes the type, with the right to
 * read the files granted or not. It stays where it is after sharing, as a
 * file manager does. Written against the platform alone, see {@link SenderProvider}.
 */
public final class SenderActivity extends Activity {
    private static final List<SenderActivity> LIVING = new CopyOnWriteArrayList<>();
    private static final AtomicInteger RESUMES = new AtomicInteger();

    static boolean alive() {
        return !LIVING.isEmpty();
    }

    static int resumes() {
        return RESUMES.get();
    }

    static void finishAll() {
        new Handler(Looper.getMainLooper()).post(() -> {
            for (SenderActivity activity : LIVING) activity.finishAndRemoveTask();
        });
    }

    @Override
    @SuppressWarnings("deprecation") // The typed getter is of API 33.
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LIVING.add(this);
        if (savedInstanceState != null) return;
        Intent asked = getIntent();
        String action = asked.getStringExtra("action");
        ArrayList<Uri> files = asked.getParcelableArrayListExtra("uris");
        Intent share = new Intent(action).setType(asked.getStringExtra("type")).setPackage(asked.getStringExtra("to"));
        if (Intent.ACTION_SEND.equals(action)) {
            share.putExtra(Intent.EXTRA_STREAM, files.get(0));
        } else {
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
        }
        if (asked.getBooleanExtra("grant", true)) {
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // A share whose stream is not in its clip: the system, which copies the stream into the clip and
            // grants the right to read it, leaves such an intent as it is, and the right is not granted.
            share.setClipData(ClipData.newPlainText("", "a share that grants nothing"));
        }
        startActivity(share);
    }

    @Override
    protected void onResume() {
        super.onResume();
        RESUMES.incrementAndGet();
    }

    @Override
    protected void onDestroy() {
        LIVING.remove(this);
        super.onDestroy();
    }
}
