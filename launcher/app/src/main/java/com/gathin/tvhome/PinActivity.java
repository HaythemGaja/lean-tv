package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The invisible arrow pattern a locked app, folder or the settings asks for: Up, Down, Left and Right on the
 * D-pad are steps, Delete takes the last one back, OK finishes a pattern being chosen and Back gives up.
 * Finishes with {@link #RESULT_OK} only for the right pattern; the caller then runs the action it held back.
 *
 * <p>A dot appears for each press, so an entry that reaches the screen can be seen to be working and a mistake can
 * be taken back before the pattern is finished. What is never shown is which arrow was pressed, so the pattern
 * cannot be read over someone's shoulder. In unlock mode the screen submits by itself at the stored length, which
 * is what makes an entry workable without reaching for OK.
 *
 * <p>A wrong pattern says so and clears the entry: there is no wait before the next try.
 *
 * <p>Choosing a pattern ({@link #EXTRA_CHOOSE}) asks for it twice and returns it in {@link #EXTRA_PATTERN}.
 *
 * <p>Nothing here says what is being unlocked, only its name, so a pattern drawn at one tile is no proof for
 * another: every locked tile asks again when it is opened. Keys from a phone remote, ADB or a vendor app arrive
 * as ordinary D-pad events and draw the same pattern; nothing on this screen is reachable over the network.
 */
public final class PinActivity extends Activity {
    static final String EXTRA_LABEL = "com.gathin.tvhome.extra.PIN_LABEL";
    static final String EXTRA_CHOOSE = "com.gathin.tvhome.extra.PIN_CHOOSE";
    static final String EXTRA_PATTERN = "com.gathin.tvhome.extra.PIN_PATTERN";

    private final StringBuilder entry = new StringBuilder();

    private int length = PinLock.MIN_STEPS;
    private boolean choosing;
    private boolean repeating;
    private String chosen = "";
    private TextView title;
    private TextView message;
    private TextView dots;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_pin);
        choosing = getIntent().getBooleanExtra(EXTRA_CHOOSE, false);
        length = PinLock.steps(this);
        title = findViewById(R.id.pinTitle);
        message = findViewById(R.id.pinMessage);
        dots = findViewById(R.id.pinDots);
        title.setText(choosing ? R.string.pin_choose : R.string.pin_enter);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        // The hint has to match the screen: unlocking submits itself, choosing a new pattern still ends on OK.
        ((TextView) findViewById(R.id.pinHint)).setText(choosing ? R.string.pin_hint : R.string.pin_hint_unlock);
        TextView subtitle = findViewById(R.id.pinSubtitle);
        if (label == null || label.isEmpty()) subtitle.setVisibility(android.view.View.GONE);
        else subtitle.setText(label);
    }

    /**
     * The D-pad draws the pattern instead of moving focus. A held direction is one step, not the repeat events
     * that follow it, and every other key keeps its usual meaning, Back included.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);
        char step = stepOf(event.getKeyCode());
        if (step != 0) {
            if (event.getRepeatCount() == 0) add(step);
            return true;
        }
        if (event.getRepeatCount() != 0) return super.dispatchKeyEvent(event);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                submit();
                return true;
            case KeyEvent.KEYCODE_DEL:
                back();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    /** The arrow a keycode draws, or 0 for any other key. */
    private static char stepOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return 'U';
            case KeyEvent.KEYCODE_DPAD_DOWN: return 'D';
            case KeyEvent.KEYCODE_DPAD_LEFT: return 'L';
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 'R';
            default: return 0;
        }
    }

    private void add(char step) {
        if (entry.length() >= PinLock.MAX_STEPS) return;
        entry.append(step);
        // A dot says a press arrived; which arrow it was stays off the screen, and an old message stops applying.
        message.setText("");
        render();
        if (PinLock.submitsAt(choosing, entry.length(), length)) submit();
    }

    private void back() {
        if (entry.length() == 0) return;
        entry.setLength(entry.length() - 1);
        render();
    }

    /**
     * One dot per step pressed, and nothing about which step it was: enough to see that the remote is reaching the
     * screen and to count the entry without putting the pattern itself on display.
     */
    private void render() {
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) shown.append("\u25CF ");
        dots.setText(shown.toString().trim());
    }

    private void submit() {
        if (entry.length() == 0) return;
        if (choosing) {
            choose();
            return;
        }
        if (entry.length() != length) {
            // A blind entry that stops early is not a wrong pattern, so it does not count as one.
            message.setText(R.string.pin_keep_going);
            return;
        }
        check();
    }

    /** Picks a new pattern: the first entry, then the same arrows again, since a blind entry can slip. */
    private void choose() {
        if (!PinLock.validPattern(entry.toString())) {
            message.setText(R.string.pin_invalid);
            return;
        }
        if (!repeating) {
            chosen = entry.toString();
            entry.setLength(0);
            repeating = true;
            title.setText(R.string.pin_repeat);
            message.setText("");
            render();
            return;
        }
        if (!chosen.equals(entry.toString())) {
            chosen = "";
            entry.setLength(0);
            repeating = false;
            title.setText(R.string.pin_choose);
            message.setText(R.string.pin_mismatch);
            render();
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATTERN, chosen));
        finish();
    }

    private void check() {
        String salt = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(PinLock.KEY_SALT, "");
        String hash = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).getString(PinLock.KEY_HASH, "");
        if (PinLock.verify(entry.toString(), salt, hash)) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        entry.setLength(0);
        message.setText(R.string.pin_wrong);
        render();
    }
}
