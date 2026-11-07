package com.gathin.leantv.remote;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * One Adb transport message: a 24-byte little-endian header and its payload, which is how the TV's debug bridge
 * speaks. Only the commands a shell call needs are named here.
 *
 * <p>The header repeats its command inverted as the last word and carries a plain sum of the payload. Both are
 * checked on the way in, so a half-read or misaligned message fails instead of sending the wrong key.
 */
final class AdbMessage {
    static final int CNXN = cmd("CNXN");
    static final int AUTH = cmd("AUTH");
    static final int OPEN = cmd("OPEN");
    /** STLS: the TV wants TLS before anything else, which this remote does not speak (see AdbClient). */
    static final int STLS = cmd("STLS");
    static final int OKAY = cmd("OKAY");
    static final int WRTE = cmd("WRTE");
    static final int CLSE = cmd("CLSE");

    /** AUTH's arg0: the TV asks for a signature, then the public key, then refuses. */
    static final int AUTH_TOKEN = 1;
    static final int AUTH_SIGNATURE = 2;
    static final int AUTH_PUBLIC_KEY = 3;

    // The original protocol version, not the current one: 0x01000001 is AOSP's A_VERSION_SKIP_CHECKSUM,
    // and checksums are skipped when both sides are at least that version, so a modern TV that hears it
    // stops sending checksums while read() below still verifies them, and refuses its very first reply
    // with "Adb payload checksum is wrong" -- the failure this remote showed on a real TV. dadb speaks
    // 0x01000000 and the TV then computes and verifies checksums both ways, which is what this code does.
    static final int VERSION = 0x01000000;
    static final int MAX_PAYLOAD = 256 * 1024;
    private static final int HEADER = 24;

    final int command;
    final int arg0;
    final int arg1;
    final byte[] data;

    AdbMessage(int command, int arg0, int arg1, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.data = data == null ? new byte[0] : data;
    }

    AdbMessage(int command, int arg0, int arg1, String text) {
        this(command, arg0, arg1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** "CNXN" as the little-endian int Adb puts on the wire. */
    static int cmd(String name) {
        return (name.charAt(0) & 0xFF)
                | ((name.charAt(1) & 0xFF) << 8)
                | ((name.charAt(2) & 0xFF) << 16)
                | ((name.charAt(3) & 0xFF) << 24);
    }

    static String commandName(int command) {
        byte[] letters = {(byte) command, (byte) (command >> 8), (byte) (command >> 16), (byte) (command >> 24)};
        return new String(letters, StandardCharsets.US_ASCII);
    }

    static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xFF;
        return sum;
    }

    static byte[] header(int command, int arg0, int arg1, byte[] data) {
        byte[] header = new byte[HEADER];
        putInt(header, 0, command);
        putInt(header, 4, arg0);
        putInt(header, 8, arg1);
        putInt(header, 12, data.length);
        putInt(header, 16, checksum(data));
        putInt(header, 20, command ^ 0xFFFFFFFF);
        return header;
    }

    void write(OutputStream out) throws IOException {
        out.write(header(command, arg0, arg1, data));
        out.write(data);
        out.flush();
    }

    /** The next message, or null when the TV closed the connection between messages. */
    static AdbMessage read(InputStream in) throws IOException {
        byte[] header = readFully(in, HEADER);
        if (header == null) return null;
        int command = getInt(header, 0);
        int arg0 = getInt(header, 4);
        int arg1 = getInt(header, 8);
        int length = getInt(header, 12);
        int sum = getInt(header, 16);
        int magic = getInt(header, 20);
        if (magic != (command ^ 0xFFFFFFFF)) throw new IOException("Adb header magic is wrong");
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("Adb payload of " + length + " bytes is too big");
        byte[] data = readFully(in, length);
        if (data == null) throw new EOFException("Adb message was cut short");
        if (checksum(data) != sum) throw new IOException("Adb payload checksum is wrong");
        return new AdbMessage(command, arg0, arg1, data);
    }

    private static byte[] readFully(InputStream in, int size) throws IOException {
        if (size == 0) return new byte[0];
        byte[] buffer = new byte[size];
        int read = 0;
        while (read < size) {
            int n = in.read(buffer, read, size - read);
            if (n < 0) {
                if (read == 0) return null;
                throw new EOFException("Adb message was cut short");
            }
            read += n;
        }
        return buffer;
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >> 8);
        out[at + 2] = (byte) (value >> 16);
        out[at + 3] = (byte) (value >> 24);
    }

    static int getInt(byte[] in, int at) {
        return (in[at] & 0xFF)
                | ((in[at + 1] & 0xFF) << 8)
                | ((in[at + 2] & 0xFF) << 16)
                | ((in[at + 3] & 0xFF) << 24);
    }
}
