package com.argon.launcher.remote;

import static com.argon.launcher.remote.Shell.quote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// The Home page: the launcher's own settings, through its ControlReceiver, plus the club and place
// searches its match and weather cards use.
final class TvHome {
    private static final String LAUNCHER = "com.argon.launcher";
    private static final Pattern PKG = Pattern.compile("^[A-Za-z0-9_.]+$");
    private static final Map<String, byte[]> banners = new ConcurrentHashMap<>();

    private TvHome() {
    }

    // `am broadcast` prints the receiver's reply as result data. Returns {ok, data} or {ok:false, error}.
    private static JSONObject control(Shell sh, String extras) {
        String out = sh.run("am broadcast -a " + LAUNCHER + ".CONTROL -p " + LAUNCHER + " " + extras, 8000);
        int i = out.indexOf("data=\"");
        if (i < 0)
            return Json.obj("ok", false, "error", out.contains("result=0") ? "old-launcher" : "TV not connected");
        String data = out.substring(i + 6, out.lastIndexOf('"'));
        if (data.startsWith("error:")) return Json.obj("ok", false, "error", data.substring(7));
        return Json.obj("ok", true, "data", data);
    }

    static JSONObject state(Shell sh) {
        JSONObject r = control(sh, "--es cmd state");
        if (!r.optBoolean("ok")) return r;
        return Json.obj("ok", true, "state", Json.parse(r.optString("data")));
    }

    static JSONObject set(Shell sh, String key, String value) {
        if (!key.matches("^[a-z_]+$")) return Json.obj("ok", false, "error", "Bad key");
        return control(sh, "--es cmd set --es key " + key + " --es value " + quote(value));
    }

    static byte[] banner(Shell sh, String pkg) {
        if (!PKG.matcher(pkg).matches()) return null;
        byte[] hit = banners.get(pkg);
        if (hit != null) return hit;
        JSONObject r = control(sh, "--es cmd banner --es pkg " + pkg);
        if (!r.optBoolean("ok")) return null;
        byte[] png = Base64.getMimeDecoder().decode(r.optString("data"));
        banners.put(pkg, png);
        return png;
    }

    // ESPN club search for the match card; team lists are cached for the server's lifetime.
    private static final List<String> LEAGUES = Arrays.asList("eng.1", "eng.2", "esp.1", "ger.1", "ita.1", "fra.1",
            "ned.1", "por.1", "usa.1", "ind.1", "gre.1", "tur.1", "sco.1", "bra.1", "arg.1");
    private static volatile List<JSONObject> teams;

    private static JSONObject fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        // ESPN's CDN turns away default user agents.
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private static List<JSONObject> league(String l) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONObject lg = fetch("https://site.api.espn.com/apis/site/v2/sports/soccer/" + l + "/teams")
                    .optJSONArray("sports").optJSONObject(0).optJSONArray("leagues").optJSONObject(0);
            String name = lg.optString("name", l);
            JSONArray ts = lg.optJSONArray("teams");
            for (int i = 0; ts != null && i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i).optJSONObject("team");
                JSONArray logos = t.optJSONArray("logos");
                out.add(Json.obj("id", t.optString("id"), "name", t.optString("displayName"), "league", name,
                        "logo", logos != null && logos.length() > 0 ? logos.optJSONObject(0).optString("href") : ""));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out;
    }

    static JSONArray searchTeams(String q) {
        if (teams == null) {
            Map<String, JSONObject> all = new LinkedHashMap<>();
            for (List<JSONObject> l : LEAGUES.parallelStream().map(TvHome::league).collect(Collectors.toList()))
                for (JSONObject t : l) all.putIfAbsent(t.optString("id"), t);
            if (all.isEmpty()) return new JSONArray();
            teams = new ArrayList<>(all.values());
        }
        String s = q.trim().toLowerCase(Locale.ROOT);
        JSONArray out = new JSONArray();
        if (s.isEmpty()) return out;
        for (JSONObject t : teams) {
            if (out.length() >= 12) break;
            if (t.optString("name").toLowerCase(Locale.ROOT).contains(s)) out.put(t);
        }
        return out;
    }

    static JSONObject teamName(String id) {
        searchTeams("x");
        if (teams == null) return null;
        for (JSONObject t : teams) if (t.optString("id").equals(id)) return t;
        return null;
    }

    static JSONArray searchPlaces(String q) {
        JSONArray out = new JSONArray();
        if (q.trim().isEmpty()) return out;
        try {
            JSONArray rs = fetch("https://geocoding-api.open-meteo.com/v1/search?count=8&name="
                    + URLEncoder.encode(q.trim(), "UTF-8")).optJSONArray("results");
            for (int i = 0; rs != null && i < rs.length(); i++) {
                JSONObject p = rs.optJSONObject(i);
                List<String> region = new ArrayList<>();
                for (String k : new String[]{"admin1", "country"}) if (!p.optString(k).isEmpty()) region.add(p.optString(k));
                out.put(Json.obj("name", p.optString("name"), "region", String.join(", ", region),
                        "lat", Math.round(p.optDouble("latitude") * 1e4) / 1e4,
                        "lon", Math.round(p.optDouble("longitude") * 1e4) / 1e4));
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return out;
    }
}
