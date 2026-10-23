package com.argon.launcher.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Consumer;

// Answers "argon.local" with the TV's address over multicast DNS, so phones can bookmark
// http://argon.local:7777 and keep it when the router hands the TV a new address.
final class Mdns {
    static final String NAME = "argon.local";
    private static final int PORT = 5353, TTL = 120;
    private static final InetAddress GROUP = group();

    private final MulticastSocket socket;

    Mdns(Consumer<String> log) throws IOException {
        socket = new MulticastSocket(PORT);
        socket.joinGroup(new InetSocketAddress(GROUP, PORT), null);
        Thread t = new Thread(() -> {
            try {
                announce();
                byte[] buf = new byte[9000];
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    answer(p);
                }
            } catch (IOException e) {
                log.accept("mdns: " + e);
            }
        }, "mdns");
        t.setDaemon(true);
        t.start();
    }

    private static InetAddress group() {
        try {
            return InetAddress.getByName("224.0.0.251");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // The TV's LAN address, looked up each time in case it changed.
    static Inet4Address address() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return (Inet4Address) a;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void announce() throws IOException {
        byte[] r = response(0, null);
        if (r != null) socket.send(new DatagramPacket(r, r.length, GROUP, PORT));
    }

    private void answer(DatagramPacket p) throws IOException {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12 || (d[2] & 0x80) != 0) return;
        int questions = ((d[4] & 0xff) << 8) | (d[5] & 0xff);
        int pos = 12;
        for (int q = 0; q < questions; q++) {
            StringBuilder name = new StringBuilder();
            int end = readName(d, len, pos, name);
            if (end < 0 || end + 4 > len) return;
            int type = ((d[end] & 0xff) << 8) | (d[end + 1] & 0xff);
            // AAAA too: without an answer saying there's no IPv6 address, phones wait ~5 s before using A.
            if (name.toString().toLowerCase(Locale.ROOT).equals(NAME) && (type == 1 || type == 28 || type == 255)) {
                // A query from port 5353 gets a multicast answer; anything else is a one-off lookup
                // that expects a unicast reply echoing its id and question.
                boolean legacy = p.getPort() != PORT;
                int id = ((d[0] & 0xff) << 8) | (d[1] & 0xff);
                byte[] r = response(legacy ? id : 0, legacy ? java.util.Arrays.copyOfRange(d, pos, end + 4) : null);
                if (r == null) return;
                socket.send(legacy ? new DatagramPacket(r, r.length, p.getAddress(), p.getPort())
                        : new DatagramPacket(r, r.length, GROUP, PORT));
                return;
            }
            pos = end + 4;
        }
    }

    // Returns the offset just past the name, or -1 if it's malformed.
    private static int readName(byte[] d, int len, int pos, StringBuilder out) {
        int end = -1;
        for (int jumps = 0; pos < len && jumps < 16; ) {
            int l = d[pos] & 0xff;
            if (l == 0) return end < 0 ? pos + 1 : end;
            if ((l & 0xc0) == 0xc0) {
                if (pos + 1 >= len) return -1;
                if (end < 0) end = pos + 2;
                pos = ((l & 0x3f) << 8) | (d[pos + 1] & 0xff);
                jumps++;
                continue;
            }
            if (pos + 1 + l > len) return -1;
            if (out.length() > 0) out.append('.');
            out.append(new String(d, pos + 1, l, java.nio.charset.StandardCharsets.US_ASCII));
            pos += 1 + l;
        }
        return -1;
    }

    private static byte[] response(int id, byte[] question) {
        Inet4Address a = address();
        if (a == null) return null;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        // One answer (A) and one additional record (NSEC: A is the only type this name has).
        int[] header = {id >> 8, id, 0x84, 0, 0, question == null ? 0 : 1, 0, 1, 0, 0, 0, 1};
        for (int h : header) b.write(h);
        byte[] name = encode(NAME);
        if (question != null) {
            b.write(name, 0, name.length);
            b.write(question, question.length - 4, 4);
        }
        // Class IN with the cache-flush bit, which one-off (unicast) answers must not set.
        int cls = question == null ? 0x8001 : 0x0001;
        record(b, name, 1, cls, a.getAddress());
        ByteArrayOutputStream nsec = new ByteArrayOutputStream();
        nsec.write(name, 0, name.length);
        nsec.write(0);
        nsec.write(1);
        nsec.write(0x40);
        record(b, name, 47, cls, nsec.toByteArray());
        return b.toByteArray();
    }

    private static void record(ByteArrayOutputStream b, byte[] name, int type, int cls, byte[] data) {
        b.write(name, 0, name.length);
        int[] rr = {type >> 8, type & 0xff, cls >> 8, cls & 0xff, 0, 0, TTL >> 8, TTL & 0xff, data.length >> 8,
                data.length & 0xff};
        for (int x : rr) b.write(x);
        b.write(data, 0, data.length);
    }

    private static byte[] encode(String name) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            b.write(label.length());
            b.write(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, label.length());
        }
        b.write(0);
        return b.toByteArray();
    }
}
