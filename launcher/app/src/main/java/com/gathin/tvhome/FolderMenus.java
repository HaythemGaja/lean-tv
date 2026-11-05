package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The dialogs that change the folder tree: make a folder, put an app in one, fill a folder with apps, rename it
 * and delete it. Every one of them leaves saving to the caller's {@code done}, which writes the tree and redraws
 * whichever screen asked.
 */
final class FolderMenus {
    private FolderMenus() { }

    /** Asks for a name, makes the folder in {@code parentId} (null for Home) and puts {@code firstPkg} in it. */
    static void create(Activity activity, Folders folders, String parentId, String firstPkg, Runnable done) {
        EditText field = nameField(activity, defaultName(activity, folders, parentId));
        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_new)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Folders.Folder folder = folders.add(field.getText().toString(), parentId);
                    if (folder == null) {
                        toast(activity, R.string.folder_full);
                        return;
                    }
                    if (firstPkg != null) folders.addApp(folder.id, firstPkg);
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Picks the folder an app goes into, with "New folder" first for one that does not exist yet. */
    static void addToFolder(Activity activity, Folders folders, String pkg, Runnable done) {
        final List<Folders.Folder> all = folders.all();
        String[] labels = new String[all.size() + 1];
        labels[0] = activity.getString(R.string.folder_new);
        for (int i = 0; i < all.size(); i++) {
            Folders.Folder folder = all.get(i);
            StringBuilder label = new StringBuilder();
            for (int depth = 1; depth < folders.depth(folder.id); depth++) label.append("    ");
            labels[i + 1] = label.append(folder.name).toString();
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_add_to)
                .setItems(labels, (dialog, which) -> {
                    if (which == 0) {
                        create(activity, folders, null, pkg, done);
                        return;
                    }
                    Folders.Folder folder = all.get(which - 1);
                    if (!folders.addApp(folder.id, pkg)) {
                        toast(activity, R.string.folder_full);
                        return;
                    }
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Ticks apps to put in this folder; apps already inside it (or a folder in it) are not offered. */
    static void addApps(Activity activity, Folders folders, String folderId, Runnable done) {
        List<String[]> installed = installed(activity);
        List<String[]> offered = new ArrayList<>();
        for (String[] app : installed) {
            String from = folders.folderOf(app[1]);
            if (from != null && folders.inSubtree(folderId, from)) continue;
            offered.add(app);
        }
        if (offered.isEmpty()) {
            toast(activity, R.string.folder_no_apps);
            return;
        }
        String[] labels = new String[offered.size()];
        boolean[] checked = new boolean[offered.size()];
        for (int i = 0; i < offered.size(); i++) labels[i] = offered.get(i)[0];
        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_add_apps)
                .setMultiChoiceItems(labels, checked, (dialog, which, on) -> checked[which] = on)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    boolean any = false;
                    for (int i = 0; i < offered.size(); i++) {
                        if (checked[i] && folders.addApp(folderId, offered.get(i)[1])) any = true;
                    }
                    if (any) done.run();
                    else toast(activity, R.string.folder_no_apps);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static void rename(Activity activity, Folders folders, String folderId, Runnable done) {
        Folders.Folder folder = folders.folder(folderId);
        if (folder == null) return;
        EditText field = nameField(activity, folder.name);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_rename)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (!folders.rename(folderId, field.getText().toString())) {
                        toast(activity, R.string.pin_invalid);
                        return;
                    }
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Deletes the folder and the folders in it; its apps go back to Home rather than being uninstalled. */
    static void remove(Activity activity, Folders folders, String folderId, Runnable done) {
        Folders.Folder folder = folders.folder(folderId);
        if (folder == null) return;
        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_remove)
                .setMessage(activity.getString(R.string.folder_remove_confirm, folder.name))
                .setPositiveButton(R.string.folder_remove, (dialog, which) -> {
                    if (!folders.remove(folderId)) return;
                    done.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** "Folder 3": the next number no folder inside this one already uses. */
    private static String defaultName(Activity activity, Folders folders, String parentId) {
        Set<String> taken = new HashSet<>();
        for (Folders.Folder folder : folders.subfolders(parentId)) taken.add(folder.name);
        for (int i = 1; i <= Folders.MAX_FOLDERS; i++) {
            String name = activity.getString(R.string.folder_default_name, i);
            if (!taken.contains(name)) return name;
        }
        return activity.getString(R.string.folder_default_name, 1);
    }

    private static EditText nameField(Activity activity, String text) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setSingleLine(true);
        field.setSelectAllOnFocus(true);
        field.setText(text);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Folders.MAX_NAME)});
        field.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    /** Every app Home could show, by label: TV apps first, then the rest, one entry per package. */
    private static List<String[]> installed(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) add(activity, pm, apps, seen, ri);
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) add(activity, pm, apps, seen, ri);
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return apps;
    }

    private static void add(Activity activity, PackageManager pm, List<String[]> apps, Set<String> seen, ResolveInfo ri) {
        if (ri.activityInfo == null || !ri.activityInfo.exported) return;
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(activity.getPackageName()) || !seen.add(pkg)) return;
        Config cfg = Config.load(activity);
        String label = cfg.appLabels.containsKey(pkg) ? cfg.appLabels.get(pkg)
                : Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
        apps.add(new String[]{label, pkg});
    }

    private static void toast(Activity activity, int text) {
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
