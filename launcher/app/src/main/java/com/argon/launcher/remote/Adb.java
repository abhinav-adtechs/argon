package com.argon.launcher.remote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// A minimal adb client. The remote talks to the TV's own adbd over 127.0.0.1:5555 (network debugging
// is on), so its commands run as the shell user, exactly like `adb shell` from a computer. One
// connection carries every stream; the TV asks once to allow this app's key.
final class Adb implements Closeable {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000001, MAX_DATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    // DigestInfo for SHA-1: adbd's token is signed as if it were a SHA-1 digest.
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};
    static final byte[] EOF = new byte[0];

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1);
    private final CountDownLatch dead = new CountDownLatch(1);
    private int maxData;

    private Adb(Socket socket) throws IOException {
        this.socket = socket;
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    // Blocks until adbd accepts the key; the first time, that waits for someone to press Allow on the TV.
    static Adb connect(String host, int port, KeyPair key, String name, int approveMs) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 3000);
        Adb a = new Adb(s);
        try {
            a.handshake(key, name, approveMs);
        } catch (IOException | RuntimeException e) {
            a.close();
            throw e;
        }
        s.setSoTimeout(0);
        Thread t = new Thread(a::readLoop, "adb-read");
        t.setDaemon(true);
        t.start();
        return a;
    }

    private void handshake(KeyPair key, String name, int approveMs) throws IOException {
        socket.setSoTimeout(5000);
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Packet p = read();
            if (p.cmd == CNXN) {
                maxData = Math.min(p.arg1, MAX_DATA);
                return;
            }
            if (p.cmd != AUTH || p.arg0 != AUTH_TOKEN) continue;
            if (!signed) {
                send(AUTH, AUTH_SIGNATURE, 0, sign(key, p.data));
                signed = true;
            } else {
                byte[] pub = (publicKey((RSAPublicKey) key.getPublic()) + " " + name + "\0")
                        .getBytes(StandardCharsets.UTF_8);
                send(AUTH, AUTH_RSAPUBLICKEY, 0, pub);
                socket.setSoTimeout(approveMs);
            }
        }
    }

    boolean closed() {
        return dead.getCount() == 0;
    }

    void awaitClosed() throws InterruptedException {
        dead.await();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        dead.countDown();
        for (Stream s : streams.values()) s.end();
        streams.clear();
    }

    // Opens a service such as "exec:sh"; null if adbd refuses it or doesn't answer.
    Stream open(String service, int timeoutMs) throws IOException, InterruptedException {
        Stream s = new Stream(ids.getAndIncrement());
        streams.put(s.local, s);
        send(OPEN, s.local, 0, (service + "\0").getBytes(StandardCharsets.UTF_8));
        if (!s.opened.await(timeoutMs, TimeUnit.MILLISECONDS) || s.remote == 0) {
            s.close();
            return null;
        }
        return s;
    }

    private void readLoop() {
        try {
            while (true) {
                Packet p = read();
                Stream s = streams.get(p.arg1);
                if (s == null) continue;
                if (p.cmd == OKAY) {
                    s.remote = p.arg0;
                    s.opened.countDown();
                    s.writable.release();
                } else if (p.cmd == WRTE) {
                    s.incoming.add(p.data);
                } else if (p.cmd == CLSE) {
                    streams.remove(s.local);
                    s.end();
                }
            }
        } catch (IOException e) {
            close();
        }
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private Packet read() throws IOException {
        ByteBuffer h = ByteBuffer.wrap(readFully(24)).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = h.getInt();
        p.arg0 = h.getInt();
        p.arg1 = h.getInt();
        p.data = readFully(h.getInt());
        return p;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        for (int off = 0; off < n; ) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    // One service's two directions. Each write waits for adbd's OKAY of the one before; each chunk read
    // is acknowledged only when it's taken, so a slow reader slows the TV side down instead of piling up.
    final class Stream implements Closeable {
        final int local;
        volatile int remote;
        final CountDownLatch opened = new CountDownLatch(1);
        final Semaphore writable = new Semaphore(0);
        final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        private volatile boolean ended;

        Stream(int local) {
            this.local = local;
        }

        void write(byte[] b, int off, int len) throws IOException {
            try {
                while (len > 0) {
                    int n = Math.min(len, maxData);
                    writable.acquire();
                    if (ended) throw new IOException("stream closed");
                    byte[] chunk = new byte[n];
                    System.arraycopy(b, off, chunk, 0, n);
                    send(WRTE, local, remote, chunk);
                    off += n;
                    len -= n;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }

        void write(String s) throws IOException {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            write(b, 0, b.length);
        }

        // The next chunk the TV sent, EOF once the stream has ended, or null after timeoutMs.
        byte[] poll(long timeoutMs) throws InterruptedException {
            byte[] b = incoming.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (b == null) return null;
            if (b == EOF) {
                incoming.add(EOF);
                return EOF;
            }
            try {
                send(OKAY, local, remote, new byte[0]);
            } catch (IOException ignored) {
            }
            return b;
        }

        // Everything until the TV closes the stream.
        byte[] readAll() throws InterruptedException {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (byte[] b; (b = poll(Long.MAX_VALUE)) != EOF; ) all.write(b, 0, b.length);
            return all.toByteArray();
        }

        boolean ended() {
            return ended;
        }

        private void end() {
            if (ended) return;
            ended = true;
            incoming.add(EOF);
            writable.release();
            opened.countDown();
        }

        @Override
        public void close() {
            boolean was = ended;
            streams.remove(local);
            end();
            if (!was && remote != 0) {
                try {
                    send(CLSE, local, remote, new byte[0]);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /* ---------- Keys ---------- */

    static KeyPair newKey() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    // A PKCS#8 private key (what newKey() saves, and what ~/.android/adbkey holds) with its public half.
    static KeyPair key(byte[] pkcs8) throws GeneralSecurityException {
        KeyFactory f = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey priv = (RSAPrivateCrtKey) f.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        return new KeyPair(f.generatePublic(new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())), priv);
    }

    private static byte[] sign(KeyPair key, byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(key.getPrivate());
            s.update(SHA1_PREFIX);
            s.update(token);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // adbd's own public key format: little-endian words of the modulus with Montgomery constants.
    private static String publicKey(RSAPublicKey k) {
        int words = 64;
        BigInteger n = k.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32).pow(2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(k.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }
}
