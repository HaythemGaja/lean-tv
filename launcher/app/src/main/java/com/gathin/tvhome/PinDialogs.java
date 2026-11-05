package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;

/**
 * The one screen that sets, changes and removes the arrow pattern, and the result it hands back. A remote has no
 * keyboard worth typing on, so there is no text field and no on-screen keyboard: the pattern is pressed on the
 * D-pad, twice, on {@link PinActivity}.
 */
final class PinDialogs {
    /** The request code that brings a new pattern back; each activity compares it in onActivityResult. */
    static final int REQUEST_SET_PATTERN = 45;

    private PinDialogs() { }

    /** Opens the pattern screen to choose a new one; the activity's result carries it back. */
    static void setPin(Activity activity) {
        activity.startActivityForResult(new Intent(activity, PinActivity.class)
                .putExtra(PinActivity.EXTRA_CHOOSE, true), REQUEST_SET_PATTERN);
    }

    /** Stores the pattern the screen returned; true when it did, so the caller can refresh or run its action. */
    static boolean takePattern(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_SET_PATTERN || resultCode != Activity.RESULT_OK || data == null) return false;
        String pattern = data.getStringExtra(PinActivity.EXTRA_PATTERN);
        if (!PinLock.setPattern(activity, pattern)) return false;
        toast(activity, R.string.pin_saved);
        return true;
    }

    /**
     * Removes the pattern, and with it every app and the settings lock it held, after a confirmation.
     */
    static void clearPin(Activity activity, Runnable done) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.pin_clear)
                .setMessage(R.string.pin_clear_confirm)
                .setPositiveButton(R.string.pin_clear, (dialog, which) -> {
                    PinLock.clear(activity);
                    toast(activity, R.string.pin_removed);
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void toast(Activity activity, int text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
