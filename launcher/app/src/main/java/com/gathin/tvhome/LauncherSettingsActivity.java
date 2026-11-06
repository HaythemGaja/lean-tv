package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private static final String[] CLOCK_FORMATS = {"system", "12", "24"};
    private final TextView[] clockChoices = new TextView[CLOCK_FORMATS.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private TextView pinChoice;
    private TextView pinSettingsChoice;
    private TextView pinClearChoice;
    private TextView pinGraceChoice;
    private TextView pinScheduleChoice;
    private TextView hiddenChoice;
    private Runnable pendingPinAction;
    private static final int REQUEST_PIN = 4201;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private String clockFormat;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        clockFormat = config.clockFormat;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] clockIds = {R.id.clockSystem, R.id.clock12, R.id.clock24};
        for (int i = 0; i < clockIds.length; i++) {
            final String value = CLOCK_FORMATS[i];
            clockChoices[i] = findViewById(clockIds[i]);
            clockChoices[i].setOnClickListener(v -> {
                if (value.equals(clockFormat)) return;
                clockFormat = value;
                choices.edit().putString(Config.CLOCK_FORMAT_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
            reverseRow(clockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        // pin_lock: the pattern that guards apps, folders and, with the row below, these settings too.
        pinChoice = findViewById(R.id.pinChoice);
        pinSettingsChoice = findViewById(R.id.pinSettingsChoice);
        pinClearChoice = findViewById(R.id.pinClearChoice);
        pinGraceChoice = findViewById(R.id.pinGraceChoice);
        pinScheduleChoice = findViewById(R.id.pinScheduleChoice);
        if (config.pinLock) {
            pinChoice.setVisibility(View.VISIBLE);
            pinSettingsChoice.setVisibility(View.VISIBLE);
            pinClearChoice.setVisibility(View.VISIBLE);
            pinGraceChoice.setVisibility(View.VISIBLE);
            pinScheduleChoice.setVisibility(View.VISIBLE);
            pinChoice.setOnClickListener(v -> PinDialogs.setPin(this));
            pinSettingsChoice.setOnClickListener(v -> {
                if (!PinLock.hasPin(this)) {
                    Toast.makeText(this, R.string.pin_needed, Toast.LENGTH_SHORT).show();
                    return;
                }
                PinLock.setSettingsLocked(this, !PinLock.settingsLocked(this));
                refresh();
            });
            pinClearChoice.setOnClickListener(v -> PinDialogs.clearPin(this, this::refresh));
            // Grace: tap cycles Off, 5, 10, 15, 30 minutes, 1 hour. It only means something with
            // a pattern set, so the row is dimmed until then, like Lock settings above it.
            pinGraceChoice.setOnClickListener(v -> {
                if (!PinLock.hasPin(this)) {
                    Toast.makeText(this, R.string.pin_needed, Toast.LENGTH_SHORT).show();
                    return;
                }
                int current = PinLock.graceMinutes(this);
                int next = PinLock.GRACE_OPTIONS[0];
                for (int i = 0; i < PinLock.GRACE_OPTIONS.length; i++) {
                    if (PinLock.GRACE_OPTIONS[i] == current) {
                        next = PinLock.GRACE_OPTIONS[(i + 1) % PinLock.GRACE_OPTIONS.length];
                        break;
                    }
                }
                PinLock.setGraceMinutes(this, next);
                refresh();
            });
            pinScheduleChoice.setOnClickListener(v -> {
                if (!PinLock.hasPin(this)) {
                    Toast.makeText(this, R.string.pin_needed, Toast.LENGTH_SHORT).show();
                    return;
                }
                showPinScheduleDays();
            });
        }
        hiddenChoice = findViewById(R.id.hiddenChoice);
        hiddenChoice.setOnClickListener(v -> showHiddenApps());
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(Config.CLOCK_FORMAT_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            clockFormat = current.clockFormat;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v ->
                open(new Intent(this, HomeSettingsActivity.class)));
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The grace choice as the row shows it: Off, a number of minutes, or 1 hour. */
    private String graceLabel() {
        int minutes = PinLock.graceMinutes(this);
        if (minutes <= 0) return getString(R.string.off);
        if (minutes >= 60) return getString(R.string.pin_grace_hour);
        return getString(R.string.pin_grace_minutes, minutes);
    }

    /** The PIN-free window as the row shows it: Off, days and hours such as "Mon–Fri 08:00–15:00", or All day. */
    private String scheduleLabel() {
        int start = PinLock.freeStart(this);
        int end = PinLock.freeEnd(this);
        int days = PinLock.freeDays(this);
        if (start < 0 || end < 0 || days == 0) return getString(R.string.off);
        // Start and end the same hour is the whole day, and "08:00–08:00" would read as an empty window.
        if (start == end) return daysLabel(days) + " " + getString(R.string.pin_schedule_all_day);
        return daysLabel(days) + " " + String.format(java.util.Locale.getDefault(),
                "%02d:00–%02d:00", start, end);
    }

    /** Days as short names in the TV's language, Monday first; the whole week and Mon–Fri read as one range. */
    private String daysLabel(int daysMask) {
        String[] shortDays = new java.text.DateFormatSymbols().getShortWeekdays();
        if (daysMask == 0x7F) return getString(R.string.pin_schedule_every_day);
        if (daysMask == 0x3E) {
            return shortDays[java.util.Calendar.MONDAY] + "–" + shortDays[java.util.Calendar.FRIDAY];
        }
        StringBuilder sb = new StringBuilder();
        for (int day : WEEKDAYS_MONDAY_FIRST) {
            if ((daysMask & (1 << (day - 1))) != 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(shortDays[day]);
            }
        }
        return sb.toString();
    }

    /** Monday first, for the days picker and the label; the bit for a day is 1 << (day - 1). */
    private static final int[] WEEKDAYS_MONDAY_FIRST = {
            java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY,
            java.util.Calendar.THURSDAY, java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY,
            java.util.Calendar.SUNDAY};

    /**
     * PIN-free hours, in three taps: which days, from when, until when. Each step is saved as it is picked,
     * so backing out of a later step keeps the earlier ones; a window only runs once all three are set.
     */
    private void showPinScheduleDays() {
        String[] shortDays = new java.text.DateFormatSymbols().getShortWeekdays();
        String[] labels = new String[WEEKDAYS_MONDAY_FIRST.length];
        boolean[] checked = new boolean[WEEKDAYS_MONDAY_FIRST.length];
        int days = PinLock.freeDays(this);
        for (int i = 0; i < WEEKDAYS_MONDAY_FIRST.length; i++) {
            labels[i] = shortDays[WEEKDAYS_MONDAY_FIRST[i]];
            checked[i] = (days & (1 << (WEEKDAYS_MONDAY_FIRST[i] - 1))) != 0;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.pin_schedule_days)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int mask = 0;
                    for (int i = 0; i < WEEKDAYS_MONDAY_FIRST.length; i++) {
                        if (checked[i]) mask |= 1 << (WEEKDAYS_MONDAY_FIRST[i] - 1);
                    }
                    PinLock.setFreeSchedule(this, PinLock.freeStart(this), PinLock.freeEnd(this), mask);
                    refresh();
                    // No days picked is a window that can never run: no point walking through the hours for it.
                    if (mask != 0) showPinScheduleStart();
                })
                .setNeutralButton(R.string.pin_schedule_off, (dialog, which) -> {
                    PinLock.clearFreeSchedule(this);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showPinScheduleStart() {
        showPinScheduleHour(R.string.pin_schedule_start, PinLock.freeStart(this), start -> {
            PinLock.setFreeSchedule(this, start, PinLock.freeEnd(this), PinLock.freeDays(this));
            refresh();
            showPinScheduleEnd();
        });
    }

    private void showPinScheduleEnd() {
        showPinScheduleHour(R.string.pin_schedule_end, PinLock.freeEnd(this), end -> {
            PinLock.setFreeSchedule(this, PinLock.freeStart(this), end, PinLock.freeDays(this));
            refresh();
        });
    }

    /** One list of hours, 00:00 to 23:00; the pick is saved at once, then the next step runs. */
    private void showPinScheduleHour(int title, int current, HourPicked onPick) {
        String[] labels = new String[24];
        for (int i = 0; i < 24; i++) {
            labels[i] = String.format(java.util.Locale.getDefault(), "%02d:00", i);
        }
        int checked = current >= 0 && current < 24 ? current : 8;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onPick.onHour(which);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A picked hour, for the PIN-free start and end dialogs. */
    private interface HourPicked {
        void onHour(int hour);
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        for (int i = 0; i < clockChoices.length; i++) {
            clockChoices[i].setSelected(CLOCK_FORMATS[i].equals(clockFormat));
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        boolean hasPin = PinLock.hasPin(this);
        pinChoice.setText(hasPin ? R.string.pin_change : R.string.pin_set);
        pinSettingsChoice.setText(getString(R.string.pin_settings_state,
                getString(PinLock.settingsLocked(this) ? R.string.on : R.string.off)));
        pinSettingsChoice.setEnabled(hasPin);
        pinSettingsChoice.setAlpha(hasPin ? 1f : 0.5f);
        pinClearChoice.setEnabled(hasPin);
        pinClearChoice.setAlpha(hasPin ? 1f : 0.5f);
        pinGraceChoice.setText(getString(R.string.pin_grace_state, graceLabel()));
        pinGraceChoice.setEnabled(hasPin);
        pinGraceChoice.setAlpha(hasPin ? 1f : 0.5f);
        pinScheduleChoice.setText(getString(R.string.pin_schedule_state, scheduleLabel()));
        pinScheduleChoice.setEnabled(hasPin);
        pinScheduleChoice.setAlpha(hasPin ? 1f : 0.5f);
        int hidden = HiddenApps.count(this);
        hiddenChoice.setVisibility(hidden == 0 ? View.GONE : View.VISIBLE);
        hiddenChoice.setText(getString(R.string.hidden_apps_count, hidden));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    /** The pattern screen returns a new pattern; take it and redraw the rows that name it. */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PIN) {
            Runnable action = pendingPinAction;
            pendingPinAction = null;
            if (resultCode == RESULT_OK) {
                PinLock.recordUnlock(this);
                if (action != null) action.run();
            }
            return;
        }
        if (PinDialogs.takePattern(this, requestCode, resultCode, data)) refresh();
    }

    /** Hidden apps come back one at a time, and the pattern guards the list: hiding must not be undone by a child. */
    private void showHiddenApps() {
        if (HiddenApps.count(this) == 0) {
            Toast.makeText(this, R.string.hidden_none, Toast.LENGTH_SHORT).show();
            return;
        }
        withPattern(this::pickHiddenApp);
    }

    /** The pattern first when one is set: without it there is nothing to protect. */
    private void withPattern(Runnable action) {
        if (!PinLock.hasPin(this) || !PinLock.needsPin(this)) {
            action.run();
            return;
        }
        pendingPinAction = action;
        startActivityForResult(new Intent(this, PinActivity.class), REQUEST_PIN);
    }

    private void pickHiddenApp() {
        final List<String> packages = new ArrayList<>(HiddenApps.all(this));
        Collections.sort(packages);
        if (packages.isEmpty()) {
            Toast.makeText(this, R.string.hidden_none, Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        String[] labels = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) labels[i] = appLabel(packages.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.hidden_apps)
                .setMessage(R.string.hidden_hint)
                .setItems(labels, (dialog, which) -> {
                    HiddenApps.set(this, packages.get(which), false);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(Config.CLOCK_FORMAT_KEY) || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
