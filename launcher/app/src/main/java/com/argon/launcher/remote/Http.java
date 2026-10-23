package com.argon.launcher.remote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

// Just enough HTTP/1.1 and WebSocket for the remote's page: one thread per connection, keep-alive
// for fixed-length replies, streamed replies close the connection when they're done.
final class Http {
    interface Handler {
        void handle(Request req) throws IOException;
    }

    private final ServerSocket server;
    private final Handler handler;
    private final Consumer<String> log;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "http");
        t.setDaemon(true);
        return t;
    });

    Http(int port, Handler handler, Consumer<String> log) throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress((InetAddress) null, port));
        this.handler = handler;
        this.log = log;
        Thread t = new Thread(this::acceptLoop, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (!server.isClosed()) log.accept("http accept: " + e);
            }
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setTcpNoDelay(true);
            // Idle keep-alive connections are dropped after two minutes, like the old server.
            s.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String ip = s.getInetAddress().getHostAddress();
            while (true) {
                Request req = Request.read(in, out, ip);
                if (req == null) return;
                handler.handle(req);
                if (!req.responded) req.send(500, "text/plain", "no reply".getBytes(StandardCharsets.UTF_8));
                if (req.upgraded || !req.keepAlive || !req.drain()) return;
            }
        } catch (SocketTimeoutException | EOFException ignored) {
        } catch (IOException e) {
            // Browsers drop connections all the time.
        }
    }

    static final class Request {
        String method, path, query, ip;
        final Map<String, String> headers = new HashMap<>();
        private final Map<String, String> params = new HashMap<>();
        private final InputStream in;
        private final OutputStream out;
        private long remaining;
        private boolean keepAlive = true, responded, upgraded;

        private Request(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        static Request read(InputStream in, OutputStream out, String ip) throws IOException {
            String line = readLine(in);
            if (line == null) return null;
            String[] parts = line.split(" ");
            if (parts.length < 3) throw new IOException("bad request line");
            Request r = new Request(in, out);
            r.ip = ip;
            r.method = parts[0];
            String target = parts[1];
            int q = target.indexOf('?');
            r.path = q < 0 ? target : target.substring(0, q);
            r.query = q < 0 ? "" : target.substring(q + 1);
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0) r.headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            String len = r.headers.get("content-length");
            r.remaining = len == null ? 0 : Long.parseLong(len);
            if ("close".equalsIgnoreCase(r.headers.get("connection"))) r.keepAlive = false;
            for (String kv : r.query.split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                String k = e < 0 ? kv : kv.substring(0, e), v = e < 0 ? "" : kv.substring(e + 1);
                r.params.putIfAbsent(decode(k), decode(v));
            }
            return r;
        }

        private static String decode(String s) {
            try {
                return URLDecoder.decode(s, "UTF-8");
            } catch (IllegalArgumentException | java.io.UnsupportedEncodingException e) {
                return s;
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (int c; (c = in.read()) != '\n'; ) {
                if (c < 0) return b.size() == 0 ? null : b.toString("UTF-8");
                if (b.size() > 16384) throw new IOException("line too long");
                if (c != '\r') b.write(c);
            }
            return b.toString("UTF-8");
        }

        String header(String name) {
            return headers.get(name);
        }

        String param(String name) {
            String v = params.get(name);
            return v == null ? "" : v;
        }

        boolean post() {
            return "POST".equals(method);
        }

        long length() {
            return remaining;
        }

        // The request body, read as it arrives.
        InputStream body() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int n = in.read(b, off, (int) Math.min(len, remaining));
                    if (n < 0) throw new EOFException();
                    remaining -= n;
                    return n;
                }
            };
        }

        String text(int max) throws IOException {
            if (remaining > max) throw new IOException("body too large");
            return new String(body().readAllBytes(), StandardCharsets.UTF_8);
        }

        private boolean drain() throws IOException {
            if (remaining > 1 << 20) return false;
            body().readAllBytes();
            return true;
        }

        void send(int status, String type, byte[] body, String... headerPairs) throws IOException {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", type);
            h.put("Content-Length", String.valueOf(body.length));
            for (int i = 0; i + 1 < headerPairs.length; i += 2) h.put(headerPairs[i], headerPairs[i + 1]);
            writeHead(status, h);
            out.write(body);
            out.flush();
        }

        // A reply of unknown length; the connection closes after it.
        OutputStream stream(int status, String... headerPairs) throws IOException {
            Map<String, String> h = new LinkedHashMap<>();
            for (int i = 0; i + 1 < headerPairs.length; i += 2) h.put(headerPairs[i], headerPairs[i + 1]);
            h.put("Connection", "close");
            keepAlive = false;
            writeHead(status, h);
            return out;
        }

        private void writeHead(int status, Map<String, String> h) throws IOException {
            responded = true;
            StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status))
                    .append("\r\n");
            for (Map.Entry<String, String> e : h.entrySet())
                sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }

        private static String reason(int status) {
            switch (status) {
                case 101: return "Switching Protocols";
                case 200: return "OK";
                case 400: return "Bad Request";
                case 401: return "Unauthorized";
                case 403: return "Forbidden";
                case 404: return "Not Found";
                case 409: return "Conflict";
                case 429: return "Too Many Requests";
                case 503: return "Service Unavailable";
                default: return status >= 500 ? "Server Error" : "Status";
            }
        }

        // Answers a WebSocket handshake; the caller then reads messages on this thread.
        WebSocket upgrade() throws IOException {
            String key = header("sec-websocket-key");
            if (key == null || !"websocket".equalsIgnoreCase(header("upgrade"))) return null;
            String accept;
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                accept = Base64.getEncoder().encodeToString(sha1.digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Upgrade", "websocket");
            h.put("Connection", "Upgrade");
            h.put("Sec-WebSocket-Accept", accept);
            writeHead(101, h);
            out.flush();
            upgraded = true;
            return new WebSocket(in, out);
        }
    }

    static final class WebSocket {
        private final InputStream in;
        private final OutputStream out;
        private boolean closed;

        WebSocket(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        // The next text message, or null once the connection is closed.
        String receive() throws IOException {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) return null;
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                if (len > 1 << 20) return null;
                byte[] mask = new byte[4];
                if ((b1 & 0x80) != 0 && in.readNBytes(mask, 0, 4) < 4) return null;
                byte[] data = in.readNBytes((int) len);
                if (data.length < len) return null;
                for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 8) {
                    close(1000, "");
                    return null;
                }
                if (op == 9) {
                    frame(10, data);
                    continue;
                }
                if (op == 10) continue;
                msg.write(data);
                if ((b0 & 0x80) != 0) return msg.toString("UTF-8");
            }
        }

        synchronized void send(String text) {
            try {
                frame(1, text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                closed = true;
            }
        }

        synchronized void close(int code, String reason) {
            if (closed) return;
            byte[] r = reason.getBytes(StandardCharsets.UTF_8);
            byte[] p = new byte[2 + r.length];
            p[0] = (byte) (code >> 8);
            p[1] = (byte) code;
            System.arraycopy(r, 0, p, 2, r.length);
            try {
                frame(8, p);
            } catch (IOException ignored) {
            }
            closed = true;
        }

        private synchronized void frame(int op, byte[] data) throws IOException {
            if (closed) throw new IOException("closed");
            ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 10);
            f.write(0x80 | op);
            if (data.length < 126) f.write(data.length);
            else if (data.length < 65536) {
                f.write(126);
                f.write(data.length >> 8);
                f.write(data.length);
            } else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)));
            }
            f.write(data);
            out.write(f.toByteArray());
            out.flush();
        }
    }
}
