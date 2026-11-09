// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What is known about the playing track, from Music Assistant or from a Home Assistant media player. */
final class TrackInfo {
    private static final Pattern SPOTIFY_ID = Pattern.compile("[A-Za-z0-9]{22}");
    private static final Pattern SPOTIFY_URI = Pattern.compile("^(?:spotify[^:/]*://track/|spotify:track:|https?://open\\.spotify\\.com/(?:intl-[a-z]+/)?track/)([A-Za-z0-9]{22})(?:[?#/].*)?$");
    private static final Pattern ISRC = Pattern.compile("[A-Za-z]{2}[A-Za-z0-9]{3}[0-9]{7}");

    /** Identifies the track for as long as it plays. Two tracks with the same key share their lyrics. */
    final String key;
    /** Spotify track id, or empty. */
    final String spotifyId;
    /** ISRC, or empty. */
    final String isrc;
    final String title;
    final String artist;
    final String album;
    /** Seconds, or 0 when unknown. */
    final double duration;

    TrackInfo(String spotifyId, String isrc, String title, String artist, String album, double duration) {
        this.spotifyId = spotifyId;
        this.isrc = isrc;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.duration = duration > 0 ? duration : 0;
        if (!spotifyId.isEmpty()) key = "spotify:" + spotifyId;
        else if (!isrc.isEmpty()) key = "isrc:" + isrc.toUpperCase(Locale.ROOT);
        else key = "text:" + artist.toLowerCase(Locale.ROOT) + "|" + title.toLowerCase(Locale.ROOT) + "|" + Math.round(this.duration);
    }

    /** The id GET /v1/lyrics/{id} takes directly: the Spotify id, else the ISRC, else empty. */
    String lookupId() { return !spotifyId.isEmpty() ? spotifyId : isrc; }

    boolean searchable() { return !title.isEmpty(); }

    /** "Title · Artist", for status lines. */
    String label() { return artist.isEmpty() ? title : title + " · " + artist; }

    /** A Music Assistant queue item, the current_item of a player queue. Null when it names no track. */
    static TrackInfo fromQueueItem(Map<?, ?> item) {
        if (item.isEmpty()) return null;
        Map<?, ?> media = Json.map(item.get("media_item"));
        String mediaType = Json.string(media.get("media_type"));
        if (!mediaType.isEmpty() && !mediaType.equals("track")) return null;
        String title = Json.string(media.get("name"));
        String artist = "";
        for (Object entry : Json.list(media.get("artists"))) {
            String name = Json.string(Json.map(entry).get("name"));
            if (name.isEmpty()) continue;
            artist = artist.isEmpty() ? name : artist + ", " + name;
        }
        if (title.isEmpty()) {
            // Without a library item the queue item's name is all there is, usually "Artist - Title".
            String name = Json.string(item.get("name"));
            int dash = name.indexOf(" - ");
            if (dash > 0) {
                artist = name.substring(0, dash).trim();
                title = name.substring(dash + 3).trim();
            } else {
                title = name;
            }
        }
        if (title.isEmpty()) return null;
        String spotify = "";
        for (Object entry : Json.list(media.get("provider_mappings"))) {
            Map<?, ?> mapping = Json.map(entry);
            String id = Json.string(mapping.get("item_id"));
            if (Json.string(mapping.get("provider_domain")).startsWith("spotify") && SPOTIFY_ID.matcher(id).matches()) {
                spotify = id;
                break;
            }
        }
        if (spotify.isEmpty()) spotify = spotifyId(Json.string(media.get("uri")));
        String isrc = "";
        Object ids = media.get("external_ids");
        if (ids instanceof Map) {
            isrc = Json.string(((Map<?, ?>) ids).get("isrc"));
        } else {
            // Music Assistant sends pairs: [["isrc", "GBARL9300135"], ["barcode", "…"]].
            for (Object entry : Json.list(ids)) {
                List<?> pair = Json.list(entry);
                if (pair.size() == 2 && "isrc".equals(pair.get(0))) isrc = Json.string(pair.get(1));
            }
        }
        if (!ISRC.matcher(isrc).matches()) isrc = "";
        double duration = Json.number(media.get("duration"));
        if (Double.isNaN(duration) || duration <= 0) duration = Json.number(item.get("duration"));
        return new TrackInfo(spotify, isrc, title, artist, Json.string(Json.map(media.get("album")).get("name")), Double.isNaN(duration) ? 0 : duration);
    }

    /** The current_media of a Music Assistant player, for sources that keep no queue. Null when it names no track. */
    static TrackInfo fromPlayerMedia(Map<?, ?> media) {
        String title = Json.string(media.get("title"));
        if (title.isEmpty()) return null;
        double duration = Json.number(media.get("duration"));
        return new TrackInfo(spotifyId(Json.string(media.get("uri"))), "", title, Json.string(media.get("artist")), Json.string(media.get("album")), Double.isNaN(duration) ? 0 : duration);
    }

    /** The attributes of a Home Assistant media_player entity. Null when they name no track. */
    static TrackInfo fromHaAttributes(Map<?, ?> attributes) {
        String title = Json.string(attributes.get("media_title"));
        if (title.isEmpty()) return null;
        String type = Json.string(attributes.get("media_content_type"));
        // A television or a video has a title too, and nothing to sing along with.
        if (type.equals("tvshow") || type.equals("movie") || type.equals("video") || type.equals("episode") || type.equals("channel")) return null;
        double duration = Json.number(attributes.get("media_duration"));
        return new TrackInfo(spotifyId(Json.string(attributes.get("media_content_id"))), "", title, Json.string(attributes.get("media_artist")), Json.string(attributes.get("media_album_name")), Double.isNaN(duration) ? 0 : duration);
    }

    /** The track id inside a Spotify URI or link, or empty. */
    static String spotifyId(String uri) {
        Matcher matcher = SPOTIFY_URI.matcher(uri);
        return matcher.matches() ? matcher.group(1) : "";
    }
}
