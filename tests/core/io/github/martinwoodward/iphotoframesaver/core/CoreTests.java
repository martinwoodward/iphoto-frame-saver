package io.github.martinwoodward.iphotoframesaver.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public final class CoreTests {
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        addresses();
        shuffle();
        refresh();
        sampling();
        cloud();
        legacy();
        serialization();
        System.out.println("PASS: " + assertions + " core assertions (synthetic data only)");
    }

    private static void addresses() {
        AlbumAddress modern = AlbumAddress.parse(" https://photos.icloud.com/shared/album/Synthetic_Album-123/ ");
        check(modern.modern && !modern.china, "Modern route");
        check(modern.canonical.endsWith("Synthetic_Album-123/"), "Canonical route");
        check(AlbumAddress.parse("https://www.icloud.com/sharedalbum/#SyntheticToken123").modern == false, "Legacy route");
        check(AlbumAddress.parse("https://photos.icloud.com.cn/shared/album/SyntheticToken123").china, "China route");
        String[] invalid = {"http://photos.icloud.com/shared/album/SyntheticToken123",
                "https://photos.icloud.com.attacker.test/shared/album/SyntheticToken123/",
                "https://photos.icloud.com@attacker.test/shared/album/SyntheticToken123/",
                "https://user@photos.icloud.com/shared/album/SyntheticToken123/",
                "https://photos.icloud.com:443/shared/album/SyntheticToken123/",
                "https://photos.icloud.com/shared/album/SyntheticToken123/?query=1",
                "https://photos.icloud.com/shared/album/SyntheticToken123/#fragment",
                "https://photos.icloud.com/shared/album/short/",
                "https://photos.icloud.com/shared/gallery/SyntheticToken123/",
                "https://www.icloud.com/photos/", "", "not a url"};
        for (String value : invalid) {
            boolean rejected = false;
            try { AlbumAddress.parse(value); }
            catch (IllegalArgumentException e) {
                rejected = true;
                check(!e.getMessage().contains(value) || value.isEmpty(), "Input not leaked by validation");
            }
            check(rejected, "Reject non-album address");
        }
    }

    private static void shuffle() {
        for (int count : new int[] {0, 1, 2, 5, 195}) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) ids.add("photo-" + i);
            ShuffleDeck deck = new ShuffleDeck(ids, new Random(42), count > 0 ? ids.get(0) : "");
            if (count == 0) {
                check(deck.next() == null && deck.peek(5).isEmpty(), "Empty album");
                continue;
            }
            String previous = ids.get(0);
            for (int cycle = 0; cycle < 10; cycle++) {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    List<String> upcoming = deck.peek(5);
                    check(upcoming.size() == 5, "Exactly five future slots");
                    String current = deck.next();
                    check(upcoming.get(0).equals(current), "Prefetch order matches playback");
                    check(count == 1 || !current.equals(previous), "No immediate repeat including cycle boundary");
                    check(seen.add(current), "Every photo once per cycle");
                    previous = current;
                }
                check(seen.size() == count, "Complete album covered each cycle");
            }
        }
    }

    private static void refresh() {
        check(PlaybackPolicy.refreshMillis("hourly") == 3600000, "Hourly");
        check(PlaybackPolicy.refreshMillis("daily") == 86400000, "Daily");
        check(PlaybackPolicy.refreshMillis("weekly") == 604800000, "Weekly");
        check(!PlaybackPolicy.refreshDue(900000000, 0, "manual"), "Manual never schedules");
        check(PlaybackPolicy.refreshDue(3601000, 1000, "hourly"), "Due at exact boundary");
        check(!PlaybackPolicy.refreshDue(3600999, 1000, "hourly"), "Not early");
        check(PlaybackPolicy.refreshDue(50, 1000, "hourly"), "Clock rollback recovers");
        check(PlaybackPolicy.intervalSeconds("30") == 30, "Default interval");
        for (String bad : Arrays.asList("0", "4", "86401", "-1", "abc", "999999999999999")) {
            boolean rejected = false;
            try { PlaybackPolicy.intervalSeconds(bad); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "Invalid interval rejected");
        }
    }

    private static void sampling() {
        for (int[] dimensions : new int[][] {{5712, 4284}, {8000, 6000}, {1200, 900},
                {40000, 30000}, {200000, 200000}, {320, 240}}) {
            int sample = PlaybackPolicy.sampleSize(dimensions[0], dimensions[1], 1366, 768);
            check((sample & (sample - 1)) == 0, "Power of two sample");
            check((long) (dimensions[0] / sample) * (dimensions[1] / sample) <= 1366L * 768 * 2,
                    "Decoded pixels bounded to screen budget");
        }
    }

    private static JSONObject field(Object value) throws JSONException {
        return new JSONObject().put("value", value);
    }

    private static JSONObject master(String id, String type) throws JSONException {
        return new JSONObject().put("recordName", id).put("recordType", "CPLMaster")
                .put("fields", new JSONObject().put("itemType", field(type))
                        .put("originalOrientation", field(6)).put("resOriginalWidth", field(4000))
                        .put("resOriginalHeight", field(3000))
                        .put("resOriginalRes", field(new JSONObject().put("downloadURL", "https://example.icloud-content.com/original")))
                        .put("resJPEGThumbRes", field(new JSONObject().put("downloadURL", "https://example.icloud-content.com/thumb"))));
    }

    private static JSONObject asset(String id, String master) throws JSONException {
        return new JSONObject().put("recordName", id).put("recordType", "CPLAsset")
                .put("fields", new JSONObject().put("masterRef", field(new JSONObject().put("recordName", master)))
                        .put("orientation", field(8)));
    }

    private static void cloud() throws JSONException {
        AlbumParser.Cloud parser = new AlbumParser.Cloud();
        // Masters and assets can arrive on different pages, and several assets can share a master.
        parser.add(new JSONObject().put("records", new JSONArray()
                .put(asset("photo-1", "master-1")).put(asset("photo-2", "master-1"))));
        parser.add(new JSONObject().put("records", new JSONArray()
                .put(master("master-1", "public.heic")).put(master("video-master", "public.mpeg-4"))
                .put(asset("video-1", "video-master"))));
        parser.add(new JSONObject().put("records", new JSONArray().put(asset("photo-1", "master-1"))));
        List<Photo> photos = parser.photos();
        check(photos.size() == 2, "Join assets across pages, deduplicate, exclude videos");
        check(photos.get(0).orientation == 8, "Asset orientation overrides master");
        check(photos.get(0).width == 4000 && photos.get(0).url.endsWith("/original"), "Full resolution preferred");
        check(photos.get(0).fallback.endsWith("/thumb"), "JPEG fallback retained");
        AlbumParser.Cloud missing = new AlbumParser.Cloud();
        missing.add(new JSONObject().put("records", new JSONArray().put(asset("missing", "not-present"))));
        boolean rejected = false;
        try { missing.photos(); } catch (JSONException e) { rejected = true; }
        check(rejected, "Incomplete metadata is not silently accepted");
        JSONObject rendered = asset("rendered", "master-1");
        rendered.getJSONObject("fields").put("resJPEGFullRes",
                field(new JSONObject().put("downloadURL", "https://example.icloud-content.com/edited")));
        AlbumParser.Cloud edits = new AlbumParser.Cloud();
        edits.add(new JSONObject().put("records", new JSONArray().put(master("master-1", "public.jpeg")).put(rendered)));
        check(edits.photos().get(0).url.endsWith("/edited") && edits.photos().get(0).orientation == 1,
                "Edited render preferred and not rotated again");
        AlbumParser.Cloud large = new AlbumParser.Cloud();
        for (int page = 0; page < 8; page++) {
            JSONArray records = new JSONArray();
            for (int i = page * 25; i < Math.min(195, (page + 1) * 25); i++) {
                records.put(master("master-" + i, "public.jpeg")).put(asset("photo-" + i, "master-" + i));
            }
            large.add(new JSONObject().put("records", records));
        }
        check(large.photos().size() == 195, "All pages retained beyond first batch");
    }

    private static void legacy() throws JSONException {
        JSONObject small = new JSONObject().put("width", "480").put("height", "320").put("checksum", "small");
        JSONObject full = new JSONObject().put("width", "2400").put("height", "1600").put("checksum", "full");
        JSONObject photo = new JSONObject().put("photoGuid", "synthetic-photo").put("mediaAssetType", "image")
                .put("derivatives", new JSONObject().put("1", small).put("2", full));
        JSONObject video = new JSONObject(photo.toString()).put("photoGuid", "synthetic-video").put("mediaAssetType", "video");
        JSONObject stream = new JSONObject().put("photos", new JSONArray().put(photo).put(video));
        JSONObject resources = new JSONObject()
                .put("items", new JSONObject()
                        .put("small", new JSONObject().put("url_location", "cdn").put("url_path", "/small"))
                        .put("full", new JSONObject().put("url_location", "cdn").put("url_path", "/full")))
                .put("locations", new JSONObject().put("cdn", new JSONObject().put("scheme", "https")
                        .put("hosts", new JSONArray().put("example.icloud-content.com"))));
        List<Photo> photos = AlbumParser.legacy(stream, resources);
        check(photos.size() == 1, "Legacy video excluded");
        check(photos.get(0).url.endsWith("/full"), "Largest legacy derivative selected");
    }

    private static void serialization() throws JSONException {
        Photo p = new Photo("synthetic", "https://example.icloud-content.com/photo", "", 9, 4000, 3000);
        Photo q = Photo.fromJson(new JSONObject(p.json().toString()));
        check(q.id.equals(p.id) && q.orientation == 1 && q.width == 4000, "Metadata round trip and orientation guard");
    }
}
