package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One folder, full screen: the folders inside it first, then its apps, each tile sized like Home's. Off by default
 * (config.xml {@code folders}); a tile in {@link AppAdapter} opens it.
 *
 * <p>Hold OK on a tile for the same kind of glass menu Home uses: move an app back to Home, file it into another
 * folder, lock it; or fill, rename, lock and delete a folder inside this one. Back returns to the folder above, or
 * to Home, which redraws when a folder changed.
 */
public final class FolderActivity extends Activity implements FolderAdapter.Listener {
    static final String EXTRA_FOLDER = "com.gathin.tvhome.extra.FOLDER";
    private static final int REQUEST_PIN = 4101;
    private static final int REQUEST_FOLDER = 4102;

    private Config cfg;
    private Folders folders;
    private String folderId;
    private FolderAdapter adapter;
    private RecyclerView grid;
    private TextView title, subtitle, hint;
    private PopupWindow openPopup;
    private Runnable pendingPinAction;
    private boolean pinEnabled;
    private boolean pinRequired;
    private boolean unlocked;
    private boolean pinAsked;
    private boolean changed;
    private float density;
    private int columns;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        folderId = getIntent().getStringExtra(EXTRA_FOLDER);
        cfg = Config.load(this);
        folders = Folders.load(this);
        Folders.Folder folder = folders.folder(folderId);
        if (folder == null) {
            finish();
            return;
        }
        pinEnabled = cfg.pinLock && PinLock.hasPin(this);
        pinRequired = pinEnabled && folder.locked;
        density = getResources().getDisplayMetrics().density;
        columns = Math.max(2, Math.min(cfg.columns, 6));
        setContentView(R.layout.activity_folder);
        getWindow().getDecorView().setBackgroundColor(cfg.backgroundColor);
        title = findViewById(R.id.folderTitle);
        subtitle = findViewById(R.id.folderSubtitle);
        hint = findViewById(R.id.folderHint);
        grid = findViewById(R.id.folderGrid);
        grid.setLayoutManager(new GridLayoutManager(this, columns));
        grid.setClipToPadding(false);
        adapter = new FolderAdapter(this, this, columns, cfg.animations, cfg.reducedMotion);
        grid.setAdapter(adapter);
        reload();
    }

    /**
     * The pattern is asked here, not in onCreate: if the folder screen is rebuilt while the pattern screen
     * is up (a 1 GB TV reclaiming memory), Android delivers the pattern result to the new instance before
     * its first resume, so by the time this runs {@code unlocked} is already true and it does not ask again.
     * Asking in onCreate would launch a second pattern screen on top of the result that just came back, and
     * the right pattern would land the person on the lock screen again. If no result arrives (the whole
     * process died), this still asks, so the folder is never left open without its pattern.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (pinRequired && !unlocked && !pinAsked && !isFinishing()) askPin(null);
    }

    /** The folder's name, how many apps it holds, and a line of help when it is empty. */
    private void reload() {
        Folders.Folder folder = folders.folder(folderId);
        if (folder == null) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        title.setText(folder.name);
        int count = folders.count(folderId);
        subtitle.setText(getString(R.string.folder_apps_count, count));
        hint.setText(count == 0 ? R.string.folder_empty : R.string.folder_hint);
        adapter.setEntries(entries(folder));
        grid.post(this::focusFirst);
    }

    /** The first tile takes focus, so the D-pad works the moment the folder opens or is redrawn. */
    private void focusFirst() {
        if (getCurrentFocus() != null) return;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View card = grid.getChildAt(i).findViewById(R.id.card);
            if (card != null) {
                card.requestFocus();
                return;
            }
        }
    }

    private List<FolderAdapter.Entry> entries(Folders.Folder folder) {
        List<FolderAdapter.Entry> out = new ArrayList<>();
        java.util.Set<String> hiddenHere = HiddenApps.all(this);
        boolean guest = GuestMode.on(this);
        for (Folders.Folder sub : folders.subfolders(folder.id)) {
            if (guest && pinEnabled && sub.locked) continue;   // guest mode: a locked folder stays hidden
            FolderAdapter.Entry tile = FolderAdapter.Entry.folder(sub.id, sub.name,
                    getDrawable(R.drawable.ic_folder), pinEnabled && sub.locked);
            tile.cover = cover(sub.id, hiddenHere, guest);
            out.add(tile);
        }
        Map<String, ResolveInfo> installed = installed();
        PackageManager pm = getPackageManager();
        for (String pkg : folders.apps(folder.id)) {
            if (hiddenHere.contains(pkg)) continue;                                    // hidden on this TV
            if (guest && pinEnabled && PinLock.locked(this, pkg)) continue;            // guest mode
            ResolveInfo ri = installed.get(pkg);
            if (ri == null) continue;   // uninstalled since it was filed: nothing to open
            String label = cfg.appLabels.containsKey(pkg) ? cfg.appLabels.get(pkg)
                    : Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            out.add(FolderAdapter.Entry.app(pm, label, ri, FolderAdapter.banner(this, pm, ri),
                    leanback.contains(pkg), PinLock.locked(this, pkg)));
        }
        return out;
    }

    /** Up to four app icons for a folder tile, in the folder's own order; null when it has none to draw. */
    private Drawable[] cover(String id, java.util.Set<String> hiddenHere, boolean guest) {
        Drawable[] icons = new Drawable[4];
        int found = 0;
        PackageManager pm = getPackageManager();
        for (String pkg : folders.apps(id)) {
            if (found == 4) break;
            if (hiddenHere.contains(pkg) || (guest && PinLock.locked(this, pkg))) continue;
            try {
                icons[found++] = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
                // uninstalled since it was filed
            }
        }
        return found == 0 ? null : icons;
    }

    private final java.util.Set<String> leanback = new java.util.HashSet<>();

    /** Every installed launcher entry, by package, and which of them are TV apps. */
    private Map<String, ResolveInfo> installed() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> found = new HashMap<>();
        leanback.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)) {
            if (ri.activityInfo == null) continue;
            found.put(ri.activityInfo.packageName, ri);
            leanback.add(ri.activityInfo.packageName);
        }
        for (ResolveInfo ri : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (ri.activityInfo != null) found.putIfAbsent(ri.activityInfo.packageName, ri);
        }
        return found;
    }

    // ---- opening ---------------------------------------------------------------------------------

    @Override
    public void onOpen(FolderAdapter.Entry entry) {
        if (entry.kind == FolderAdapter.FOLDER) {
            startActivityForResult(new Intent(this, FolderActivity.class).putExtra(EXTRA_FOLDER, entry.folderId),
                    REQUEST_FOLDER);
            return;
        }
        Runnable launch = () -> launch(entry);
        if (pinEnabled && PinLock.locked(this, entry.pkg)) askPin(launch);
        else launch.run();
    }

    /** The app itself, with the leanback category when it is a TV app, as Home launches its own tiles. */
    private void launch(FolderAdapter.Entry entry) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (entry.leanback) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        String action = cfg.launchActions.get(entry.pkg);
        // An input:<id> action belongs to Home, which knows the TV's inputs; inside a folder the app opens plainly.
        if (action != null && LaunchActions.inputId(action) == null) {
            Intent byAction = LaunchActions.intent(entry.pkg, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (getPackageManager().resolveActivity(byAction, 0) != null) intent = byAction;
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.cant_open, entry.label), Toast.LENGTH_SHORT).show();
        }
    }

    /** One pattern entry per locked tile: unlocking one app says nothing about the next. */
    private void askPin(Runnable action) {
        pinAsked = true;
        pendingPinAction = action;
        Intent intent = new Intent(this, PinActivity.class);
        startActivityForResult(intent, REQUEST_PIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PIN) {
            if (resultCode == RESULT_OK) {
                unlocked = true;
                Runnable action = pendingPinAction;
                pendingPinAction = null;
                if (action != null) action.run();
            } else {
                // A cancelled pattern must not leave a stale action behind, and must not latch the
                // folder ask shut: a later resume in this same instance may still need to ask.
                pendingPinAction = null;
                if (pinRequired && !unlocked) finish();
                else pinAsked = false;
            }
            return;
        }
        if (requestCode == PinDialogs.REQUEST_SET_PATTERN) {
            Runnable action = pendingPinAction;
            pendingPinAction = null;
            if (PinDialogs.takePattern(this, requestCode, resultCode, data) && action != null) action.run();
            return;
        }
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            changed = true;
            reload();
        }
    }

    // ---- the tile menu ---------------------------------------------------------------------------

    @Override
    public void onMenu(FolderAdapter.Entry entry, View card) {
        if (entry.kind == FolderAdapter.FOLDER) showFolderMenu(entry, card);
        else showAppMenu(entry, card);
    }

    private void showAppMenu(FolderAdapter.Entry entry, View card) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, grid, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel);
        addRow(rows, popup, R.string.folder_move_home, () -> {
            folders.removeApp(folderId, entry.pkg);
            save();
        });
        addRow(rows, popup, R.string.folder_add_to,
                () -> FolderMenus.addToFolder(this, folders, entry.pkg, this::save));
        if (pinEnabled) {
            addRow(rows, popup, PinLock.locked(this, entry.pkg) ? R.string.pin_unlock : R.string.pin_lock_app,
                    () -> toggleAppPin(entry.pkg));
        }
        addRow(rows, popup, R.string.hide_app, () -> {
            HiddenApps.set(this, entry.pkg, true);
            Toast.makeText(this, R.string.app_hidden, Toast.LENGTH_SHORT).show();
            save();
        });
        Intent info = SystemSettings.appInfo(this, entry.pkg);
        if (info != null) addRow(rows, popup, R.string.library_app_info, () -> open(info));
        show(panel, rows, popup, card);
    }

    private void showFolderMenu(FolderAdapter.Entry entry, View card) {
        Folders.Folder folder = folders.folder(entry.folderId);
        if (folder == null) return;
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, grid, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        PopupWindow popup = glassPopup(panel);
        addRow(rows, popup, R.string.folder_open, () ->
                startActivityForResult(new Intent(this, FolderActivity.class)
                        .putExtra(EXTRA_FOLDER, folder.id), REQUEST_FOLDER));
        addRow(rows, popup, R.string.folder_add_apps, () -> FolderMenus.addApps(this, folders, folder.id, this::save));
        if (folders.depth(folder.id) < Folders.MAX_DEPTH) {
            addRow(rows, popup, R.string.folder_new_inside,
                    () -> FolderMenus.create(this, folders, folder.id, null, this::save));
        }
        addRow(rows, popup, R.string.folder_rename, () -> FolderMenus.rename(this, folders, folder.id, this::save));
        if (pinEnabled) {
            addRow(rows, popup, folder.locked ? R.string.pin_unlock : R.string.pin_lock_folder,
                    () -> toggleFolderPin(folder.id));
        }
        addRow(rows, popup, R.string.folder_move_home, () -> {
            folders.move(folder.id, null);
            save();
        });
        addRow(rows, popup, R.string.folder_remove, () -> FolderMenus.remove(this, folders, folder.id, this::save));
        show(panel, rows, popup, card);
    }

    private void toggleAppPin(String pkg) {
        if (PinLock.locked(this, pkg)) {
            PinLock.setLocked(this, pkg, false);
            save();
            return;
        }
        lockWithPin(() -> {
            PinLock.setLocked(this, pkg, true);
            save();
        });
    }

    private void toggleFolderPin(String id) {
        Folders.Folder folder = folders.folder(id);
        if (folder == null) return;
        if (folder.locked) {
            folders.setLocked(id, false);
            save();
            return;
        }
        lockWithPin(() -> {
            folders.setLocked(id, true);
            save();
        });
    }

    /**
     * Adds a lock, or picks a pattern first when none is set yet: a lock needs a key. The lock runs once the
     * pattern screen stores one.
     */
    private void lockWithPin(Runnable lock) {
        if (PinLock.hasPin(this)) {
            lock.run();
            return;
        }
        Toast.makeText(this, R.string.pin_needed, Toast.LENGTH_SHORT).show();
        pendingPinAction = lock;
        PinDialogs.setPin(this);
    }

    /** Writes the tree and redraws this folder; Home picks the change up from this activity's result. */
    private void save() {
        if (!folders.save(this)) {
            Toast.makeText(this, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        changed = true;
        reload();
    }

    private PopupWindow glassPopup(View panel) {
        PopupWindow popup = new PopupWindow(panel, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // Back dismisses it
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> openPopup = null);
        openPopup = popup;
        return popup;
    }

    private void show(View panel, LinearLayout rows, PopupWindow popup, View card) {
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        popup.setWidth(Math.max((int) (200 * density), panel.getMeasuredWidth()));
        // A long menu under a low tile runs off the bottom of a 720p screen: capping its height to
        // the room below does not save it when that room is near zero, because the popup itself
        // would still start below the tile and end past the screen edge. So pick the side with
        // more room: below when it fits, above when it does not, and cap to that side's room (at
        // least one row) so the ScrollView in popup_inputs can scroll the rest into view.
        // Room is measured against the window's decor height, in the same coordinates as the
        // tile's location in window — not the display height, which over-reads under an inset.
        int gap = (int) (8 * density);
        int[] at = new int[2];
        card.getLocationInWindow(at);
        int windowHeight = getWindow().getDecorView().getHeight();
        int belowRoom = windowHeight - (at[1] + card.getHeight() + gap) - (int) (16 * density);
        int aboveRoom = at[1] - gap - (int) (16 * density);
        int measured = panel.getMeasuredHeight();
        boolean above = belowRoom < measured && aboveRoom > belowRoom;
        int height = above ? Math.min(measured, Math.max(aboveRoom, (int) (48 * density)))
                : Math.min(measured, Math.max(belowRoom, (int) (48 * density)));
        popup.setHeight(height);
        if (above) {
            popup.showAtLocation(grid, Gravity.NO_GRAVITY, at[0], Math.max(0, at[1] - gap - height));
        } else {
            popup.showAsDropDown(card, 0, gap, Gravity.START);
        }
        rows.getChildAt(0).requestFocus();
    }

    /** A glass row: the same item_input row Home's menus use, with the icon, dot and state left out. */
    private void addRow(LinearLayout rows, PopupWindow popup, int title, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        // The OK-up that opened the menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) pressed[0] = true;
                return true;
            }
            boolean activate = pressed[0];
            pressed[0] = false;
            if (activate) {
                popup.dismiss();
                action.run();
            }
            return true;
        });
        rows.addView(row);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Back to the folder above: it redraws when this one changed a folder it shows. */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        setResult(changed ? RESULT_OK : RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (openPopup != null) openPopup.dismiss();
    }
}
