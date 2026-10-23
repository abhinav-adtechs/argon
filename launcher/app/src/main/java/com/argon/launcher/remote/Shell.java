package com.argon.launcher.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// A long-lived shell on the TV that runs one command at a time and returns its output. adbd gives
// exec: a raw pty, where a bare `sh` turns interactive and echoes its prompt, so it's a read/eval loop.
final class Shell {
    private static final String SERVICE = "exec:while IFS= read -r l; do eval \"$l\"; done";

    private final Link link;
    private Adb.Stream stream;
    private int n;

    Shell(Link link) {
        this.link = link;
    }

    // Single-quotes a string for the TV's shell.
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    String run(String cmd) {
        return run(cmd, 5000);
    }

    // "" when the TV can't be reached or the command doesn't finish in time; the shell is then
    // replaced so a late answer can't end up in the next command's output.
    synchronized String run(String cmd, int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        try {
            if (stream == null || stream.ended()) stream = link.open(SERVICE, timeoutMs);
            if (stream == null) return "";
            String marker = "__argon_" + (++n) + "__";
            stream.write(cmd + " 2>&1; echo " + marker + "\n");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (true) {
                long left = end - System.currentTimeMillis();
                byte[] b = left > 0 ? stream.poll(left) : null;
                if (b == null || b == Adb.EOF) break;
                buf.write(b, 0, b.length);
                String s = buf.toString(StandardCharsets.ISO_8859_1.name());
                int i = s.indexOf(marker);
                if (i >= 0) return new String(buf.toByteArray(), 0, i, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // Falls through to replacing the shell.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (stream != null) stream.close();
        stream = null;
        return "";
    }
}
