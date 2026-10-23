package com.argon.launcher.smarthome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// Tuya's cloud, used once while setting up: it's the only place a plug's local key comes from. A developer project at
// platform.tuya.com, linked to the Smart Life (or Tuya) app account the plugs are in, gives the Access ID and secret.
// Neither is kept; only the plugs' ids and keys are.
final class TuyaCloud {
    static final String[][] REGIONS = {
            {"in", "India", "https://openapi.tuyain.com"},
            {"eu", "Central Europe", "https://openapi.tuyaeu.com"},
            {"us", "Western America", "https://openapi.tuyaus.com"},
            {"cn", "China", "https://openapi.tuyacn.com"},
    };
    // Sockets, power strips and switches: data point 1 switches them.
    private static final List<String> SWITCHES = List.of("cz", "pc", "kg", "tdq");

    static final class Plug {
        String id, key, name;
    }

    private final String id, secret, base;
    private String token;

    TuyaCloud(String accessId, String secret, String region) {
        id = accessId.trim();
        this.secret = secret.trim();
        String b = REGIONS[0][2];
        for (String[] r : REGIONS) if (r[0].equals(region)) b = r[2];
        base = b;
    }

    /** The plugs in the linked app account, with their local keys. */
    List<Plug> plugs() throws IOException {
        try {
            token = call("/v1.0/token?grant_type=1").getJSONObject("result").getString("access_token");
            List<Plug> out = new ArrayList<>();
            String last = "";
            while (true) {
                // The signature covers the query with its keys in alphabetical order.
                JSONObject page = call("/v1.0/iot-01/associated-users/devices?" + (last.isEmpty() ? "" : "last_row_key=" + last + "&") + "size=50")
                        .getJSONObject("result");
                JSONArray ds = page.getJSONArray("devices");
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject d = ds.getJSONObject(i);
                    if (!SWITCHES.contains(d.optString("category"))) continue;
                    Plug p = new Plug();
                    p.id = d.getString("id");
                    p.key = d.getString("local_key");
                    p.name = d.optString("name", "Plug");
                    out.add(p);
                }
                if (!page.optBoolean("has_more")) return out;
                last = page.getString("last_row_key");
            }
        } catch (JSONException e) {
            throw new IOException("Tuya's answer didn't make sense: " + e.getMessage());
        }
    }

    private JSONObject call(String path) throws IOException, JSONException {
        String t = String.valueOf(System.currentTimeMillis()), nonce = UUID.randomUUID().toString().replace("-", "");
        String toSign = "GET\n" + sha256("") + "\n\n" + path;
        String sign = hmac(id + (token == null ? "" : token) + t + nonce + toSign);
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10_000);
        c.setRequestProperty("client_id", id);
        c.setRequestProperty("sign", sign);
        c.setRequestProperty("t", t);
        c.setRequestProperty("nonce", nonce);
        c.setRequestProperty("sign_method", "HMAC-SHA256");
        if (token != null) c.setRequestProperty("access_token", token);
        try (InputStream in = c.getInputStream()) {
            JSONObject r = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (!r.optBoolean("success")) {
                int code = r.optInt("code");
                throw new IOException(code == 1004 || code == 1001 ? "Tuya didn't accept that Access ID and secret"
                        : code == 2406 || code == 1106 ? "Link your Smart Life app to the project first (Devices › Link App Account)"
                        : "Tuya: " + r.optString("msg", "error " + code));
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(String s) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private String hmac(String s) throws IOException {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : m.doFinal(s.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString().toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }
}
