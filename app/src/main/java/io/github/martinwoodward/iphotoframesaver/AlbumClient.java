package io.github.martinwoodward.iphotoframesaver;

import io.github.martinwoodward.iphotoframesaver.core.AlbumAddress;
import io.github.martinwoodward.iphotoframesaver.core.AlbumParser;
import io.github.martinwoodward.iphotoframesaver.core.Photo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AlbumClient {
    List<Photo> fetch(AlbumAddress address) throws FrameException {
        try {
            return address.modern ? modern(address) : legacy(address);
        } catch (JSONException | IllegalArgumentException e) {
            throw new FrameException(FrameException.ALBUM);
        }
    }

    private String encode(String text) {
        try { return URLEncoder.encode(text, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException e) { throw new AssertionError(e); }
    }

    private List<Photo> modern(AlbumAddress address) throws JSONException, FrameException {
        String root = address.china ? "icloud.com.cn" : "icloud.com";
        String path = "/database/1/com.apple.photos.cloud/production/";
        String key = "?remapEnums=true&getCurrentSyncToken=true&sharing_url_key=" + encode(address.token);
        JSONObject resolved = Net.post("https://ckdatabasews." + root + path + "public/records/resolve" + key,
                new JSONObject().put("shortGUIDs", new JSONArray().put(
                        new JSONObject().put("value", address.token).put("shouldFetchRootRecord", true))));
        JSONObject result = resolved.getJSONArray("results").getJSONObject(0);
        JSONObject access = result.getJSONObject("anonymousPublicAccess");
        int partition = access.getInt("resolvedPartitionNumber");
        if (partition < 1 || partition > 999) throw new JSONException("Invalid partition");
        String queryUrl = "https://p" + String.format(java.util.Locale.ROOT, "%02d", partition)
                + "-ckdatabasews." + root + path + "shared/records/query" + key
                + "&publicAccessAuthToken=" + encode(access.getString("token"));
        JSONObject zone = result.getJSONObject("zoneID");
        JSONArray filters = new JSONArray()
                .put(filter("direction", "ASCENDING", "STRING"))
                .put(filter("startRank", 0, "INT64"));
        JSONObject request = new JSONObject().put("query", new JSONObject()
                .put("recordType", "CPLAssetAndMasterByAddedDate").put("filterBy", filters))
                .put("zoneID", zone).put("resultsLimit", 200);
        AlbumParser.Cloud parser = new AlbumParser.Cloud();
        Set<String> markers = new HashSet<>();
        for (int page = 0; page < 10000; page++) {
            JSONObject response = Net.post(queryUrl, request);
            parser.add(response);
            String marker = response.optString("continuationMarker");
            if (marker.isEmpty()) return parser.photos();
            if (!markers.add(marker)) throw new JSONException("Repeated pagination cursor");
            request.put("continuationMarker", marker);
        }
        throw new JSONException("Pagination limit reached");
    }

    private JSONObject filter(String name, Object value, String type) throws JSONException {
        return new JSONObject().put("fieldName", name).put("comparator", "EQUALS")
                .put("fieldValue", new JSONObject().put("value", value).put("type", type));
    }

    private List<Photo> legacy(AlbumAddress address) throws JSONException, FrameException {
        String root = address.china ? "icloud.com.cn" : "icloud.com";
        String base = "https://p01-sharedstreams." + root + "/" + address.token;
        JSONObject stream = Net.post(base + "/sharedstreams/webstream",
                new JSONObject().put("streamCtag", JSONObject.NULL));
        String redirect = stream.optString("X-Apple-MMe-Host");
        if (!redirect.isEmpty()) {
            if (!redirect.matches("p[0-9]{2,3}-sharedstreams\\.icloud\\.com(\\.cn)?")) {
                throw new JSONException("Invalid album host");
            }
            base = "https://" + redirect + "/" + address.token;
            stream = Net.post(base + "/sharedstreams/webstream",
                    new JSONObject().put("streamCtag", JSONObject.NULL));
        }
        JSONArray photos = stream.getJSONArray("photos");
        JSONObject items = new JSONObject();
        JSONObject locations = new JSONObject();
        for (int start = 0; start < photos.length(); start += 100) {
            JSONArray ids = new JSONArray();
            for (int i = start; i < Math.min(start + 100, photos.length()); i++) {
                ids.put(photos.getJSONObject(i).getString("photoGuid"));
            }
            JSONObject urls = Net.post(base + "/sharedstreams/webasseturls",
                    new JSONObject().put("photoGuids", ids));
            merge(items, urls.getJSONObject("items"));
            merge(locations, urls.getJSONObject("locations"));
        }
        return AlbumParser.legacy(stream, new JSONObject().put("items", items).put("locations", locations));
    }

    private void merge(JSONObject into, JSONObject from) throws JSONException {
        java.util.Iterator<String> keys = from.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            into.put(key, from.get(key));
        }
    }
}
