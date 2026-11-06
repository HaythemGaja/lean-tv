package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the arrow pattern: what counts as one, when it submits itself, and the stored hash. */
public class PinLockTest {

    @Test
    public void onlyFourToEightArrowStepsCount() {
        assertFalse(PinLock.validPattern(null));
        assertFalse(PinLock.validPattern(""));
        assertFalse(PinLock.validPattern("UDL"));
        assertTrue(PinLock.validPattern("UDLR"));
        assertTrue(PinLock.validPattern("UUUUUUUU"));
        assertFalse(PinLock.validPattern("UUUUUUUUU"));
        assertFalse(PinLock.validPattern("UDLX"));
        assertFalse(PinLock.validPattern("udlr"));
        assertFalse(PinLock.validPattern("U D L R"));
        assertFalse(PinLock.validPattern("1234"));
    }

    @Test
    public void unlockingSubmitsAtTheStoredLengthWithoutOk() {
        assertTrue(PinLock.submitsAt(false, 5, 5));
        assertTrue(PinLock.submitsAt(false, 4, 4));
        assertFalse(PinLock.submitsAt(false, 4, 6));
        assertFalse(PinLock.submitsAt(false, 6, 5));
        assertFalse(PinLock.submitsAt(false, 0, 0));
        assertFalse(PinLock.submitsAt(true, 5, 5));   // choosing a new pattern still ends on OK
    }

    @Test
    public void anOkPressChecksAnyFourToEightStepEntryNotOnlyTheStoredLength() {
        // A pattern longer or shorter than a stale stored length must still be checkable on OK:
        // auto-submit at the stored length fails first, and OK is the way out of that trap.
        assertFalse(PinLock.checksOnOk(0));
        assertFalse(PinLock.checksOnOk(3));
        assertTrue(PinLock.checksOnOk(4));
        assertTrue(PinLock.checksOnOk(5));
        assertTrue(PinLock.checksOnOk(6));
        assertTrue(PinLock.checksOnOk(8));
        assertFalse(PinLock.checksOnOk(9));
    }

    @Test
    public void theStoredHashVerifiesThePatternAndNothingElse() {
        String salt = PinLock.newSalt();
        String hash = PinLock.hash("UUDL", salt);
        assertTrue(PinLock.verify("UUDL", salt, hash));
        assertFalse(PinLock.verify("LDDU", salt, hash));
        assertFalse(PinLock.verify("UUD", salt, hash));
        assertFalse(PinLock.verify("", salt, hash));
        assertFalse(PinLock.verify("UUDL", salt, ""));
        assertFalse(PinLock.verify("UUDL", null, hash));
    }

    @Test
    public void thePatternItselfIsNotInTheHash() {
        String salt = PinLock.newSalt();
        String hash = PinLock.hash("UUDD", salt);
        assertFalse(hash.contains("UUDD"));
        assertEquals(64, hash.length());   // SHA-256 as hex
    }

    @Test
    public void theSamePatternWithAnotherSaltHashesDifferently() {
        assertNotEquals(PinLock.hash("UUDL", PinLock.newSalt()), PinLock.hash("UUDL", PinLock.newSalt()));
        assertEquals(PinLock.hash("UUDL", "abc"), PinLock.hash("UUDL", "abc"));
    }

    @Test
    public void graceCoversMinutesAfterAnEntryAndNoLonger() {
        long now = 1_000_000_000_000L;
        assertFalse(PinLock.withinGrace(0, 10, now));                 // never entered
        assertFalse(PinLock.withinGrace(now, 0, now));                 // grace off
        assertTrue(PinLock.withinGrace(now - 4 * 60_000L, 5, now));    // 4 minutes into 5
        assertFalse(PinLock.withinGrace(now - 5 * 60_000L, 5, now));   // exactly at the end
        assertFalse(PinLock.withinGrace(now - 6 * 60_000L, 5, now));   // past it
        assertTrue(PinLock.withinGrace(now - 59 * 60_000L, 60, now));  // an hour covers 59 minutes
    }

    @Test
    public void freeHoursCoverTheDayStretchOvernightAndTheWholeDay() {
        assertTrue(PinLock.hourInWindow(8, 15, 8));    // school hours, from the start
        assertTrue(PinLock.hourInWindow(8, 15, 14));   // and up to the last hour
        assertFalse(PinLock.hourInWindow(8, 15, 15));  // the window ends at 15:00
        assertFalse(PinLock.hourInWindow(8, 15, 7));
        assertTrue(PinLock.hourInWindow(22, 7, 22));   // sleeping, overnight from 22:00
        assertTrue(PinLock.hourInWindow(22, 7, 2));    // and past midnight
        assertTrue(PinLock.hourInWindow(22, 7, 6));
        assertFalse(PinLock.hourInWindow(22, 7, 7));   // until 07:00
        assertFalse(PinLock.hourInWindow(22, 7, 12));
        assertTrue(PinLock.hourInWindow(8, 8, 12));    // start and end the same is the whole day
    }

    @Test
    public void freeHoursRunOnlyOnThePickedDays() {
        int weekdays = 0x3E;   // Monday to Friday, bits 1 to 5
        assertTrue(PinLock.inFreeWindow(8, 15, weekdays, java.util.Calendar.MONDAY, 10));
        assertTrue(PinLock.inFreeWindow(8, 15, weekdays, java.util.Calendar.FRIDAY, 10));
        assertFalse(PinLock.inFreeWindow(8, 15, weekdays, java.util.Calendar.SATURDAY, 10));
        assertFalse(PinLock.inFreeWindow(8, 15, weekdays, java.util.Calendar.SUNDAY, 10));
        assertFalse(PinLock.inFreeWindow(8, 15, weekdays, java.util.Calendar.MONDAY, 16));
        assertFalse(PinLock.inFreeWindow(-1, 15, weekdays, java.util.Calendar.MONDAY, 10)); // no window set
        assertFalse(PinLock.inFreeWindow(8, 15, 0, java.util.Calendar.MONDAY, 10));         // no days picked
    }

    @Test
    public void overnightHoursBelongToTheNightBefore() {
        int weekdays = 0x3E;   // Monday to Friday
        // Friday night runs into Saturday morning: Saturday 02:00 is free.
        assertTrue(PinLock.inFreeWindow(22, 7, weekdays, java.util.Calendar.SATURDAY, 2));
        // Monday 02:00 is Sunday night's tail, and Sunday was never picked.
        assertFalse(PinLock.inFreeWindow(22, 7, weekdays, java.util.Calendar.MONDAY, 2));
        // The evening hours belong to their own day.
        assertTrue(PinLock.inFreeWindow(22, 7, weekdays, java.util.Calendar.FRIDAY, 23));
        assertFalse(PinLock.inFreeWindow(22, 7, weekdays, java.util.Calendar.SUNDAY, 23));
    }
}
