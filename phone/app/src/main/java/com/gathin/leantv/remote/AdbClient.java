package com.gathin.leantv.remote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Just enough of the Adb protocol to run a TV from a phone: connect, prove the key, then drive it.
 *
 * <p>Silent commands (key events, text, starting an app) go down one shell stream kept open for the life of the
 * connection, so a press costs one round trip instead of the two an OPEN and a CLSE would add. Commands whose
 * output matters, and screenshots, still open a stream of their own and close it again.
 *
 * <p>The phone is the client and the TV is the server, so the launcher needs no listening socket and keeps its
 * no-INTERNET rule: it only ever answers its own debug bridge, and only while network debugging is on.
 */
final class AdbClient implements Closeable {
    private static final String MARKER = "__leantv_done__";
    private static final int SHELL_TIMEOUT_MS = 10000;
    private static final int EXEC_TIMEOUT_MS = 20000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final AdbKeys keys;
    private int nextLocal = 1;
    private int sessionLocal;
    private boolean sessionOpen;

    private AdbClient(Socket socket, AdbKeys keys) throws IOException {
        this.socket = socket;
        this.keys = keys;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    static AdbClient connect(String host, int port, AdbKeys keys, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);
            AdbClient client = new AdbClient(socket, keys);
            client.handshake();
            return client;
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already failing: the first error is the useful one.
            }
            throw e;
        }
    }

    /** CNXN, then whatever the TV asks for until it accepts this phone's key and answers CNXN itself. */
    private void handshake() throws IOException {
        send(new AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, "host::leantv-remote\0"));
        boolean signed = false;
        boolean sentKey = false;
        long deadline = System.currentTimeMillis() + 120000;   // the TV waits on its Allow prompt
        while (System.currentTimeMillis() < deadline) {
            AdbMessage message = AdbMessage.read(in);
            if (message == null) throw new IOException("The TV closed the connection");
            if (message.command == AdbMessage.CNXN) return;
            if (message.command != AdbMessage.AUTH || message.arg0 != AdbMessage.AUTH_TOKEN) continue;
            if (!signed) {
                signed = true;
                send(new AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_SIGNATURE, 0, sign(message.data)));
            } else if (!sentKey) {
                sentKey = true;
                send(new AdbMessage(AdbMessage.AUTH, AdbMessage.AUTH_PUBLIC_KEY, 0, keys.publicKeyPayload()));
            } else {
                throw new IOException("The TV refused this phone's key");
            }
        }
        throw new IOException("Timed out waiting for the TV to allow debugging");
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            return keys.sign(token);
        } catch (Exception e) {
            throw new IOException("Could not sign the TV's challenge", e);
        }
    }

    // ---- what the remote does ----------------------------------------------------------------

    void key(int keyCode) throws IOException {
        quiet("input keyevent " + keyCode);
    }

    /** Several presses in one command: a long press on Back sends three. */
    void keys(int... keyCodes) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int code : keyCodes) line.append("input keyevent ").append(code).append("; ");
        quiet(line.toString());
    }

    void text(String value) throws IOException {
        String escaped = escape(value);
        if (!escaped.isEmpty()) quiet("input text " + escaped);
    }

    void launch(String pkg) throws IOException {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) throw new IOException("Not a package name: " + pkg);
        quiet("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
    }

    /** The TV's third-party package names, one per line. */
    String packages() throws IOException {
        return run("pm list packages -3");
    }

    /**
     * A command whose answer matters, on a stream of its own. Callers build the whole line, so every package name
     * and every setting they put in it is checked by them first; see {@link Clean} and {@link LeanConfig}.
     */
    String shell(String command) throws IOException {
        return run(command);
    }

    /** A line about uptime, free memory and the data partition's free space. */
    String health() throws IOException {
        return Health.describe(run("cat /proc/uptime"), run("cat /proc/meminfo"), run("df /data"));
    }

    /** One PNG frame of what the TV shows, as screencap writes it. */
    synchronized byte[] screenshot() throws IOException {
        return exec("screencap -p");
    }

    /** What "input text" accepts: single-quoted, spaces as %s, nothing else a shell could read as syntax. */
    static String escape(String value) {
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == ' ') out.append("%s");
            else if (ch == '\'') out.append("\\'");
            else if (Character.isLetterOrDigit(ch) || ",.!?@#$%&()+-_/=:;*".indexOf(ch) >= 0) out.append(ch);
        }
        return out.length() <= 2 ? "" : out.append('\'').toString();
    }

    // ---- the shell streams -------------------------------------------------------------------

    /** A command whose output nobody needs, sent down the shell that stays open. */
    private synchronized void quiet(String command) throws IOException {
        openSession();
        socket.setSoTimeout(SHELL_TIMEOUT_MS);
        line(command + "; echo " + MARKER);
        drain();
    }

    /** A command whose output is the answer, on a stream of its own so nothing else can mix into it. */
    private synchronized String run(String command) throws IOException {
        int local = nextLocal++;
        send(new AdbMessage(AdbMessage.OPEN, local, 0, "shell:" + command + "\0"));
        socket.setSoTimeout(SHELL_TIMEOUT_MS);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage message = AdbMessage.read(in);
            if (message == null) break;
            if (message.command == AdbMessage.OKAY) continue;
            if (message.command == AdbMessage.WRTE) {
                bytes.write(message.data);
                send(new AdbMessage(AdbMessage.OKAY, local, message.arg1, (byte[]) null));
            } else if (message.command == AdbMessage.CLSE) {
                break;
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
    }

    /** A binary stream, like a screenshot: everything the command writes, until the stream closes. */
    private synchronized byte[] exec(String command) throws IOException {
        int local = nextLocal++;
        send(new AdbMessage(AdbMessage.OPEN, local, 0, "exec:" + command + "\0"));
        socket.setSoTimeout(EXEC_TIMEOUT_MS);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            AdbMessage message;
            try {
                message = AdbMessage.read(in);
            } catch (java.net.SocketTimeoutException e) {
                break;          // a slow frame is not worth failing the connection over
            }
            if (message == null) break;
            if (message.command == AdbMessage.OKAY) continue;
            if (message.command == AdbMessage.WRTE) {
                bytes.write(message.data);
                send(new AdbMessage(AdbMessage.OKAY, local, message.arg1, (byte[]) null));
            } else if (message.command == AdbMessage.CLSE) {
                break;
            }
        }
        return bytes.toByteArray();
    }

    /**
     * Opens the interactive shell once. Its prompt and the echo of what is typed are noise; the marker printed
     * after each command is what says the TV has finished with it.
     */
    private void openSession() throws IOException {
        if (sessionOpen) return;
        sessionLocal = nextLocal++;
        send(new AdbMessage(AdbMessage.OPEN, sessionLocal, 0, "shell:\0"));
        long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage message = AdbMessage.read(in);
            if (message == null) throw new IOException("The TV closed the connection");
            if (message.command == AdbMessage.OKAY && message.arg1 == sessionLocal) {
                sessionOpen = true;
                return;
            }
            if (message.command == AdbMessage.CLSE) throw new IOException("The TV refused a shell");
        }
        throw new IOException("Timed out opening a shell on the TV");
    }

    private void line(String text) throws IOException {
        out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads until the marker: the answer may be a prompt, an echo or nothing, and none of it matters here. */
    private void drain() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + SHELL_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            AdbMessage message = AdbMessage.read(in);
            if (message == null) {
                sessionOpen = false;
                return;
            }
            if (message.command == AdbMessage.WRTE) {
                bytes.write(message.data);
                send(new AdbMessage(AdbMessage.OKAY, sessionLocal, message.arg1, (byte[]) null));
                if (bytes.toString("UTF-8").contains(MARKER)) return;
            } else if (message.command == AdbMessage.CLSE) {
                sessionOpen = false;
                return;
            }
        }
    }

    private void send(AdbMessage message) throws IOException {
        message.write(out);
    }

    @Override
    public void close() {
        sessionOpen = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a dead socket is not an error worth showing.
        }
    }
}
