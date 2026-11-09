// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The parts without Android: JSON, lyrics, the clock, track matching and the API client. */
public final class CoreTest {
    static final String SYLLABLE = "{\"Body\":{\"Type\":\"Syllable\",\"id\":\"4cOdK2wGLETKBW3PvgPWqT\",\"source\":\"spicy_lyrics\","
        + "\"SongWriters\":[\"Mike Stock\",\"Matt Aitken\"],"
        + "\"UploadAttribution\":{\"Uploader\":{\"username\":\"syncer\",\"url\":\"https://spicylyrics.org/uid/1\"},\"Maker\":{\"username\":\"maker\",\"url\":\"https://spicylyrics.org/uid/2\"}},"
        + "\"StartTime\":18.5,\"EndTime\":40,\"Content\":["
        + "{\"Type\":\"Vocal\",\"OppositeAligned\":false,\"Lead\":{\"StartTime\":18.5,\"EndTime\":20.1,\"Syllables\":["
        + "{\"Text\":\"We're\",\"StartTime\":18.5,\"EndTime\":18.8,\"IsPartOfWord\":false},"
        + "{\"Text\":\"no\",\"StartTime\":18.8,\"EndTime\":19.1,\"IsPartOfWord\":false},"
        + "{\"Text\":\"stran\",\"StartTime\":19.1,\"EndTime\":19.5,\"IsPartOfWord\":true},"
        + "{\"Text\":\"gers\",\"StartTime\":19.5,\"EndTime\":20.1,\"IsPartOfWord\":false}]},"
        + "\"Background\":[{\"StartTime\":20.2,\"EndTime\":21,\"Syllables\":[{\"Text\":\"(ooh)\",\"StartTime\":20.2,\"EndTime\":21}]}]},"
        + "{\"Type\":\"Vocal\",\"OppositeAligned\":true,\"Lead\":{\"StartTime\":30,\"EndTime\":32,\"Syllables\":["
        + "{\"Text\":\"to\",\"StartTime\":30,\"EndTime\":30.5},{\"Text\":\"love\",\"StartTime\":30.5,\"EndTime\":32}]}}"
        + "]},\"Status\":200,\"Type\":\"object\"}";

    public static void main(String[] args) throws Exception {
        json();
        lyrics();
        clock();
        times();
        tracks();
        client();
        System.out.println("CoreTest passed");
    }

    static void json() {
        Map<?, ?> value = (Map<?, ?>) Json.parse("{\"a\":[1,2.5,-3e2,true,false,null],\"b\":\"x\\\"\\u00e4\\n\",\"c\":{}}");
        List<?> a = (List<?>) value.get("a");
        assert ((Double) a.get(0)) == 1 && ((Double) a.get(1)) == 2.5 && ((Double) a.get(2)) == -300;
        assert a.get(3) == Boolean.TRUE && a.get(4) == Boolean.FALSE && a.get(5) == null;
        assert "x\"ä\n".equals(value.get("b"));
        for (String bad : new String[] {"", "{", "{\"a\" 1}", "[1,]x", "\"open", "{\"a\":1}x"}) {
            boolean refused = false;
            try { Json.parse(bad); } catch (IllegalArgumentException expected) { refused = true; }
            assert refused : bad;
        }
    }

    static void lyrics() {
        Lyrics lyrics = Lyrics.parse(Json.map(Json.map(Json.parse(SYLLABLE)).get("Body")));
        assert lyrics.type == Lyrics.SYLLABLE && lyrics.timed();
        // Dots through the intro, the lead, its background vocal, dots for the 9 second gap, the second lead.
        assert lyrics.lines.size() == 5 : lyrics.lines.size();
        assert lyrics.lines.get(0).interlude && lyrics.lines.get(0).start == 0 && lyrics.lines.get(0).end == 18.5;
        assert "We're no strangers".equals(lyrics.lines.get(1).text) : lyrics.lines.get(1).text;
        assert lyrics.lines.get(2).background && "(ooh)".equals(lyrics.lines.get(2).text);
        assert lyrics.lines.get(3).interlude && lyrics.lines.get(3).start == 21 && lyrics.lines.get(3).end == 30;
        assert lyrics.lines.get(4).opposite && "to love".equals(lyrics.lines.get(4).text);
        assert lyrics.lines.get(1).syllables.get(2).joinNext;
        assert lyrics.focus(10) == 0;
        assert lyrics.focus(19) == 1;
        // Background vocals never take the focus.
        assert lyrics.focus(20.5) == 1;
        assert lyrics.focus(25) == 3;
        assert lyrics.focus(31) == 4;
        assert "spicy_lyrics".equals(lyrics.source);
        assert lyrics.credits.size() == 2 && "syncer".equals(lyrics.credits.get(0).name) && "maker".equals(lyrics.credits.get(1).role);
        assert lyrics.writers.equals(Arrays.asList("Mike Stock", "Matt Aitken"));

        Lyrics line = Lyrics.parse(Json.map(Json.parse("{\"Type\":\"Line\",\"source\":\"spotify\",\"Content\":["
            + "{\"Type\":\"Vocal\",\"Text\":\"Second\",\"StartTime\":5,\"EndTime\":6},"
            + "{\"Type\":\"Vocal\",\"Text\":\"First\",\"StartTime\":1,\"EndTime\":2},"
            + "{\"Type\":\"Vocal\",\"Text\":\"Backwards\",\"StartTime\":9,\"EndTime\":8},"
            + "{\"Type\":\"Vocal\",\"Text\":\"\",\"StartTime\":10,\"EndTime\":11}]}")));
        assert line.type == Lyrics.LINE && line.lines.size() == 2;
        assert "First".equals(line.lines.get(0).text) && "Second".equals(line.lines.get(1).text);
        assert line.credits.isEmpty();

        Lyrics plain = Lyrics.parse(Json.map(Json.parse("{\"Type\":\"Static\",\"Lines\":[{\"Text\":\"One\"},{\"Text\":\" \"},{\"Text\":\"Two\"}]}")));
        assert !plain.timed() && plain.lines.size() == 2 && plain.focus(100) == -1;

        boolean refused = false;
        try { Lyrics.parse(Json.map(Json.parse("{\"Type\":\"Karaoke\"}"))); } catch (IllegalArgumentException expected) { refused = true; }
        assert refused;
    }

    static void clock() {
        PlaybackClock clock = new PlaybackClock();
        long s = 1_000_000_000L;
        assert clock.position(5 * s) == 0 && !clock.known();
        clock.set(10, true, 0);
        assert near(clock.position(2 * s), 12);
        // A report a little off nudges the clock a quarter of the way.
        clock.sync(12.4, true, 2 * s);
        assert near(clock.position(2 * s), 12.1) : clock.position(2 * s);
        // A seek is taken exactly.
        clock.sync(60, true, 3 * s);
        assert near(clock.position(3 * s), 60);
        clock.setPlaying(false, 4 * s);
        assert near(clock.position(9 * s), 61) && !clock.playing();
        clock.sync(61, false, 10 * s);
        assert near(clock.position(20 * s), 61);
        clock.reset();
        assert !clock.known() && clock.position(30 * s) == 0;

        // A stale zero-position timestamp must not count the time spent preparing the track.
        clock.report(0, 60, true, true, 30 * s);
        assert near(clock.position(30 * s), 0);
        assert near(clock.position(31 * s), 1);
        // Attaching to an already playing track still accounts for an in-flight position report.
        clock.report(12, 0.5, true, true, 32 * s);
        assert near(clock.position(32 * s), 12.5);
        // Resuming after a long pause must not add that pause, even if later reports retain its old timestamp.
        clock.set(42, false, 33 * s);
        clock.report(42, 120, true, false, 153 * s);
        assert near(clock.position(153 * s), 42);
        clock.report(42, 122, true, false, 155 * s);
        assert near(clock.position(155 * s), 44);
        // The same rule holds when a player event confirms playback before the next queue report arrives.
        clock.setPlaying(false, 156 * s);
        clock.setPlaying(true, 200 * s);
        clock.report(45, 60, true, false, 200 * s);
        assert near(clock.position(200 * s), 45);

        Model model = new Model();
        model.offset = 3;
        assert model.position(0) < 0;
        model.clock.set(0, false, 0);
        assert model.position(10 * s) < 0 : "The offset must not start the first words while buffering";
        model.clock.setPlaying(true, 10 * s);
        assert near(model.position(11 * s), 4);
    }

    static void times() {
        assert near(Times.epochSeconds("1970-01-01T00:00:00Z"), 0);
        assert near(Times.epochSeconds("2026-11-09T19:56:12.5+00:00"), 1794254172.5) : Times.epochSeconds("2026-11-09T19:56:12.5+00:00");
        assert near(Times.epochSeconds("2026-11-09T20:56:12.5+01:00"), 1794254172.5);
        assert near(Times.epochSeconds("2024-02-29 12:00:00"), 1709208000);
        assert Double.isNaN(Times.epochSeconds("yesterday")) && Double.isNaN(Times.epochSeconds(null));
    }

    static void tracks() {
        assert "4cOdK2wGLETKBW3PvgPWqT".equals(TrackInfo.spotifyId("spotify:track:4cOdK2wGLETKBW3PvgPWqT"));
        assert "4cOdK2wGLETKBW3PvgPWqT".equals(TrackInfo.spotifyId("spotify--x8Fk2://track/4cOdK2wGLETKBW3PvgPWqT"));
        assert "4cOdK2wGLETKBW3PvgPWqT".equals(TrackInfo.spotifyId("https://open.spotify.com/intl-de/track/4cOdK2wGLETKBW3PvgPWqT?si=abc"));
        assert "".equals(TrackInfo.spotifyId("library://track/12"));

        Map<?, ?> item = Json.map(Json.parse("{\"name\":\"Rick Astley - Never Gonna Give You Up\",\"duration\":213,"
            + "\"media_item\":{\"media_type\":\"track\",\"name\":\"Never Gonna Give You Up\",\"uri\":\"library://track/7\",\"duration\":213,"
            + "\"artists\":[{\"name\":\"Rick Astley\"}],\"album\":{\"name\":\"Whenever You Need Somebody\"},"
            + "\"external_ids\":[[\"isrc\",\"GBARL9300135\"]],"
            + "\"provider_mappings\":[{\"provider_domain\":\"tidal\",\"item_id\":\"491206012\"},{\"provider_domain\":\"spotify\",\"item_id\":\"4cOdK2wGLETKBW3PvgPWqT\"}]}}"));
        TrackInfo track = TrackInfo.fromQueueItem(item);
        assert "4cOdK2wGLETKBW3PvgPWqT".equals(track.spotifyId) && "GBARL9300135".equals(track.isrc);
        assert "Rick Astley".equals(track.artist) && track.duration == 213 && "spotify:4cOdK2wGLETKBW3PvgPWqT".equals(track.key);

        TrackInfo radio = TrackInfo.fromQueueItem(Json.map(Json.parse("{\"name\":\"Kraftklub - Teil dieser Band\"}")));
        assert "Teil dieser Band".equals(radio.title) && "Kraftklub".equals(radio.artist) && radio.lookupId().isEmpty();
        assert TrackInfo.fromQueueItem(Json.map(Json.parse("{\"media_item\":{\"media_type\":\"radio\",\"name\":\"1LIVE\"}}"))) == null;

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("media_title", "Never Gonna Give You Up");
        attributes.put("media_artist", "Rick Astley");
        attributes.put("media_content_id", "spotify:track:4cOdK2wGLETKBW3PvgPWqT");
        attributes.put("media_duration", 213.0);
        assert "4cOdK2wGLETKBW3PvgPWqT".equals(TrackInfo.fromHaAttributes(attributes).spotifyId);
        attributes.put("media_content_type", "tvshow");
        assert TrackInfo.fromHaAttributes(attributes) == null;
    }

    static final class FakeApi implements LyricsClient.Transport {
        final Map<String, LyricsClient.Reply> replies = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        String key;

        @Override public LyricsClient.Reply get(String url, String key) throws IOException {
            asked.add(url);
            this.key = key;
            for (Map.Entry<String, LyricsClient.Reply> reply : replies.entrySet()) {
                if (url.startsWith(reply.getKey())) {
                    if (reply.getValue() == null) throw new IOException("offline");
                    return reply.getValue();
                }
            }
            return new LyricsClient.Reply(404, "", 0);
        }
    }

    static void client() {
        FakeApi api = new FakeApi();
        long[] now = {1000};
        LyricsClient client = new LyricsClient(api, () -> now[0]);
        TrackInfo spotify = new TrackInfo("4cOdK2wGLETKBW3PvgPWqT", "", "Never Gonna Give You Up", "Rick Astley", "", 213);
        assert LyricsClient.NO_KEY.equals(client.fetch(spotify, true).state) && api.asked.isEmpty();
        assert client.setKey(" sl_pk_test ") && !client.setKey("sl_pk_test");

        api.replies.put(LyricsClient.BASE + "/v1/lyrics/4cOdK2wGLETKBW3PvgPWqT", new LyricsClient.Reply(200, SYLLABLE, 0));
        LyricsClient.Result found = client.fetch(spotify, true);
        assert found.found() && "sl_pk_test".equals(api.key) && api.asked.size() == 1;
        // A second ask is answered from memory.
        assert client.fetch(spotify, true).found() && api.asked.size() == 1;

        // No ids: an exact search result of the right length.
        TrackInfo text = new TrackInfo("", "", "Never Gonna Give You Up", "Rick Astley, Someone", "", 213.2);
        api.replies.put(LyricsClient.BASE + "/v1/tracks/search", new LyricsClient.Reply(200, "{\"Body\":{\"results\":["
            + "{\"id\":\"AAAAAAAAAAAAAAAAAAAAAA\",\"tier\":\"exact\",\"duration\":300},"
            + "{\"id\":\"4cOdK2wGLETKBW3PvgPWqT\",\"tier\":\"exact\",\"duration\":213.573}]}}", 0));
        assert client.fetch(text, false).found();
        String search = api.asked.get(1);
        assert search.contains("title=Never%20Gonna%20Give%20You%20Up") && search.contains("artist=Rick%20Astley&") && search.contains("duration=213.2") : search;

        // Only a likely match: refused unless allowed.
        TrackInfo other = new TrackInfo("", "", "Another", "Band", "", 0);
        api.replies.put(LyricsClient.BASE + "/v1/tracks/search", new LyricsClient.Reply(200, "{\"Body\":{\"results\":[{\"id\":\"4cOdK2wGLETKBW3PvgPWqT\",\"tier\":\"likely\"}]}}", 0));
        assert LyricsClient.NO_MATCH.equals(client.fetch(other, false).state);
        TrackInfo other2 = new TrackInfo("", "", "Another", "Band2", "", 0);
        assert client.fetch(other2, true).found();

        // An ISRC the API cannot link falls back to a search.
        TrackInfo isrc = new TrackInfo("", "GBARL9300135", "Isrc Song", "Band", "", 0);
        int before = api.asked.size();
        assert client.fetch(isrc, true).found();
        assert api.asked.get(before).endsWith("/v1/lyrics/GBARL9300135") && api.asked.get(before + 1).contains("/v1/tracks/search");

        // A refused key, a rate limit with Retry-After and no network.
        TrackInfo refused = new TrackInfo("BBBBBBBBBBBBBBBBBBBBBB", "", "X", "Y", "", 0);
        api.replies.put(LyricsClient.BASE + "/v1/lyrics/BBBBBBBBBBBBBBBBBBBBBB", new LyricsClient.Reply(401, "", 0));
        assert LyricsClient.BAD_KEY.equals(client.fetch(refused, true).state);
        TrackInfo limited = new TrackInfo("CCCCCCCCCCCCCCCCCCCCCC", "", "X", "Y", "", 0);
        api.replies.put(LyricsClient.BASE + "/v1/lyrics/CCCCCCCCCCCCCCCCCCCCCC", new LyricsClient.Reply(429, "", 90));
        assert LyricsClient.RATE_LIMITED.equals(client.fetch(limited, true).state);
        before = api.asked.size();
        assert LyricsClient.RATE_LIMITED.equals(client.fetch(refused, true).state) && api.asked.size() == before;
        now[0] += 91_000;
        TrackInfo offline = new TrackInfo("DDDDDDDDDDDDDDDDDDDDDD", "", "X", "Y", "", 0);
        api.replies.put(LyricsClient.BASE + "/v1/lyrics/DDDDDDDDDDDDDDDDDDDDDD", null);
        assert LyricsClient.OFFLINE.equals(client.fetch(offline, true).state);

        // Missing lyrics are remembered for a while, then asked for again.
        TrackInfo missing = new TrackInfo("EEEEEEEEEEEEEEEEEEEEEE", "", "X", "Y", "", 0);
        assert LyricsClient.NONE.equals(client.fetch(missing, true).state);
        before = api.asked.size();
        assert LyricsClient.NONE.equals(client.fetch(missing, true).state) && api.asked.size() == before;
        now[0] += 16 * 60_000;
        client.fetch(missing, true);
        assert api.asked.size() == before + 1;

        // A new key forgets what the old one was told.
        client.setKey("sl_pk_other");
        before = api.asked.size();
        client.fetch(spotify, true);
        assert api.asked.size() == before + 1;
        assert LyricsClient.OK.equals(client.test());
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-6; }
}
