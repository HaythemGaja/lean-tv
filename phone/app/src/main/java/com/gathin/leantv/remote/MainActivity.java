package com.gathin.leantv.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone end of Lean TV: a D-pad, Back, Home, volume, power, one line of text, the TV's app list, its health
 * and a frame of what it is showing, all sent to the TV over Adb.
 *
 * <p>Nothing is paired by hand: the first connection is answered by the TV's own "Allow debugging?" prompt,
 * naming this phone, and the key is remembered after that. Presses go down one shell kept open for the life of
 * the connection, so a press is one round trip; a press after the TV slept reopens the connection by itself.
 *
 * <p>Every network call runs on a background thread and the screen only shows the result, so a slow or missing TV
 * cannot freeze the buttons.
 */
public final class MainActivity extends Activity {
    static final String PREFS = "leantv_remote";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final int ADB_PORT = 5555;
    private static final long RETRY_PAUSE_MS = 1500;
    private static final long MIRROR_EVERY_MS = 1500;

    private static final int KEY_BACK = 4, KEY_HOME = 3, KEY_UP = 19, KEY_DOWN = 20, KEY_LEFT = 21,
            KEY_RIGHT = 22, KEY_OK = 23, KEY_VOLUME_UP = 24, KEY_VOLUME_DOWN = 25, KEY_POWER = 26;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService frames = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Remotes.Tv> found = new ArrayList<>();

    private AdbClient client;
    private AdbKeys keys;
    private Discovery discovery;
    private EditText hostField;
    private EditText textField;
    private TextView status;
    private TextView health;
    private ImageView preview;
    private boolean mirroring;
    private volatile boolean connecting;

    private interface Action {
        void run(AdbClient client) throws IOException;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        hostField = findViewById(R.id.host);
        textField = findViewById(R.id.text);
        status = findViewById(R.id.status);
        health = findViewById(R.id.health_line);
        preview = findViewById(R.id.preview);

        String saved = prefs().getString(HOST, "");
        if (!saved.isEmpty()) {
            int port = prefs().getInt(PORT, ADB_PORT);
            hostField.setText(port == ADB_PORT ? saved : saved + ":" + port);
        }

        findViewById(R.id.connect).setOnClickListener(v -> connectOrClose());
        findViewById(R.id.scan).setOnClickListener(v -> scan());
        findViewById(R.id.saved).setOnClickListener(v -> showSaved());
        findViewById(R.id.apps).setOnClickListener(v -> showApps());
        findViewById(R.id.health).setOnClickListener(v -> showHealth());
        findViewById(R.id.screen).setOnClickListener(v -> toggleMirror());
        findViewById(R.id.send).setOnClickListener(v -> sendText());

        button(R.id.up, KEY_UP);
        button(R.id.down, KEY_DOWN);
        button(R.id.left, KEY_LEFT);
        button(R.id.right, KEY_RIGHT);
        button(R.id.ok, KEY_OK);
        button(R.id.back, KEY_BACK);
        button(R.id.home, KEY_HOME);
        button(R.id.power, KEY_POWER);
        button(R.id.volume_up, KEY_VOLUME_UP);
        button(R.id.volume_down, KEY_VOLUME_DOWN);

        // A long press on Back is what people expect from a remote: three steps back, not one.
        findViewById(R.id.back).setOnLongClickListener(v -> {
            on(c -> c.keys(KEY_BACK, KEY_BACK, KEY_BACK));
            return true;
        });
    }

    private void button(int id, int keyCode) {
        findViewById(id).setOnClickListener(v -> on(c -> c.key(keyCode)));
    }

    // ---- connecting ----------------------------------------------------------------------------

    private void connectOrClose() {
        AdbClient open = client;
        if (open != null) {
            client = null;
            io.execute(open::close);
            show(getString(R.string.not_connected));
            return;
        }
        String typed = hostField.getText().toString().trim();
        if (typed.isEmpty()) {
            show(getString(R.string.needs_address));
            return;
        }
        String host = typed;
        int port = ADB_PORT;
        int colon = typed.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(typed.substring(colon + 1));
                host = typed.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // Not "address:port": the whole field is the address.
            }
        }
        connectTo(host, port, "");
    }

    private void connectTo(String host, int port, String name) {
        AdbClient open = client;
        client = null;
        if (open != null) io.execute(open::close);
        hostField.setText(port == ADB_PORT ? host : host + ":" + port);
        prefs().edit().putString(HOST, host).putInt(PORT, port).apply();
        if (name != null && !name.isEmpty()) Remotes.save(this, name, host, port);
        show(getString(R.string.connecting, host));
        if (connecting) return;
        connecting = true;
        io.execute(() -> {
            try {
                client = openWithRetry(host, port);
                show(getString(R.string.connected, host));
            } catch (IOException | RuntimeException e) {
                fail(e);
            } finally {
                connecting = false;
            }
        });
    }

    /** Three tries: a TV that just woke drops the first connection while its debug bridge starts. */
    private AdbClient openWithRetry(String host, int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return AdbClient.connect(host, port, keys(), 8000);
            } catch (IOException e) {
                last = e;
                pause();
            }
        }
        throw last == null ? new IOException("Could not reach " + host) : last;
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The connection, or a fresh one to the TV last used: a press after the TV slept works again. */
    private AdbClient live() throws IOException {
        AdbClient open = client;
        if (open != null) return open;
        String host = prefs().getString(HOST, "");
        if (host.isEmpty()) throw new IOException(getString(R.string.not_connected));
        AdbClient fresh = openWithRetry(host, prefs().getInt(PORT, ADB_PORT));
        client = fresh;
        return fresh;
    }

    private void on(Action action) {
        io.execute(() -> {
            try {
                action.run(live());
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    // ---- finding TVs ---------------------------------------------------------------------------

    private void scan() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        found.clear();
        show(getString(R.string.scanning));
        discovery = Discovery.start(this, new Discovery.Listener() {
            @Override
            public void found(String name, String host, int port) {
                found.add(new Remotes.Tv(name, host, port));
            }

            @Override
            public void finished(String problem) {
                discovery = null;
                if (problem != null) {
                    show(getString(R.string.failed, problem));
                } else if (found.isEmpty()) {
                    show(getString(R.string.no_tvs_found));
                } else {
                    show(getString(R.string.tvs_found, found.size()));
                    pick(found, R.string.scan);
                }
            }
        });
    }

    private void showSaved() {
        List<Remotes.Tv> saved = Remotes.all(this);
        if (saved.isEmpty()) {
            show(getString(R.string.no_saved));
            return;
        }
        List<String> labels = new ArrayList<>();
        for (Remotes.Tv tv : saved) labels.add(tv.label());
        labels.add(getString(R.string.forget));
        new AlertDialog.Builder(this)
                .setTitle(R.string.saved)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == saved.size()) {
                        Remotes.forgetAll(this);
                        show(getString(R.string.forgot));
                    } else {
                        Remotes.Tv tv = saved.get(which);
                        connectTo(tv.host, tv.port, tv.name);
                    }
                })
                .show();
    }

    private void pick(List<Remotes.Tv> tvs, int title) {
        List<String> labels = new ArrayList<>();
        for (Remotes.Tv tv : tvs) labels.add(tv.label());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    Remotes.Tv tv = tvs.get(which);
                    connectTo(tv.host, tv.port, tv.name);
                })
                .show();
    }

    // ---- apps, health, screen --------------------------------------------------------------------

    private void showApps() {
        show(getString(R.string.apps_loading));
        io.execute(() -> {
            try {
                List<String> apps = packages(live().packages());
                ui.post(() -> {
                    if (apps.isEmpty()) {
                        show(getString(R.string.no_apps));
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.apps)
                            .setItems(apps.toArray(new String[0]),
                                    (dialog, which) -> on(c -> c.launch(apps.get(which))))
                            .show();
                });
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    /** package: lines from pm, sorted, with anything that is not a package name dropped. */
    private static List<String> packages(String listing) {
        List<String> names = new ArrayList<>();
        if (listing == null) return names;
        for (String line : listing.split("\n")) {
            String text = line.trim();
            if (!text.startsWith("package:")) continue;
            String pkg = text.substring("package:".length()).trim();
            if (pkg.matches("[A-Za-z0-9_.]+")) names.add(pkg);
        }
        Collections.sort(names);
        return names;
    }

    private void showHealth() {
        show(getString(R.string.health_reading));
        on(c -> {
            String line = c.health();
            ui.post(() -> {
                health.setText(line);
                health.setVisibility(View.VISIBLE);
            });
        });
    }

    private void toggleMirror() {
        mirroring = !mirroring;
        preview.setVisibility(mirroring ? View.VISIBLE : View.GONE);
        if (mirroring) {
            preview.setImageDrawable(null);
            grabFrame();
        }
    }

    /** One frame at a time: the next is asked for only after the last has been shown. */
    private void grabFrame() {
        if (!mirroring) return;
        frames.execute(() -> {
            AdbClient open = client;
            Bitmap frame = null;
            if (open != null) {
                try {
                    byte[] png = open.screenshot();
                    frame = BitmapFactory.decodeByteArray(png, 0, png.length);
                } catch (IOException | RuntimeException e) {
                    frame = null;
                }
            }
            Bitmap shown = frame;
            ui.post(() -> {
                if (!mirroring) return;
                preview.setImageBitmap(shown);
                ui.postDelayed(this::grabFrame, MIRROR_EVERY_MS);
            });
        });
    }

    // ---- small things ----------------------------------------------------------------------------

    private void sendText() {
        String value = textField.getText().toString();
        if (value.isEmpty()) return;
        on(c -> c.text(value));
    }

    /** Generated once, then kept in the app's own preferences so the TV stays paired. */
    private AdbKeys keys() {
        if (keys != null) return keys;
        SharedPreferences prefs = prefs();
        String storedPrivate = prefs.getString("key_private", null);
        String storedPublic = prefs.getString("key_public", null);
        try {
            if (storedPrivate == null || storedPublic == null) {
                keys = AdbKeys.generate();
                prefs.edit().putString("key_private", keys.privateBase64())
                        .putString("key_public", keys.publicBase64()).apply();
            } else {
                keys = AdbKeys.fromBase64(storedPrivate, storedPublic);
            }
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("This phone could not make an Adb key", e);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private void fail(Throwable error) {
        String message = error.getMessage();
        show(getString(R.string.failed, message == null ? error.getClass().getSimpleName() : message));
    }

    private void show(String text) {
        ui.post(() -> status.setText(text));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mirroring = false;
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
        AdbClient open = client;
        client = null;
        if (open != null) io.execute(open::close);
        io.shutdown();
        frames.shutdown();
    }
}
