# Spicy Lyrics Overlay for Kiosk Satellite

Word-by-word synced lyrics from the [Spicy Lyrics Developer API](https://developers.spicylyrics.org/) over [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite)'s Now Playing, in place of the built-in LRCLIB lyrics.

- **Syllable sync**: each word fills as it is sung and lifts a little, with a soft glow on long notes. Line-synced and plain lyrics work too.
- **Spicy Lyrics layout**: the current line centered, other lines receding, background vocals smaller under their line, duet lines on the opposite side, dots through instrumental breaks.
- **Follows your player**: the kiosk's own Music Assistant player (Sendspin), any other Music Assistant player, or any Home Assistant `media_player`, such as the Spotify integration's.
- **Tap a line to jump there** (Music Assistant queues), drag to look ahead, playback buttons, a close button.
- **Shows itself with Now Playing**, or only when you run its actions from a gesture, the drawer or Home Assistant.
- Full screen or the right half of the screen, adjustable text size and timing, and a reduced mode for slow panels like the Echo Show 5.

> Unofficial. Not affiliated with or endorsed by Spicy Lyrics, Spotify, Apple, Music Assistant or Kiosk Satellite.

## Requirements

- Kiosk Satellite **2026.11.10** or newer, with plugins enabled.
- Your own **Spicy Lyrics API key**. Every person who installs this plugin needs their own key.
- For the Music Assistant source: the Music Assistant address and token under **Settings > Media Player > Music Assistant** in Kiosk Satellite.

### Get an API key

1. Sign in at [developers.spicylyrics.org](https://developers.spicylyrics.org/dashboard) and create an application.
2. Turn on **client access** to get a publishable key (`sl_pk_…`). The kiosk is an app, not a browser, and sends no `Origin` header, so allow requests **without an origin header** for that key.
3. Paste the key into the plugin's **API key** setting and press **Test**.

A secret key (`sl_sk_…`) works too, but the Spicy Lyrics terms keep those for servers you control. If you use one, use it only on your own kiosk and never share it.

## Installation

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/Eibelucas/spicy-lyrics-for-kiosk-satelite` and choose **Preview**.
3. Review the plugin and choose **Trust and install**, then enable it from its entry row.

For testing a local build, use **Plugin Manager > Developer Tools > Install from ZIP** with the ZIP from `dist/`.

### Recommended Kiosk Satellite settings

- **Settings > Media Player > Lyrics > Enable lyrics**: off. The overlay takes their place, and Now Playing then lays out its cover and controls without the built-in lyrics panel. This matters most with the **Right half** layout.
- **Settings > Media Player > Now Playing > Launch Now Playing when music starts playing**: on, so the lyrics appear as soon as a song starts.

## Settings

| Setting | Default | What it does |
| --- | --- | --- |
| API key | empty | Your Spicy Lyrics key. Stored masked by Kiosk Satellite. |
| Test the key | button | Asks the API for one track's lyrics with the key. |
| Accept likely matches | on | For tracks without a Spotify ID, also take a search result Spicy Lyrics rates as *likely* when its length fits. Off takes *exact* matches only. |
| Follow | Music Assistant | Where the track and position come from: **Music Assistant** or a **Home Assistant media player**. |
| Music Assistant player | this kiosk | Another Music Assistant player to follow. Press Refresh to list them. |
| Media player | empty | The `media_player` entity for the Home Assistant source. |
| Lyrics timing | +0.3 s | Positive shows words earlier, negative later. |
| Show the lyrics | With Now Playing | Or **Only by action**. |
| Layout | Full screen | Or **Right half**, leaving Now Playing's cover and controls in sight. |
| Text size | 100 % | 60 to 160 %. |
| Animations | Full | **Reduced** drops the glow, the lift and the moving background and draws fewer frames. |
| Playback buttons | on | Previous, play/pause and next under the lyrics. |
| Tap a line to jump there | on | Music Assistant queues only. |

### Actions

**Show or hide lyrics**, **Show lyrics** and **Hide lyrics** can be assigned under **Gestures**, added to the drawer or exposed as Home Assistant buttons from the plugin's page. Back or the close button hides the lyrics until Now Playing closes.

## How tracks are matched

1. A **Spotify track ID** is used directly: from Music Assistant's Spotify provider mapping, a `spotify://` URI or a Home Assistant `media_content_id` like `spotify:track:…`. This is the most reliable.
2. Otherwise an **ISRC** from Music Assistant, if the track has one.
3. Otherwise a **search** by title, first artist, album and length. Only *exact* results (and *likely* ones, if allowed) whose length is within 3 seconds are used, since a live or remixed version has different timings.

Tracks without lyrics leave the built-in Now Playing alone.

## Data and privacy

The plugin sends the playing track's Spotify ID, ISRC or title, artist, album and length to `api.spicylyrics.org` with your key, and nothing else. Answers are kept in memory for up to six hours and never written to disk or passed on. Music Assistant and Home Assistant are reached through Kiosk Satellite's own connections. The plugin never sees their tokens.

## Attribution

Lyrics come from the Spicy Lyrics API. As its terms ask, the end of every lyrics scroll names the provider that answered (a Spicy Lyrics community sync, Apple Music or Spotify) and, for community syncs, credits the uploader and maker. Tap their names to open their profiles.

## Development

Needs Python 3, JDK 17 or newer and an Android SDK with a platform and build-tools.

```sh
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` runs the lyrics parser, timing, matching, API client and plugin tests on a plain JVM. Releases are built by the included GitHub Actions workflow: publish a GitHub release tagged like `v1.0.0` and it attaches the manifest, ZIP and checksum Kiosk Satellite requires.

| Path | Purpose |
| --- | --- |
| `src/io/github/eibelucas/spicylyrics/SpicyLyricsPlugin.java` | Entry point: follows the player, looks up lyrics, shows the overlay |
| `LyricsView.java` | The overlay, drawn on a canvas |
| `LyricsClient.java` | Spicy Lyrics API client with caching and rate-limit handling |
| `Lyrics.java`, `TrackInfo.java`, `PlaybackClock.java` | Lyrics model, track matching, position between player reports |
| `sdk/` | Kiosk Satellite plugin SDK, compile-time only |

## License

[Apache-2.0](LICENSE). The SDK under `sdk/` and the build tools under `tools/` come from the [Kiosk Satellite Plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) template, also Apache-2.0. Lyrics remain subject to the [Spicy Lyrics terms](https://developers.spicylyrics.org/terms) and the rights of their owners.
