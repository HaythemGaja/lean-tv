package com.gathin.leantv.remote;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The cleaning rules: what may be switched off, what never may, and the restore point that brings it back. */
public class CleanTest {

    @Test
    public void packagesComeFromPmAndNothingElse() {
        String listing = "package:com.tcl.ads\nWARNING: something\npackage:com.sony.bloat\n\npackage:not a package\n";
        assertEquals(Arrays.asList("com.sony.bloat", "com.tcl.ads"), Clean.packages(listing));
        assertTrue(Clean.packages(null).isEmpty());
    }

    @Test
    public void androidItselfAndThePartsTheTvNeedsAreNeverOffered() {
        assertTrue(Clean.protectedPackage("android"));
        assertTrue(Clean.protectedPackage("android.ext.services"));
        assertTrue(Clean.protectedPackage("com.android.systemui"));
        assertTrue(Clean.protectedPackage("com.android.settings"));
        assertTrue(Clean.protectedPackage("com.android.tv.settings"));
        assertTrue(Clean.protectedPackage("com.android.shell"));
        assertTrue(Clean.protectedPackage("com.android.phone"));
        assertTrue(Clean.protectedPackage("com.android.permissioncontroller"));
        assertTrue(Clean.protectedPackage("com.google.android.packageinstaller"));
        assertTrue(Clean.protectedPackage("com.google.android.gms"));
        assertTrue(Clean.protectedPackage("com.android.vending"));
        assertTrue(Clean.protectedPackage("com.gathin.tvhome"));
        assertTrue(Clean.protectedPackage("com.gathin.leantv.remote"));
        assertTrue(Clean.protectedPackage("com.tcl.tvlauncher"));
        assertTrue(Clean.protectedPackage("com.vendor.setupwizard"));
        assertTrue(Clean.protectedPackage("com.sony.dtv.tvinput"));
        assertTrue(Clean.protectedPackage("com.example.livetv"));
        assertTrue(Clean.protectedPackage("com.example.leanbackkeyboard"));
        assertTrue(Clean.protectedPackage(""));
        assertTrue(Clean.protectedPackage(null));
        // Vendor bloat is what is left to switch off.
        assertFalse(Clean.protectedPackage("com.tcl.ads"));
        assertFalse(Clean.protectedPackage("com.sony.bloat"));
    }

    @Test
    public void offeredLeavesOutWhatIsAlreadyOffAndWhatIsProtected() {
        List<String> installed = Arrays.asList("com.tcl.ads", "com.android.systemui", "com.sony.bloat");
        List<String> off = Collections.singletonList("com.sony.bloat");
        assertEquals(Collections.singletonList("com.tcl.ads"), Clean.offered(installed, off));
        assertEquals(installed.size() - 1, Clean.offered(installed, Collections.emptyList()).size());
        assertTrue(Clean.offered(null, null).isEmpty());
    }

    @Test
    public void thePlanNamesWhatItWillSwitchOff() {
        assertEquals("", Clean.plan(Collections.emptyList()));
        assertEquals("", Clean.plan(null));
        assertEquals("com.tcl.ads", Clean.plan(Collections.singletonList("com.tcl.ads")));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) many.add("com.tcl.app" + i);
        assertTrue(Clean.plan(many).endsWith("and 3 more"));
    }

    @Test
    public void theRestorePointSurvivesARoundTripAndDropsWhatItCannotRead() {
        List<String> packages = Arrays.asList("com.tcl.ads", "com.sony.bloat");
        assertEquals(packages, Clean.decode(Clean.encode(packages)));
        assertTrue(Clean.decode(null).isEmpty());
        assertEquals(Collections.singletonList("com.tcl.ads"),
                Clean.decode("com.tcl.ads\nnot a package\ncom.tcl.ads\n"));
    }
}
