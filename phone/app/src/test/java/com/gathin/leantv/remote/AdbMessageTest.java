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
