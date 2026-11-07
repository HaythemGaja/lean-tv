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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** TVs the scan has reported, by host: the same host is reported again when its 5555 probe comes back. */
    private final Map<String, Discovery.Tv> found = new LinkedHashMap<>();

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
        findViewById(R.id.clean).setOnClickListener(v -> showClean());
        findViewById(R.id.customize).setOnClickListener(v -> showCustomize());

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

    /** The port the last connection actually opened on: 5555 after a TLS-port fallback. */
    private int connectedPort = ADB_PORT;

    private void connectTo(String host, int port, String name) {
        AdbClient open = client;
        client = null;
        if (open != null) io.execute(open::close);
        hostField.setText(port == ADB_PORT ? host : host + ":" + port);
        prefs().edit().putString(HOST, host).putInt(PORT, port).apply();
        show(getString(R.string.connecting, host));
        if (connecting) return;
        connecting = true;
        io.execute(() -> {
            try {
                client = openWithRetry(host, port);
                // Remember the port that worked, not the one that was asked for: a TV saved by the
                // old app at a TLS port is re-saved at 5555, so Saved stops failing the same way.
                prefs().edit().putString(HOST, host).putInt(PORT, connectedPort).apply();
                if (name != null && !name.isEmpty()) Remotes.save(this, name, host, connectedPort);
                ui.post(() -> hostField.setText(connectedPort == ADB_PORT ? host : host + ":" + connectedPort));
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
                AdbClient client = AdbClient.connect(host, port, keys(), 8000);
                connectedPort = port;
                return client;
            } catch (AdbClient.TlsPortException e) {
                // The port speaks TLS, not plain Adb: a TV saved by the old app at a discovered TLS
                // port, or a typed TLS port, gets one try at 5555 instead of three failures.
                if (port == ADB_PORT) throw e;
                return openWithRetry(host, ADB_PORT);
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
            public void found(Discovery.Tv tv) {
                found.put(tv.host, tv);
                show(getString(R.string.tvs_found, found.size()));
            }

            @Override
            public void finished(String problem) {
                discovery = null;
                if (problem != null) {
                    show(getString(R.string.failed, problem));
                } else if (pickable().isEmpty()) {
                    // Nothing found that is a TV with debugging at all: a Cast-only device, if one
                    // was seen, is not a remote target, and the fix is the same either way.
                    show(getString(R.string.no_tvs_found));
                } else if (!hasConnectable()) {
                    // Every TV found only offers Wireless debugging: no connect here could work,
                    // so say what to turn on instead of offering a list of dead ends.
                    showTlsOnly(null);
                } else {
                    show(getString(R.string.tvs_found, pickable().size()));
                    pick(pickable(), R.string.scan);
                }
            }
        });
    }

    /** TVs the picker may offer: connectable ones, and TLS-only ones with their guidance. */
    private List<Discovery.Tv> pickable() {
        List<Discovery.Tv> offered = new ArrayList<>();
        for (Discovery.Tv tv : found.values()) {
            if (tv.offerable()) offered.add(tv);
        }
        return offered;
    }

    /** At least one TV the scan saw listens on 5555. */
    private boolean hasConnectable() {
        for (Discovery.Tv tv : found.values()) if (tv.connectable()) return true;
        return false;
    }

    /** A TV that only offers Wireless debugging, and what to turn on so it can be driven at all. */
    private void showTlsOnly(Discovery.Tv tv) {
        String title = tv == null ? getString(R.string.scan) : tv.label();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(R.string.tv_tls_only_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show();
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

    private void pick(List<Discovery.Tv> tvs, int title) {
        List<String> labels = new ArrayList<>();
        for (Discovery.Tv tv : tvs) {
            labels.add(tv.tlsOnly() ? tv.label() + " — " + getString(R.string.tv_tls_only) : tv.label());
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    Discovery.Tv tv = tvs.get(which);
                    if (tv.tlsOnly()) {
                        showTlsOnly(tv);
                        return;
                    }
                    // Always 5555, never the port the scan found the TV on: a discovered TLS port
                    // speaks TLS, and plain Adb sent there is what used to fail every time.
                    connectTo(tv.host, Discovery.ADB_PORT, tv.name);
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

    // ---- cleaning and customising the TV ---------------------------------------------------------

    /** The apps this phone switched off on the TV, kept so they can be put back after a restart of the phone app. */
    private static final String CLEAN_RESTORE = "clean_restore";

    /**
     * Cleaning: what the TV has, what may be switched off, and what comes back. Nothing here uninstalls anything:
     * an app is switched off for the TV's main user and put back from the same screen.
     */
    private void showClean() {
        show(getString(R.string.clean_reading));
        io.execute(() -> {
            try {
                AdbClient tv = live();
                List<String> installed = Clean.packages(tv.packages());
                List<String> off = Clean.packages(tv.shell("pm list packages -d"));
                List<String> canGo = Clean.offered(installed, off);
                List<String> back = new ArrayList<>();
                for (String pkg : off) if (!Clean.protectedPackage(pkg)) back.add(pkg);
                List<String> point = restorePoint();
                ui.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.clean)
                        .setItems(new String[]{
                                getString(R.string.clean_off, canGo.size()),
                                getString(R.string.clean_put_back, back.size()),
                                getString(R.string.clean_put_back_all, point.size()),
                        }, (dialog, which) -> {
                            if (which == 0) pickToSwitchOff(canGo);
                            else if (which == 1) pickToPutBack(back);
                            else putBack(point);
                        })
                        .show());
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    /** The rows ticked in a multi-choice dialog, in the order they were offered. */
    private static List<String> ticked(List<String> offered, boolean[] ticked) {
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < offered.size(); i++) if (ticked[i]) chosen.add(offered.get(i));
        return chosen;
    }

    private void pickToSwitchOff(List<String> canGo) {
        if (canGo.isEmpty()) {
            show(getString(R.string.clean_nothing));
            return;
        }
        final boolean[] ticked = new boolean[canGo.size()];
        new AlertDialog.Builder(this)
                .setTitle(R.string.clean)
                .setMultiChoiceItems(canGo.toArray(new String[0]), ticked,
                        (dialog, which, checked) -> ticked[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> chosen = ticked(canGo, ticked);
                    if (chosen.isEmpty()) {
                        show(getString(R.string.clean_nothing));
                        return;
                    }
                    confirmSwitchOff(chosen);
                })
                .show();
    }

    private void confirmSwitchOff(List<String> chosen) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clean_confirm_title)
                .setMessage(getString(R.string.clean_confirm, Clean.plan(chosen)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clean, (dialog, which) -> switchOff(chosen))
                .show();
    }

    /** Switched off one shell call per package, and written down before it is reported as done. */
    private void switchOff(List<String> chosen) {
        show(getString(R.string.clean_working, chosen.size()));
        io.execute(() -> {
            try {
                AdbClient tv = live();
                List<String> point = restorePoint();
                for (String pkg : chosen) {
                    tv.shell("pm disable-user --user 0 " + pkg);
                    if (!point.contains(pkg)) point.add(pkg);
                }
                saveRestorePoint(point);
                ui.post(() -> show(getString(R.string.clean_done, chosen.size())));
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    private void pickToPutBack(List<String> off) {
        if (off.isEmpty()) {
            show(getString(R.string.clean_nothing));
            return;
        }
        final boolean[] ticked = new boolean[off.size()];
        new AlertDialog.Builder(this)
                .setTitle(R.string.clean_put_back_title)
                .setMultiChoiceItems(off.toArray(new String[0]), ticked,
                        (dialog, which, checked) -> ticked[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    List<String> chosen = ticked(off, ticked);
                    if (chosen.isEmpty()) {
                        show(getString(R.string.clean_nothing));
                        return;
                    }
                    putBack(chosen);
                })
                .show();
    }

    private void putBack(List<String> packages) {
        if (packages.isEmpty()) {
            show(getString(R.string.clean_nothing));
            return;
        }
        show(getString(R.string.clean_back_working, packages.size()));
        io.execute(() -> {
            try {
                AdbClient tv = live();
                for (String pkg : packages) tv.shell("pm enable " + pkg);
                List<String> point = restorePoint();
                point.removeAll(packages);
                saveRestorePoint(point);
                ui.post(() -> show(getString(R.string.clean_back_done, packages.size())));
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    private List<String> restorePoint() {
        return Clean.decode(prefs().getString(CLEAN_RESTORE, ""));
    }

    private void saveRestorePoint(List<String> packages) {
        prefs().edit().putString(CLEAN_RESTORE, Clean.encode(packages)).apply();
    }

    /**
     * Customising: the Lean TV settings a phone row can carry, written to the launcher's config.json overlay and
     * picked up by a restart of Home. Everything a row cannot carry is on the launcher's own settings screen,
     * which this screen opens on the TV.
     */
    private void showCustomize() {
        show(getString(R.string.customise_reading));
        io.execute(() -> {
            try {
                int[] start = picks(LeanConfig.current(live()));
                ui.post(() -> customizeDialog(start, start));
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
        });
    }

    /** Where each row starts: the value the TV has, or TV default when the file does not name it. */
    private static int[] picks(Map<String, String> now) {
        int[] picks = new int[LeanConfig.SETTINGS.length];
        for (int i = 0; i < picks.length; i++) {
            LeanConfig.Setting setting = LeanConfig.SETTINGS[i];
            for (int v = 0; v < setting.values.length; v++) {
                if (setting.values[v].equals(now.get(setting.key))) picks[i] = v + 1;
            }
        }
        return picks;
    }

    private void customizeDialog(int[] picks, int[] start) {
        String[] rows = new String[LeanConfig.SETTINGS.length + 1];
        for (int i = 0; i < LeanConfig.SETTINGS.length; i++) {
            LeanConfig.Setting setting = LeanConfig.SETTINGS[i];
            rows[i] = getString(setting.label) + ": " + shownValue(setting, picks[i]);
        }
        rows[rows.length - 1] = getString(R.string.customise_restart);
        new AlertDialog.Builder(this)
                .setTitle(R.string.customize)
                .setMessage(R.string.customise_hint)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.customise_open_settings,
                        (dialog, which) -> on(LeanConfig::openSettings))
                .setPositiveButton(R.string.customise_apply, (dialog, which) -> applyCustomize(picks, start))
                .setItems(rows, (dialog, which) -> {
                    if (which == LeanConfig.SETTINGS.length) {
                        on(LeanConfig::restartHome);
                        return;
                    }
                    LeanConfig.Setting setting = LeanConfig.SETTINGS[which];
                    picks[which] = (picks[which] + 1) % (setting.values.length + 1);
                    customizeDialog(picks, start);   // the same screen, with that row moved on
                })
                .show();
    }

    /** A row's value as it reads: TV default, on or off, or the number itself. */
    private String shownValue(LeanConfig.Setting setting, int pick) {
        if (pick == 0) return getString(R.string.set_default);
        String chosen = setting.values[pick - 1];
        if ("true".equals(chosen)) return getString(R.string.set_on);
        if ("false".equals(chosen)) return getString(R.string.set_off);
        return chosen;
    }

    /** Only the rows that moved are written, so a theme pushed from a computer keeps everything else it set. */
    private void applyCustomize(int[] picks, int[] start) {
        Map<String, String> changes = new LinkedHashMap<>();
        for (int i = 0; i < picks.length; i++) {
            if (picks[i] == start[i]) continue;
            LeanConfig.Setting setting = LeanConfig.SETTINGS[i];
            changes.put(setting.key, picks[i] == 0 ? null : setting.values[picks[i] - 1]);
        }
        if (changes.isEmpty()) {
            show(getString(R.string.clean_nothing));
            return;
        }
        show(getString(R.string.customise_writing));
        io.execute(() -> {
            try {
                LeanConfig.apply(live(), changes);
                ui.post(() -> show(getString(R.string.customise_saved)));
            } catch (IOException | RuntimeException e) {
                fail(e);
            }
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
