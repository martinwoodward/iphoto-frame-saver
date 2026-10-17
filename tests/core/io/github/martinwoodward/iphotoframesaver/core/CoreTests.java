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
        startupAndMembership();
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

    private static void startupAndMembership() {
        List<Photo> photos = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 195; i++) {
            photos.add(new Photo("photo-" + i, "https://example.icloud-content.com/photo", "", 1, 64, 64, i * 1000L));
            ids.add("photo-" + i);
        }
        List<String> recent = new ArrayList<>();
        for (Photo photo : PlaybackPolicy.recentPhotos(photos)) recent.add(photo.id);
        check(recent.size() == 10 && recent.get(0).equals("photo-194")
                && recent.get(9).equals("photo-185"), "Exactly ten newest sharing timestamps, not capture order");
        java.util.Collections.reverse(photos);
        check(PlaybackPolicy.recentPhotos(photos).get(0).id.equals("photo-194"), "Newest selection ignores input ordering");
        check(PlaybackPolicy.recentPhotos(photos.subList(0, 3)).size() == 3, "Small albums cache every photo");
        Set<String> starts = new HashSet<>();
        for (int seed = 0; seed < 100; seed++) {
            ShuffleDeck deck = new ShuffleDeck(ids, new Random(seed), "photo-194");
            String first = deck.choose(recent);
            check(recent.contains(first) && !first.equals("photo-194"), "Random startup is recent and avoids the previous photo");
            starts.add(first);
            check(deck.consume(first), "Startup consumes its full-album place");
            Set<String> seen = new HashSet<>();
            seen.add(first);
            for (int i = 1; i < ids.size(); i++) {
                check(deck.peek(5).size() == 5, "Preferred startup preserves lookahead");
                check(seen.add(deck.next()), "No startup repeat until every photo has been shown");
            }
            check(seen.size() == ids.size(), "Startup cycle covers the complete large album");
        }
        check(starts.size() == 9, "Startup randomization reaches every eligible recent photo");
        List<String> small = new ArrayList<>(Arrays.asList("a", "b", "c", "d", "e"));
        ShuffleDeck deck = new ShuffleDeck(small, new Random(17), "");
        String shown = deck.next();
        String removed = deck.peek(20).get(0);
        small.remove(removed);
        small.add("new");
        deck.update(small);
        Set<String> seen = new HashSet<>();
        seen.add(shown);
        for (int i = 1; i < small.size(); i++) {
            String next = deck.next();
            check(!next.equals(removed) && seen.add(next), "Refresh removes deleted IDs without repeating visited photos");
        }
        check(seen.contains("new") && seen.containsAll(small), "Newly shared photos join the unfinished cycle");
        check(!deck.peek(100).contains(removed), "Deleted IDs leave pregenerated future cycles too");
        deck.update(Arrays.asList("new"));
        check(deck.peek(5).equals(Arrays.asList("new", "new", "new", "new", "new")), "Shrink to one photo");
        deck.update(new ArrayList<>());
        check(deck.next() == null && deck.peek(5).isEmpty(), "Deleting all photos empties the rotation");
        ShuffleDeck deferred = new ShuffleDeck(Arrays.asList("a", "b", "c"), new Random(4), "");
        String failed = deferred.peek(10).get(0);
        deferred.defer(failed);
        check(!deferred.peek(1).get(0).equals(failed), "Unavailable photo does not block other unseen photos");
        Set<String> retried = new HashSet<>();
        for (int i = 0; i < 3; i++) retried.add(deferred.next());
        check(retried.size() == 3 && retried.contains(failed), "Failed fetch is deferred, not counted as displayed");
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
        JSONObject dated = asset("dated", "master-1");
        dated.getJSONObject("fields").put("addedDate", field(1000L))
                .put("mostRecentAddedDate", field(2000L)).put("assetDate", field(999999L));
        AlbumParser.Cloud dates = new AlbumParser.Cloud();
        dates.add(new JSONObject().put("records", new JSONArray().put(master("master-1", "public.jpeg")).put(dated)));
        check(dates.photos().get(0).sharedAt == 2000L, "Use latest sharing date, never capture date");
        dated.getJSONObject("fields").remove("mostRecentAddedDate");
        check(dates.photos().get(0).sharedAt == 1000L, "Added date fallback");
        dates.add(new JSONObject().put("records", new JSONArray().put(new JSONObject()
                .put("recordName", "dated").put("recordType", "CPLAsset").put("deleted", true))));
        check(dates.photos().isEmpty(), "Cloud deletion tombstones remove earlier records");
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
        photo.put("batchDateCreated", "2024-01-02T03:04:05.000Z").put("dateCreated", 9999999999999L);
        long shared = AlbumParser.legacy(stream, resources).get(0).sharedAt;
        check(shared == 1704164645000L, "Legacy batch sharing time, not capture date");
        photo.put("batchDateCreated", shared);
        check(AlbumParser.legacy(stream, resources).get(0).sharedAt == shared, "Numeric legacy sharing time");
        photo.put("batchDateCreated", "2024-01-02T04:04:05+01:00");
        check(AlbumParser.legacy(stream, resources).get(0).sharedAt == shared, "Legacy offset timestamp");
    }

    private static void serialization() throws JSONException {
        Photo p = new Photo("synthetic", "https://example.icloud-content.com/photo", "", 9, 4000, 3000);
        Photo q = Photo.fromJson(new JSONObject(p.json().toString()));
        check(q.id.equals(p.id) && q.orientation == 1 && q.width == 4000, "Metadata round trip and orientation guard");
        JSONObject old = p.json();
        old.remove("sharedAt");
        check(Photo.fromJson(old).sharedAt == 0, "Older saved metadata remains readable");
        Photo dated = new Photo(p.id, p.url, "", 1, 64, 64, 123456789L);
        check(Photo.fromJson(dated.json()).sharedAt == 123456789L, "Sharing date survives restart");
    }
}
