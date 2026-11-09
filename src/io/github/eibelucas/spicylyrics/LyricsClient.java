// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Talks to the Spicy Lyrics Developer API, https://developers.spicylyrics.org/docs. Every method blocks, so call it
 * from a thread of the plugin's own. Answers are kept in memory only, for a few hours, and never written to disk.
 */
final class LyricsClient {
    static final String BASE = "https://api.spicylyrics.org";
    /** Lyrics found. */
    static final String OK = "ok";
    /** The API has no lyrics for the track. */
    static final String NONE = "none";
    /** The track has no id the API takes and the search found no match that is safe to use. */
    static final String NO_MATCH = "no_match";
    static final String NO_KEY = "no_key";
    static final String BAD_KEY = "bad_key";
    static final String RATE_LIMITED = "rate_limited";
    static final String OFFLINE = "offline";
    static final String ERROR = "error";

    private static final int CACHE_LIMIT = 48;
    private static final long FOUND_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long MISSING_TTL_MS = 15 * 60 * 1000L;
    /** A search result whose length is further than this from the playing track is another recording, in seconds. */
    private static final double DURATION_SLACK = 3;

    static final class Reply {
        final int status;
        final String body;
        /** The Retry-After header in seconds, or 0. */
        final int retryAfter;

        Reply(int status, String body, int retryAfter) {
            this.status = status;
            this.body = body;
            this.retryAfter = retryAfter;
        }
    }

    interface Transport {
        Reply get(String url, String key) throws IOException;
    }

    interface Clock {
        long millis();
    }

    static final class Result {
        final String state;
        final Lyrics lyrics;

        Result(String state, Lyrics lyrics) {
            this.state = state;
            this.lyrics = lyrics;
        }

        boolean found() { return OK.equals(state) && lyrics != null; }
    }

    private static final class Cached {
        final Result result;
        final long until;

        Cached(Result result, long until) {
            this.result = result;
            this.until = until;
        }
    }

    private final Transport transport;
    private final Clock clock;
    private final Map<String, Cached> cache = new LinkedHashMap<>();
    private String key = "";
    private long pausedUntil;

    LyricsClient(Transport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /** A new key starts over: what one key was refused, another may get. */
    synchronized boolean setKey(String key) {
        String next = key == null ? "" : key.trim();
        if (next.equals(this.key)) return false;
        this.key = next;
        cache.clear();
        pausedUntil = 0;
        return true;
    }

    synchronized boolean hasKey() { return !key.isEmpty(); }

    /** The lyrics for a track, from memory when it was asked for before. */
    Result fetch(TrackInfo track, boolean allowLikely) {
        String key;
        synchronized (this) {
            key = this.key;
            if (key.isEmpty()) return new Result(NO_KEY, null);
            Cached cached = cache.get(track.key);
            if (cached != null) {
                if (cached.until > clock.millis()) return cached.result;
                cache.remove(track.key);
            }
            if (pausedUntil > clock.millis()) return new Result(RATE_LIMITED, null);
        }
        Result result = lookup(track, allowLikely, key);
        boolean keep = result.found() || NONE.equals(result.state) || NO_MATCH.equals(result.state);
        synchronized (this) {
            // An answer for a key that was replaced meanwhile says nothing about the new one.
            if (keep && key.equals(this.key)) {
                cache.put(track.key, new Cached(result, clock.millis() + (result.found() ? FOUND_TTL_MS : MISSING_TTL_MS)));
                Iterator<String> oldest = cache.keySet().iterator();
                while (cache.size() > CACHE_LIMIT && oldest.hasNext()) {
                    oldest.next();
                    oldest.remove();
                }
            }
        }
        return result;
    }

    /** One request that tells whether the key works, for the Test button. Returns OK, NONE or an error state. */
    String test() {
        String key;
        synchronized (this) { key = this.key; }
        if (key.isEmpty()) return NO_KEY;
        // The track the API's own documentation uses as its example.
        return lyrics("4cOdK2wGLETKBW3PvgPWqT", key).state;
    }

    private Result lookup(TrackInfo track, boolean allowLikely, String key) {
        String id = track.lookupId();
        if (!id.isEmpty()) {
            Result result = lyrics(id, key);
            // An ISRC names one release. A search can still find the recording under another one.
            boolean retryBySearch = NONE.equals(result.state) && track.spotifyId.isEmpty() && track.searchable();
            if (!retryBySearch) return result;
        }
        if (!track.searchable()) return new Result(NO_MATCH, null);
        StringBuilder url = new StringBuilder(BASE).append("/v1/tracks/search?title=").append(encode(track.title));
        if (!track.artist.isEmpty()) url.append("&artist=").append(encode(firstArtist(track.artist)));
        if (!track.album.isEmpty()) url.append("&album=").append(encode(track.album));
        if (track.duration > 0) url.append("&duration=").append(Math.round(track.duration * 1000) / 1000.0);
        Reply reply;
        try {
            reply = transport.get(url.toString(), key);
        } catch (IOException offline) {
            return new Result(OFFLINE, null);
        }
        String failure = failure(reply);
        if (failure != null) return new Result(NONE.equals(failure) ? NO_MATCH : failure, null);
        String found = "";
        try {
            Map<?, ?> body = Json.map(Json.map(Json.parse(reply.body)).get("Body"));
            found = pick(body, track, "exact");
            if (found.isEmpty() && allowLikely) found = pick(body, track, "likely");
        } catch (IllegalArgumentException invalid) {
            return new Result(ERROR, null);
        }
        if (found.isEmpty() || found.equals(id)) return new Result(NO_MATCH, null);
        return lyrics(found, key);
    }

    /** The id of the first search result of a tier whose length fits the playing track, or empty. */
    private static String pick(Map<?, ?> body, TrackInfo track, String tier) {
        for (Object entry : Json.list(body.get("results"))) {
            Map<?, ?> result = Json.map(entry);
            if (!tier.equals(result.get("tier"))) continue;
            double duration = Json.number(result.get("duration"));
            if (track.duration > 0 && !Double.isNaN(duration) && Math.abs(duration - track.duration) > DURATION_SLACK) continue;
            String id = Json.string(result.get("id"));
            if (id.matches("[A-Za-z0-9]{22}")) return id;
        }
        return "";
    }

    private Result lyrics(String id, String key) {
        Reply reply;
        try {
            reply = transport.get(BASE + "/v1/lyrics/" + id, key);
        } catch (IOException offline) {
            return new Result(OFFLINE, null);
        }
        String failure = failure(reply);
        if (failure != null) return new Result(failure, null);
        try {
            Lyrics lyrics = Lyrics.parse(Json.map(Json.map(Json.parse(reply.body)).get("Body")));
            return lyrics.isEmpty() ? new Result(NONE, null) : new Result(OK, lyrics);
        } catch (IllegalArgumentException invalid) {
            return new Result(ERROR, null);
        }
    }

    /** The state a reply other than 200 stands for, or null for a 200. */
    private String failure(Reply reply) {
        if (reply.status == 200) return null;
        if (reply.status == 404) return NONE;
        if (reply.status == 401 || reply.status == 403) return BAD_KEY;
        if (reply.status == 429 || reply.status == 503) {
            // Wait as long as the API asks, and at least a little, so a skipped track does not ask again at once.
            long wait = Math.max(reply.status == 429 ? 30 : 5, Math.min(reply.retryAfter, 3600));
            synchronized (this) { pausedUntil = clock.millis() + wait * 1000L; }
            return RATE_LIMITED;
        }
        return ERROR;
    }

    /** "Rick Astley, Someone Else" searches better as its first name alone. */
    private static String firstArtist(String artist) {
        int comma = artist.indexOf(", ");
        return comma > 0 ? artist.substring(0, comma) : artist;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The real transport: one HTTPS GET with the key as a bearer token. */
    static final class Http implements Transport {
        private static final int MAX_BYTES = 2 * 1024 * 1024;
        private final String userAgent;

        Http(String userAgent) { this.userAgent = userAgent; }

        @Override public Reply get(String url, String key) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(12000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", userAgent);
                int status = connection.getResponseCode();
                int retryAfter = 0;
                try {
                    String header = connection.getHeaderField("Retry-After");
                    if (header != null) retryAfter = Math.max(0, Integer.parseInt(header.trim()));
                } catch (NumberFormatException aDate) {
                    retryAfter = 60;
                }
                String body = "";
                if (status == 200) {
                    try (InputStream stream = connection.getInputStream()) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = stream.read(buffer)) > 0) {
                            if (bytes.size() + read > MAX_BYTES) throw new IOException("Response too large");
                            bytes.write(buffer, 0, read);
                        }
                        body = bytes.toString("UTF-8");
                    }
                }
                return new Reply(status, body, retryAfter);
            } finally {
                connection.disconnect();
            }
        }
    }
}
