package io.github.martinwoodward.iphotoframesaver.core;

import org.json.JSONException;
import org.json.JSONObject;

public final class Photo {
    public final String id;
    public final String url;
    public final String fallback;
    public final int orientation;
    public final int width;
    public final int height;

    public Photo(String id, String url, String fallback, int orientation, int width, int height) {
        this.id = id;
        this.url = url;
        this.fallback = fallback;
        this.orientation = orientation >= 1 && orientation <= 8 ? orientation : 1;
        this.width = width;
        this.height = height;
    }

    public JSONObject json() throws JSONException {
        return new JSONObject().put("id", id).put("url", url).put("fallback", fallback)
                .put("orientation", orientation).put("width", width).put("height", height);
    }

    public static Photo fromJson(JSONObject o) throws JSONException {
        return new Photo(o.getString("id"), o.getString("url"), o.optString("fallback"),
                o.optInt("orientation", 1), o.optInt("width"), o.optInt("height"));
    }
}
