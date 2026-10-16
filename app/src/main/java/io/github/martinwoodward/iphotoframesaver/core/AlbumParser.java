package io.github.martinwoodward.iphotoframesaver.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AlbumParser {
    private AlbumParser() {}

    public static final class Cloud {
        private final Map<String, JSONObject> masters = new LinkedHashMap<>();
        private final Map<String, JSONObject> assets = new LinkedHashMap<>();

        public void add(JSONObject page) throws JSONException {
            if (page.has("serverErrorCode")) throw new JSONException("Album service rejected request");
            JSONArray records = page.getJSONArray("records");
            for (int i = 0; i < records.length(); i++) {
                JSONObject r = records.getJSONObject(i);
                if (r.optBoolean("deleted")) continue;
                String type = r.optString("recordType");
                if ("CPLMaster".equals(type)) masters.put(r.getString("recordName"), r);
                if ("CPLAsset".equals(type)) assets.put(r.getString("recordName"), r);
            }
        }

        public List<Photo> photos() throws JSONException {
            List<Photo> result = new ArrayList<>();
            for (Map.Entry<String, JSONObject> entry : assets.entrySet()) {
                JSONObject asset = entry.getValue().getJSONObject("fields");
                JSONObject ref = valueObject(asset, "masterRef");
                JSONObject record = masters.get(ref.optString("recordName"));
                if (record == null) throw new JSONException("Incomplete album page");
                JSONObject master = record.getJSONObject("fields");
                String type = string(master, "itemType").toLowerCase(Locale.ROOT);
                if (type.contains("video") || type.contains("movie") || type.contains("mpeg")
                        || number(asset, "duration", 0) > 0 || number(asset, "isHidden", 0) != 0
                        || number(asset, "isTrashed", 0) != 0) continue;
                String[] renderKeys = {"resJPEGFullRes", "resJPEGLargeRes", "resJPEGMedRes"};
                String url = firstUrl(asset, renderKeys);
                boolean rendered = !url.isEmpty();
                if (!rendered) url = firstUrl(master, new String[] {
                        "resJPEGFullRes", "resOriginalRes", "resJPEGLargeRes", "resJPEGMedRes"});
                String thumb = firstUrl(master, new String[] {"resJPEGLargeRes", "resJPEGMedRes", "resJPEGThumbRes"});
                if (url.isEmpty()) url = thumb;
                if (url.isEmpty()) throw new JSONException("Photo resource missing");
                int orientation = rendered ? 1 : number(asset, "orientation",
                        number(master, "originalOrientation", 1));
                result.add(new Photo(entry.getKey(), url, thumb, orientation,
                        number(master, "resOriginalWidth", 0), number(master, "resOriginalHeight", 0)));
            }
            return result;
        }
    }

    public static List<Photo> legacy(JSONObject stream, JSONObject resources) throws JSONException {
        JSONArray photos = stream.getJSONArray("photos");
        JSONObject items = resources.getJSONObject("items");
        JSONObject locations = resources.getJSONObject("locations");
        List<Photo> result = new ArrayList<>();
        for (int i = 0; i < photos.length(); i++) {
            JSONObject p = photos.getJSONObject(i);
            String media = p.optString("mediaAssetType").toLowerCase(Locale.ROOT);
            if (media.contains("video") || p.optDouble("videoDuration", 0) > 0) continue;
            JSONObject derivatives = p.optJSONObject("derivatives");
            if (derivatives == null) continue;
            long largest = -1;
            JSONObject best = null;
            java.util.Iterator<String> keys = derivatives.keys();
            while (keys.hasNext()) {
                JSONObject d = derivatives.getJSONObject(keys.next());
                long pixels = (long) d.optInt("width") * d.optInt("height");
                if (pixels > largest && items.has(d.optString("checksum"))) {
                    largest = pixels;
                    best = d;
                }
            }
            if (best == null) throw new JSONException("Photo resource missing");
            JSONObject item = items.getJSONObject(best.getString("checksum"));
            JSONObject location = locations.getJSONObject(item.getString("url_location"));
            String url = location.getString("scheme") + "://" + location.getJSONArray("hosts").getString(0)
                    + item.getString("url_path");
            result.add(new Photo(p.getString("photoGuid"), url, "", 1,
                    best.optInt("width"), best.optInt("height")));
        }
        return result;
    }

    private static String firstUrl(JSONObject fields, String[] keys) {
        for (String key : keys) {
            String url = valueObject(fields, key).optString("downloadURL");
            if (!url.isEmpty()) return url;
        }
        return "";
    }

    private static JSONObject valueObject(JSONObject fields, String key) {
        JSONObject field = fields.optJSONObject(key);
        JSONObject value = field == null ? null : field.optJSONObject("value");
        return value == null ? new JSONObject() : value;
    }

    private static String string(JSONObject fields, String key) {
        JSONObject field = fields.optJSONObject(key);
        return field == null ? "" : field.optString("value");
    }

    private static int number(JSONObject fields, String key, int fallback) {
        JSONObject field = fields.optJSONObject(key);
        return field == null ? fallback : field.optInt("value", fallback);
    }
}
