package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the saved folder tree: the file format, nesting, and what it refuses. */
public class FoldersTest {

    @Test
    public void roundTripsFoldersAppsAndNesting() {
        Folders folders = Folders.empty();
        Folders.Folder games = folders.add("Games", null);
        assertNotNull(games);
        assertTrue(folders.addApp(games.id, "com.netflix.ninja"));
        Folders.Folder arcade = folders.add("Arcade", games.id);
        assertNotNull(arcade);
        assertTrue(folders.addApp(arcade.id, "com.sega.arcade"));
        assertTrue(folders.rename(games.id, "Games and more"));

        String text = folders.encode();
        assertEquals("tvhome-folders 1\n"
                + "folder f1 - 0 Games and more\n"
                + "app f1 com.netflix.ninja\n"
                + "folder f2 f1 0 Arcade\n"
                + "app f2 com.sega.arcade\n", text);

        Folders back = Folders.decode(text);
        assertNotNull(back);
        assertEquals(text, back.encode());
        assertEquals(Collections.singletonList("com.netflix.ninja"), back.apps("f1"));
        assertEquals("f2", back.folderOf("com.sega.arcade"));   // filed in the inner folder
        assertEquals(2, back.depth("f2"));
        assertEquals(2, back.count("f1"));
        assertEquals(Collections.singletonList("f1"), Arrays.asList(back.subfolders(null).get(0).id));
    }

    @Test
    public void anAppLivesInOneFolderOnly() {
        Folders folders = Folders.empty();
        Folders.Folder a = folders.add("A", null), b = folders.add("B", null);
        assertTrue(folders.addApp(a.id, "com.foo"));
        assertTrue(folders.addApp(b.id, "com.foo"));
        assertEquals(b.id, folders.folderOf("com.foo"));
        assertFalse(folders.holds(a.id, "com.foo"));
    }

    @Test
    public void foldersNestFourDeepAndNoDeeper() {
        Folders folders = Folders.empty();
        Folders.Folder parent = null;
        for (int level = 1; level <= Folders.MAX_DEPTH; level++) {
            parent = folders.add("Level " + level, parent == null ? null : parent.id);
            assertNotNull("level " + level, parent);
            assertEquals(level, folders.depth(parent.id));
        }
        assertNull(folders.add("Too deep", parent.id));
    }

    @Test
    public void aFolderCannotMoveIntoItself() {
        Folders folders = Folders.empty();
        Folders.Folder outer = folders.add("Outer", null);
        Folders.Folder inner = folders.add("Inner", outer.id);
        assertFalse(folders.move(outer.id, outer.id));
        assertFalse(folders.move(outer.id, inner.id));
        assertTrue(folders.move(inner.id, null));
        assertNull(folders.folder(inner.id).parent);
    }

    @Test
    public void deletingAFolderReturnsItsAppsToHomeAndTakesItsSubfolders() {
        Folders folders = Folders.empty();
        Folders.Folder outer = folders.add("Outer", null);
        Folders.Folder inner = folders.add("Inner", outer.id);
        assertTrue(folders.addApp(outer.id, "com.a"));
        assertTrue(folders.addApp(inner.id, "com.b"));
        assertTrue(folders.remove(outer.id));
        assertNull(folders.folder(outer.id));
        assertNull(folders.folder(inner.id));
        assertNull(folders.folderOf("com.a"));
        assertNull(folders.folderOf("com.b"));
    }

    @Test
    public void namesAreTrimmedAndCut() {
        assertEquals("Games", Folders.cleanName("  Games  "));
        assertEquals("", Folders.cleanName("   "));
        assertEquals("", Folders.cleanName(null));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < Folders.MAX_NAME + 20; i++) longName.append('x');
        assertEquals(Folders.MAX_NAME, Folders.cleanName(longName.toString()).length());
        assertFalse(Folders.validName("two\nlines"));
        assertNull(Folders.empty().add("  ", null));
    }

    @Test
    public void rejectsWhatIsNotAFolderFile() {
        assertNull(Folders.decode(null));
        assertNull(Folders.decode(""));
        assertNull(Folders.decode("{\"folders\":[]}"));
        assertNull(Folders.decode("tvhome-folders 2\n"));
        assertNull(Folders.decode("tvhome-folders 1\nfolder f1 - 0 A\nfolder f1 - 0 Again\n"));
        assertNull(Folders.decode("tvhome-folders 1\nfolder f2 f1 0 Orphan\n"));       // unknown parent
        assertNull(Folders.decode("tvhome-folders 1\nfolder f1 - 9 A\n"));               // not a lock flag
        assertNull(Folders.decode("tvhome-folders 1\nfolder f1 - 0 A\napp f1 not a package\n"));
        assertNull(Folders.decode("tvhome-folders 1\nfolder f1 - 0 A\napp f1 com.foo\napp f1 com.foo\n"));
        assertNull(Folders.decode("tvhome-folders 1\nwhat is this\n"));
        assertNull(Folders.decode("tvhome-folders 1\nfolder f1 - 0 A\n\nfolder f2 - 0 B\n"));
        assertNull(Folders.decode("tvhome-folders 1\nfolder f1 - 0 A\nfolder f2 f3 0 B\n"));   // parent below it
    }

    @Test
    public void theLockFlagRoundTrips() {
        Folders folders = Folders.empty();
        Folders.Folder folder = folders.add("Kids", null);
        assertTrue(folders.setLocked(folder.id, true));
        Folders back = Folders.decode(folders.encode());
        assertTrue(back.folder(folder.id).locked);
        assertTrue(folders.setLocked(folder.id, false));
        assertFalse(Folders.decode(folders.encode()).folder(folder.id).locked);
    }

    @Test
    public void anEmptyFileRoundTrips() {
        Folders back = Folders.decode(Folders.empty().encode());
        assertNotNull(back);
        assertEquals("tvhome-folders 1\n", back.encode());
        assertTrue(back.all().isEmpty());
        assertTrue(back.subfolders(null).isEmpty());
    }
}
