package com.argon.launcher.remote;

import java.io.IOException;
import java.security.KeyPair;
import java.util.function.Consumer;

// Keeps one adb connection to the TV. Everything that needs it waits on the same retry loop
// (1 s, doubling to 15 s), so adbd restarting costs a reconnect, not a burst of them.
final class Link {
    // How long the TV's "Allow debugging?" dialog may wait for someone to answer it. Short, because
    // anything opened after it (the home screen after an install) hides it; the retry shows a new one.
    private static final int APPROVE_MS = 60_000;

    final String address;
    private final String host;
    private final int port;
    private final KeyPair key;
    private final String name;
    private final Consumer<String> log;
    private Adb adb;
    private boolean connecting;

    Link(String host, int port, KeyPair key, String name, Consumer<String> log) {
        this.host = host;
        this.port = port;
        this.key = key;
        this.name = name;
        this.log = log;
        address = host + ":" + port;
    }

    // The live connection, or null if there's none within timeoutMs.
    synchronized Adb get(long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + Math.min(timeoutMs, 1L << 40);
        while (adb == null || adb.closed()) {
            if (!connecting) {
                connecting = true;
                Thread t = new Thread(this::connectLoop, "adb-connect");
                t.setDaemon(true);
                t.start();
            }
            long left = end - System.currentTimeMillis();
            if (left <= 0) return null;
            wait(left);
        }
        return adb;
    }

    Adb.Stream open(String service, long timeoutMs) throws InterruptedException {
        Adb a = get(timeoutMs);
        if (a == null) return null;
        try {
            return a.open(service, 5000);
        } catch (IOException e) {
            a.close();
            return null;
        }
    }

    private void connectLoop() {
        boolean warned = false;
        for (long wait = 1000; ; wait = Math.min(wait * 2, 15000)) {
            try {
                Adb a = Adb.connect(host, port, key, name, APPROVE_MS);
                if (warned) log.accept("adb " + address + " is back");
                synchronized (this) {
                    adb = a;
                    connecting = false;
                    notifyAll();
                }
                return;
            } catch (IOException e) {
                if (!warned) log.accept("adb " + address + " not answering (" + e.getMessage() + "), retrying");
                warned = true;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
