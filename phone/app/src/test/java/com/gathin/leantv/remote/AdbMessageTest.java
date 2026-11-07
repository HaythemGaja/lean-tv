package com.gathin.leantv.remote;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the Adb framing and the public key struct, which is where a remote silently breaks. */
public class AdbMessageTest {

    @Test
    public void aCommandTravelsAsTheFourLettersAdbExpects() {
        assertEquals(0x4E584E43, AdbMessage.cmd("CNXN"));
        assertEquals("CNXN", AdbMessage.commandName(AdbMessage.CNXN));
        assertEquals("WRTE", AdbMessage.commandName(AdbMessage.WRTE));
    }

    @Test
    public void theHeaderCarriesTheLengthChecksumAndInvertedMagic() {
        byte[] data = "host::\0".getBytes(StandardCharsets.UTF_8);
        byte[] header = AdbMessage.header(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, data);
        assertEquals(24, header.length);
        assertEquals(AdbMessage.CNXN, AdbMessage.getInt(header, 0));
        assertEquals(data.length, AdbMessage.getInt(header, 12));
        assertEquals(AdbMessage.checksum(data), AdbMessage.getInt(header, 16));
        assertEquals(AdbMessage.CNXN ^ 0xFFFFFFFF, AdbMessage.getInt(header, 20));
    }

    @Test
    public void aMessageSurvivesTheRoundTrip() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new AdbMessage(AdbMessage.OPEN, 3, 0, "shell:input keyevent 19\0").write(bytes);
        AdbMessage back = AdbMessage.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertNotNull(back);
        assertEquals(AdbMessage.OPEN, back.command);
        assertEquals(3, back.arg0);
        assertEquals("shell:input keyevent 19\0", new String(back.data, StandardCharsets.UTF_8));
    }

    @Test
    public void theClientSpeaksTheOriginalProtocolVersion() {
        // 0x01000001 is AOSP's A_VERSION_SKIP_CHECKSUM: a TV that hears it stops sending checksums,
        // so read() refuses its first reply with "Adb payload checksum is wrong" -- the exact failure
        // this remote showed on a real TV. 0x01000000 is what dadb speaks, and the TV then checksums
        // both ways, which is what read() and write() here already do.
        assertEquals(0x01000000, AdbMessage.VERSION);
    }

    @Test
    public void aReplyWithNoChecksumIsRefused() throws Exception {
        // What a TV sends when the client announced 0x01000001: a real payload with data_check 0.
        // The version above is what keeps this from ever happening; this test pins the failure mode.
        byte[] data = "device::ro.product.model=x\0".getBytes(StandardCharsets.UTF_8);
        byte[] header = AdbMessage.header(AdbMessage.CNXN, 0x01000001, 4096, data);
        header[16] = 0; header[17] = 0; header[18] = 0; header[19] = 0;   // data_check zeroed
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(header);
        bytes.write(data);
        try {
            AdbMessage.read(new ByteArrayInputStream(bytes.toByteArray()));
            throw new AssertionError("a reply with no checksum was accepted");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }

    @Test
    public void aChecksummedReplyFromAnOriginalPeerIsAccepted() throws Exception {
        // The mirror of the refusal above: a TV that heard 0x01000000 checksums its reply, and read()
        // accepts it. Same banner, same peer version in the header's arg0, real checksum this time.
        byte[] data = "device::ro.product.model=x\0".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new AdbMessage(AdbMessage.CNXN, 0x01000000, 4096, data).write(bytes);
        AdbMessage back = AdbMessage.read(new ByteArrayInputStream(bytes.toByteArray()));
        assertNotNull(back);
        assertEquals(AdbMessage.CNXN, back.command);
        assertEquals(0x01000000, back.arg0);
    }

    @Test
    public void aCorruptPayloadIsRefusedRatherThanSent() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new AdbMessage(AdbMessage.WRTE, 1, 1, "abc").write(bytes);
        byte[] wire = bytes.toByteArray();
        wire[24] = (byte) 'z';                              // one payload byte changed, checksum left alone
        try {
            AdbMessage.read(new ByteArrayInputStream(wire));
            throw new AssertionError("a bad checksum was accepted");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }

    @Test
    public void thePublicKeyIsAdbs524ByteStructInBase64() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String payload = AdbKeys.publicKeyPayload((RSAPublicKey) generator.generateKeyPair().getPublic());
        assertTrue(payload.endsWith("\0"));
        byte[] blob = Base64.getDecoder().decode(payload.substring(0, payload.length() - 1));
        assertEquals(4 + 4 + 256 + 256 + 4, blob.length);
        assertEquals(64, AdbMessage.getInt(blob, 0));        // modulus length in 32-bit words
        assertEquals(65537, AdbMessage.getInt(blob, 520));   // the exponent adb uses
    }

    @Test
    public void typedTextIsQuotedAndSpacesBecomePercentS() {
        assertEquals("'hello%sworld'", AdbClient.escape("hello world"));
        assertEquals("'it\\'s'", AdbClient.escape("it's"));
        assertEquals("'a;b'", AdbClient.escape("a;b"));
        assertEquals("", AdbClient.escape("\uD83D\uDE00"));  // nothing a shell could type
    }
}
