package com.gathin.leantv.remote;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The health line reads three commands whose output differs by firmware; each part stands on its own. */
public class HealthTest {

    @Test
    public void uptimeBecomesDaysHoursAndMinutes() {
        assertEquals("1d 1h", Health.duration("90000.00 1.00"));
        assertEquals("3h 20m", Health.duration("12000.00 1.00"));
        assertEquals("5m", Health.duration("300.00 1.00"));
    }

    @Test
    public void nothingRecognisableIsAQuestionMarkNotAWrongNumber() {
        assertEquals("?", Health.duration(""));
        assertEquals("?", Health.duration(null));
        assertEquals("?", Health.memory("MemTotal: 1966080 kB"));
        assertEquals("?", Health.storage("garbage\n"));
        assertTrue(Health.describe(null, null, null).contains("?"));
    }

    @Test
    public void freeMemoryComesFromMemAvailable() {
        String meminfo = "MemTotal:  1966080 kB\nMemFree:  200000 kB\nMemAvailable:  734003 kB\n";
        assertEquals("716 MB", Health.memory(meminfo));
    }

    @Test
    public void storageReadsTheDfLineInBlocks() {
        String disk = "Filesystem 1K-blocks Used Available Use% Mounted on\n"
                + "/dev/block/dm-1 8388608 4194304 4194304 50% /data\n";
        assertEquals("4.0 GB free of 8.0 GB", Health.storage(disk));
    }
}
