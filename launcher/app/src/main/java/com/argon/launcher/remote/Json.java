package com.argon.launcher.remote;

import org.json.JSONException;
import org.json.JSONObject;

// Android's org.json throws checked exceptions from put(); the remote only ever puts plain values.
final class Json {
    private Json() {
    }

    static JSONObject obj(Object... kv) {
        return put(new JSONObject(), kv);
    }

    // Null values are written as JSON null, not dropped, so the page sees the key.
    static JSONObject put(JSONObject o, Object... kv) {
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    static JSONObject parse(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
