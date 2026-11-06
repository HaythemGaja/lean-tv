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
}
