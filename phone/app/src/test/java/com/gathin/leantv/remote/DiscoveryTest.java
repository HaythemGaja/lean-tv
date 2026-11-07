package com.gathin.leantv.remote;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for what a found TV means: which ones connect, which ones only offer TLS. */
public class DiscoveryTest {

    @Test
    public void aTvWith5555OpenConnects() {
        Discovery.Tv tv = new Discovery.Tv("Living room", "192.168.1.50", false, true);
        assertTrue(tv.connectable());
        assertFalse(tv.tlsOnly());
        assertEquals("Living room (192.168.1.50)", tv.label());
    }

    @Test
    public void aTvWithOnlyWirelessDebuggingDoesNotConnect() {
        Discovery.Tv tv = new Discovery.Tv("onn. TV", "192.168.1.50", true, false);
        assertFalse(tv.connectable());
        assertTrue(tv.tlsOnly());
    }

    @Test
    public void aCastOnlyTvWith5555ClosedIsNeither() {
        Discovery.Tv tv = new Discovery.Tv("", "192.168.1.51", false, false);
        assertFalse(tv.connectable());
        assertFalse(tv.tlsOnly());
        assertFalse(tv.offerable());
        assertEquals("192.168.1.51", tv.label());
    }

    @Test
    public void thePickerOffersConnectableAndTlsOnlyTvs() {
        assertTrue(new Discovery.Tv("A", "192.168.1.50", false, true).offerable());
        assertTrue(new Discovery.Tv("B", "192.168.1.51", true, false).offerable());
    }

    @Test
    public void thePhoneItselfIsNeverATv() {
        HashSet<String> own = new HashSet<>(Arrays.asList("192.168.1.10", "10.0.2.15"));
        assertTrue(Discovery.isOwn(null, own));
        assertTrue(Discovery.isOwn("127.0.0.1", own));
        assertTrue(Discovery.isOwn("127.0.0.5", own));
        assertTrue(Discovery.isOwn("0.0.0.0", own));
        assertTrue(Discovery.isOwn("192.168.1.10", own));
        assertFalse(Discovery.isOwn("192.168.1.50", own));
    }
}
