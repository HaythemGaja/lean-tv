package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The owner's folders: apps grouped on Home, and folders inside folders, kept in the launcher's private files
 * (folders.txt). Off by default (config.xml {@code folders}, or a theme's {@code "folders": true}); the tiles are
 * drawn by {@link AppAdapter} and opened by {@link FolderActivity}.
 *
 * <p>Format, UTF-8 text: {@code tvhome-folders 1}, then each folder as
 * {@code folder <id> <parent|-> <locked 0|1> <name>} immediately followed by one
 * {@code app <folder id> <package>} line per app it holds. Folders are written parent first, so the file reads top
 * to bottom as a tree, and the line order is the display order: subfolders in the order their folder lines appear,
 * apps in theirs. A duplicate id, an unknown parent, a package in two folders, too deep a tree or anything else
 * unrecognised makes the whole file invalid; it is then ignored rather than guessed at, as {@link Order} does.
 */
final class Folders {
    private static final String TAG = "TvHome.Folders";
    static final String FILE_NAME = "folders.txt";
    private static final String MAGIC = "tvhome-folders 1";
    private static final String FOLDER = "folder ";
    private static final String APP = "app ";
    /** Folder names are cut to this many characters. */
    static final int MAX_NAME = 40;
    static final int MAX_FOLDERS = 32;
    static final int MAX_APPS = 256;
    /** A folder on Home is depth 1: four levels of folders is as deep as this goes. */
    static final int MAX_DEPTH = 4;

    static final class Folder {
        final String id;
        String name;
        /** The folder this one sits in, or null for one on Home. */
        final String parent;
        boolean locked;

        Folder(String id, String name, String parent, boolean locked) {
            this.id = id;
            this.name = name;
            this.parent = parent;
            this.locked = locked;
        }
    }

    /** One app in a folder, in display order: {folder id, package}. */
    private static final class Member {
        final String folder;
        final String pkg;

        Member(String folder, String pkg) {
            this.folder = folder;
            this.pkg = pkg;
        }
    }

    private final List<Folder> folders = new ArrayList<>();
    private final List<Member> members = new ArrayList<>();
    /** False when the saved file could not be read: saving is then refused, so choices are never wiped. */
    private final boolean readable;

    private Folders(boolean readable) {
        this.readable = readable;
    }

    static Folders empty() {
        return new Folders(true);
    }

    boolean readable() {
        return readable;
    }

    // ---- reading and writing ---------------------------------------------------------------------

    /** The saved tree; empty when there is none, unreadable when the file exists but does not decode. */
    static Folders load(Context ctx) {
        File file = new File(ctx.getFilesDir(), FILE_NAME);
        if (!file.exists()) return empty();
        try {
            Folders decoded = decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            if (decoded != null) return decoded;
            Log.w(TAG, FILE_NAME + " is not valid; ignored");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "cannot read " + FILE_NAME + ": " + e);
        }
        return new Folders(false);
    }

    /** Writes the tree atomically. False (and a log line) when it could not be written. */
    boolean save(Context ctx) {
        if (!readable) {
            Log.w(TAG, "keeping unreadable " + FILE_NAME + " unchanged");
            return false;
        }
        AtomicFile file = new AtomicFile(new File(ctx.getFilesDir(), FILE_NAME));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(encode().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            return true;
        } catch (IOException | RuntimeException e) {
            if (out != null) file.failWrite(out);
            Log.w(TAG, "cannot write " + FILE_NAME + ": " + e);
            return false;
        }
    }

    String encode() {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n');
        encode(null, sb);
        return sb.toString();
    }

    /** Folder lines parent first, each followed by its apps, then its subfolders. */
    private void encode(String parent, StringBuilder sb) {
        for (Folder f : folders) {
            if (!eq(f.parent, parent)) continue;
            sb.append(FOLDER).append(f.id).append(' ').append(f.parent == null ? "-" : f.parent)
                    .append(' ').append(f.locked ? '1' : '0').append(' ').append(f.name).append('\n');
            for (Member m : members) {
                if (m.folder.equals(f.id)) sb.append(APP).append(f.id).append(' ').append(m.pkg).append('\n');
            }
            encode(f.id, sb);
        }
    }

    /** The tree in this text, or null when it is not one. */
    static Folders decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !lines[0].equals(MAGIC)) return null;
        Folders out = empty();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                if (i == lines.length - 1) break;   // the trailing newline
                return null;
            }
            if (line.startsWith(FOLDER)) {
                if (!out.decodeFolder(line.substring(FOLDER.length()))) return null;
            } else if (line.startsWith(APP)) {
                if (!out.decodeApp(line.substring(APP.length()))) return null;
            } else {
                return null;
            }
        }
        return out;
    }

    /** {@code <id> <parent|-> <0|1> <name>}: the parent must already be in this tree. */
    private boolean decodeFolder(String rest) {
        String[] parts = rest.split(" ", 4);
        if (parts.length != 4) return false;
        String id = parts[0], parent = "-".equals(parts[1]) ? null : parts[1], lock = parts[2], name = parts[3];
        if (!validId(id) || folder(id) != null) return false;
        if (folders.size() >= MAX_FOLDERS) return false;
        if (parent != null && folder(parent) == null) return false;
        if (!"0".equals(lock) && !"1".equals(lock)) return false;
        if (!validName(name)) return false;
        if (depthOf(parent) + 1 > MAX_DEPTH) return false;
        folders.add(new Folder(id, name, parent, "1".equals(lock)));
        return true;
    }

    /** {@code <folder id> <package>}: the folder must already be in this tree, the package in no other. */
    private boolean decodeApp(String rest) {
        int space = rest.indexOf(' ');
        if (space <= 0) return false;
        String folder = rest.substring(0, space), pkg = rest.substring(space + 1);
        if (folder(folder) == null || folderOf(pkg) != null) return false;
        if (members.size() >= MAX_APPS || !LibraryApps.validPackage(pkg)) return false;
        members.add(new Member(folder, pkg));
        return true;
    }

    /** A 1-3 character id: "f1" or "f27", as {@link #add} hands them out. */
    static boolean validId(String id) {
        if (id == null || id.length() < 2 || id.length() > 3 || id.charAt(0) != 'f') return false;
        for (int i = 1; i < id.length(); i++) if (id.charAt(i) < '0' || id.charAt(i) > '9') return false;
        return true;
    }

    /** A name as the owner may type it: trimmed, not blank, at most {@link #MAX_NAME}, no line breaks. */
    static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= MAX_NAME && noControlChars(name);
    }

    /** Trims a typed name and cuts it to {@link #MAX_NAME}; empty when nothing usable is left. */
    static String cleanName(String typed) {
        if (typed == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < typed.length() && sb.length() < MAX_NAME; i++) {
            char c = typed.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;   // a line break would break the file format
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static boolean noControlChars(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    // ---- the tree --------------------------------------------------------------------------------

    List<Folder> all() {
        return Collections.unmodifiableList(new ArrayList<>(folders));
    }

    Folder folder(String id) {
        if (id == null) return null;
        for (Folder f : folders) if (f.id.equals(id)) return f;
        return null;
    }

    /** The folders directly inside {@code parentId}, or on Home when it is null, in display order. */
    List<Folder> subfolders(String parentId) {
        List<Folder> out = new ArrayList<>();
        for (Folder f : folders) if (eq(f.parent, parentId)) out.add(f);
        return out;
    }

    /** The apps directly in this folder, in display order. */
    List<String> apps(String folderId) {
        List<String> out = new ArrayList<>();
        for (Member m : members) if (m.folder.equals(folderId)) out.add(m.pkg);
        return out;
    }

    /** The folder that holds this package directly, or null when Home shows it. */
    String folderOf(String pkg) {
        if (pkg == null) return null;
        for (Member m : members) if (m.pkg.equals(pkg)) return m.folder;
        return null;
    }

    /** Whether Home should leave this package out of the grid: it lives in a folder now. */
    boolean contains(String pkg) {
        return folderOf(pkg) != null;
    }

    boolean holds(String folderId, String pkg) {
        for (Member m : members) {
            if (m.folder.equals(folderId) && m.pkg.equals(pkg)) return true;
        }
        return false;
    }

    /** Whether {@code id} is {@code rootId} or sits somewhere below it (what folders may not move into). */
    boolean inSubtree(String rootId, String id) {
        for (String at = id; at != null; at = folder(at) == null ? null : folder(at).parent) {
            if (at.equals(rootId)) return true;
        }
        return false;
    }

    /** 1 for a folder on Home, 2 for one inside it; 0 for no folder. */
    int depth(String id) {
        return depthOf(id);
    }

    private int depthOf(String id) {
        int depth = 0;
        for (String at = id; at != null && depth <= MAX_DEPTH + 1; depth++) {
            Folder f = folder(at);
            if (f == null) return depth;
            at = f.parent;
        }
        return depth;
    }

    /** How many apps this folder holds, subfolders included. */
    int count(String folderId) {
        int n = 0;
        for (Member m : members) if (inSubtree(folderId, m.folder)) n++;
        return n;
    }

    /** A new folder in {@code parentId} (null for Home), or null when the tree is full or that deep is too far. */
    Folder add(String name, String parentId) {
        String clean = cleanName(name);
        if (!validName(clean) || folders.size() >= MAX_FOLDERS) return null;
        if (parentId != null && (folder(parentId) == null || depthOf(parentId) + 1 > MAX_DEPTH)) return null;
        for (int i = 1; i <= MAX_FOLDERS + 1; i++) {
            String id = "f" + i;
            if (folder(id) != null) continue;
            Folder f = new Folder(id, clean, parentId, false);
            folders.add(f);
            return f;
        }
        return null;
    }

    /** Puts an app in a folder, taking it out of whichever folder held it before. */
    boolean addApp(String folderId, String pkg) {
        if (folder(folderId) == null || !LibraryApps.validPackage(pkg)) return false;
        if (holds(folderId, pkg)) return true;
        String from = folderOf(pkg);
        if (from == null && members.size() >= MAX_APPS) return false;
        if (from != null) removeApp(from, pkg);
        members.add(new Member(folderId, pkg));
        return true;
    }

    /** Takes an app out of a folder: Home shows it again. */
    boolean removeApp(String folderId, String pkg) {
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            if (m.folder.equals(folderId) && m.pkg.equals(pkg)) {
                members.remove(i);
                return true;
            }
        }
        return false;
    }

    boolean rename(String id, String name) {
        Folder f = folder(id);
        String clean = cleanName(name);
        if (f == null || !validName(clean)) return false;
        f.name = clean;
        return true;
    }

    boolean setLocked(String id, boolean locked) {
        Folder f = folder(id);
        if (f == null) return false;
        f.locked = locked;
        return true;
    }

    /** Moves a folder into {@code newParent} (null for Home), keeping its contents. */
    boolean move(String id, String newParent) {
        Folder f = folder(id);
        if (f == null || eq(f.parent, newParent)) return false;
        if (newParent != null) {
            if (folder(newParent) == null || inSubtree(id, newParent)) return false;   // no folder inside itself
            if (depthOf(newParent) + subtreeHeight(id) > MAX_DEPTH) return false;
        }
        folders.remove(f);
        Folder moved = new Folder(f.id, f.name, newParent, f.locked);
        int at = newParent == null ? folders.size() : folders.indexOf(folder(newParent)) + 1;
        folders.add(at, moved);
        return true;
    }

    /** The deepest chain of folders under this one: 1 for a folder with no subfolders. */
    int subtreeHeight(String id) {
        int height = 1;
        for (Folder f : subfolders(id)) height = Math.max(height, 1 + subtreeHeight(f.id));
        return height;
    }

    /**
     * Deletes a folder and every folder inside it. The apps it held are not deleted: they drop out of the tree,
     * so Home shows them again.
     */
    boolean remove(String id) {
        Folder f = folder(id);
        if (f == null) return false;
        List<Folder> doomed = new ArrayList<>();
        for (Folder other : folders) if (inSubtree(id, other.id)) doomed.add(other);
        for (Folder other : doomed) folders.remove(other);
        for (int i = members.size() - 1; i >= 0; i--) {
            String at = members.get(i).folder;
            if (folder(at) == null) members.remove(i);
        }
        return true;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
