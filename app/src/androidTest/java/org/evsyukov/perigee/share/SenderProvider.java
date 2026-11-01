package org.evsyukov.perigee.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The files of another app, as a file manager gives them: by content address,
 * to an app the right to read is granted to, and failing as such files fail.
 * It runs in the test package's own process, under its own user ID, so it is
 * written against the platform alone: the libraries of the app under test are
 * not in the test package. The tests in the app's process drive it through
 * {@link #call}; see {@code Sender}.
 */
public final class SenderProvider extends ContentProvider {
    /** Given as it is. */
    static final String NONE = "none";
    /** No stream comes. */
    static final String NO_STREAM = "noStream";
    /** The file is gone. */
    static final String GONE = "gone";
    /** Half of the file comes, then an error. */
    static final String BREAKS_OFF = "breaksOff";
    /** Asked for its name, the app waits to be released before it answers. */
    static final String HOLD_QUERY = "holdQuery";
    /** Opened, the app gives nothing until it is released, then the whole file. */
    static final String HOLD_COPY = "holdCopy";
    /** Half of the file comes, then nothing more until it is released. */
    static final String STALL = "stall";

    private static final long LONGEST_HOLD_SECONDS = 60;

    private static final class Sent {
        final String name;
        final File file;
        final String mode;
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean held;
        volatile int opens;

        Sent(String name, File file, String mode) {
            this.name = name;
            this.file = file;
            this.mode = mode;
        }
    }

    private static final Map<String, Sent> SENT = new ConcurrentHashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle answer = new Bundle();
        switch (method) {
            case "stage": {
                String key = UUID.randomUUID().toString();
                File file = new File(getContext().getFilesDir(), "sent/" + key);
                try {
                    file.getParentFile().mkdirs();
                    Files.write(file.toPath(), extras.getByteArray("bytes"));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                String mode = extras.getString("mode");
                SENT.put(key, new Sent(extras.getString("name"), file, mode == null ? NONE : mode));
                Uri uri = new Uri.Builder().scheme("content").authority(getContext().getPackageName() + ".sender").appendPath(key).build();
                answer.putString("uri", uri.toString());
                break;
            }
            case "state": {
                Sent sent = sent(Uri.parse(arg));
                answer.putBoolean("held", sent != null && sent.held);
                answer.putInt("opens", sent == null ? 0 : sent.opens);
                break;
            }
            case "release": {
                Sent sent = sent(Uri.parse(arg));
                if (sent != null) sent.release.countDown();
                break;
            }
            case "revoke":
                getContext().revokeUriPermission(Uri.parse(arg), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                break;
            case "sender":
                answer.putBoolean("alive", SenderActivity.alive());
                answer.putInt("resumes", SenderActivity.resumes());
                break;
            case "finishSender":
                SenderActivity.finishAll();
                break;
            case "forget":
                for (Sent sent : SENT.values()) sent.release.countDown();
                SENT.clear();
                break;
            default:
                throw new IllegalArgumentException(method);
        }
        return answer;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Sent sent = sent(uri);
        if (sent == null) return null;
        if (HOLD_QUERY.equals(sent.mode)) {
            sent.held = true;
            await(sent);
        }
        String[] columns = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = sent.name;
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = sent.file.length();
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Sent sent = sent(uri);
        if (sent == null) throw new FileNotFoundException(uri.toString());
        sent.opens++;
        switch (sent.mode) {
            case NO_STREAM:
                return null;
            case GONE:
                throw new FileNotFoundException(uri.toString());
            case BREAKS_OFF:
            case HOLD_COPY:
            case STALL:
                return pipe(sent);
            default:
                return ParcelFileDescriptor.open(sent.file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    /** The file through a pipe, given as its mode says. */
    private static ParcelFileDescriptor pipe(Sent sent) throws FileNotFoundException {
        ParcelFileDescriptor[] ends;
        try {
            ends = ParcelFileDescriptor.createReliablePipe();
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
        ParcelFileDescriptor write = ends[1];
        if (HOLD_COPY.equals(sent.mode)) sent.held = true;
        new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(sent.file.toPath());
                if (HOLD_COPY.equals(sent.mode)) {
                    await(sent);
                    try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(write)) {
                        out.write(bytes);
                    }
                    return;
                }
                new FileOutputStream(write.getFileDescriptor()).write(bytes, 0, bytes.length / 2);
                if (STALL.equals(sent.mode)) {
                    sent.held = true;
                    await(sent);
                }
                write.closeWithError("the app went away");
            } catch (IOException e) {
                // The app reading it closed its end.
                try {
                    write.close();
                } catch (IOException ignored) {
                    // Closed already.
                }
            }
        }).start();
        return ends[0];
    }

    private static void await(Sent sent) {
        try {
            sent.release.await(LONGEST_HOLD_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Sent sent(Uri uri) {
        String key = uri.getLastPathSegment();
        return key == null ? null : SENT.get(key);
    }

    @Override
    public String getType(Uri uri) {
        return sent(uri) == null ? null : "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
