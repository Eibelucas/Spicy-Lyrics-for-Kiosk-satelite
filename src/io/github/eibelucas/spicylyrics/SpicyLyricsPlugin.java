// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Spicy Lyrics for Kiosk Satellite. Follows the playing track through Music Assistant or a Home Assistant media
 * player, fetches its lyrics from the Spicy Lyrics Developer API with the user's own key and draws them over Now
 * Playing in a native overlay.
 *
 * Every entry point is synchronized: plugin callbacks arrive on the KS worker, lookups finish on a network thread of
 * the plugin's own and the overlay's buttons run on the main thread.
 */
public final class SpicyLyricsPlugin implements KioskPlugin {
    static final String OVERLAY = "lyrics";
    static final String SOURCE_MA = "Music Assistant";
    static final String SOURCE_HA = "Home Assistant media player";
    static final String SHOW_AUTO = "With Now Playing";
    static final String SHOW_MANUAL = "Only by action";
    static final String LAYOUT_FULL = "Full screen";
    static final String LAYOUT_RIGHT = "Right half";
    static final String KIOSK_PLAYER = "kiosk";
    static final String SUB_PLAYER = "player";
    static final String SUB_QUEUE = "queue";

    /** Runs blocking work off the KS worker. */
    interface Runner {
        void run(Runnable task);
        void shutdown();
    }

    private final Model model = new Model();
    private Runner runner;
    private LyricsClient client;
    private PluginHost host;

    // Settings
    private String source = "";
    private String entity = "";
    private String chosenPlayer = "";
    private String showWith = SHOW_AUTO;
    private String layout = LAYOUT_FULL;
    private boolean allowLikely = true;
    private boolean tapToSeek = true;

    // Following the player
    private Map<String, Object> lastIdentity;
    private String playerId = "";
    private String queueId = "";
    private String haEntity = "";
    private boolean navigationEvents;

    // The overlay
    private boolean appearanceNowPlaying;
    private boolean viewNowPlaying;
    private boolean manual;
    private boolean dismissed;
    private boolean shown;
    private String shownLayout = "";
    private int screenWidthDp;

    /** Raised for every lookup, so an answer for a track that already ended is dropped. */
    private int generation;

    public SpicyLyricsPlugin() {}

    /** For tests: run lookups inline and answer them from a fake API. */
    SpicyLyricsPlugin(Runner runner, LyricsClient client) {
        this.runner = runner;
        this.client = client;
    }

    Model model() { return model; }

    // ---------------------------------------------------------------- lifecycle

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        if (runner == null) runner = threadRunner();
        if (client == null) client = new LyricsClient(new LyricsClient.Http("KioskSatellite-SpicyLyrics/1.0 (unofficial)"), new LyricsClient.Clock() {
            @Override public long millis() { return System.currentTimeMillis(); }
        });
        host.subscribe("sendspin.player");
        host.subscribe("screensaver.appearance");
        try {
            host.subscribe("navigation.view");
            navigationEvents = true;
        } catch (IllegalArgumentException olderHost) {
            navigationEvents = false;
        }
        read("getScreensaverAppearance", Collections.<String, Object>emptyMap(), new Reader() {
            @Override public void read(Map<?, ?> data) { appearance(data); }
        });
        if (navigationEvents) read("getNavigationView", Collections.<String, Object>emptyMap(), new Reader() {
            @Override public void read(Map<?, ?> data) { navigation(data); }
        });
        read("getDeviceInfo", Collections.<String, Object>emptyMap(), new Reader() {
            @Override public void read(Map<?, ?> data) { deviceInfo(data); }
        });
        read("getSendspinPlayer", Collections.<String, Object>emptyMap(), new Reader() {
            @Override public void read(Map<?, ?> data) { identity(data); }
        });
        apply(settings);
    }

    @Override public synchronized void configure(Map<String, Object> settings) {
        if (host != null) apply(settings);
    }

    @Override public synchronized void execute(String command, Map<String, Object> arguments) {
        if (host == null) return;
        if (command.equals("toggle")) {
            if (shown) userHide();
            else userShow();
        } else if (command.equals("show")) {
            userShow();
        } else if (command.equals("hide")) {
            userHide();
        }
    }

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (host == null) return;
        if (event.equals("ks.sendspin.player")) identity(payload);
        else if (event.equals("ks.screensaver.appearance")) appearance(payload);
        else if (event.equals("ks.navigation.view")) navigation(payload);
        else if (event.equals("ks.ma.subscription." + SUB_PLAYER)) maEvent(payload, false);
        else if (event.equals("ks.ma.subscription." + SUB_QUEUE)) maEvent(payload, true);
        else if (!haEntity.isEmpty() && event.equals("ks.ha.entity." + haEntity)) haState(payload);
        else if (event.equals("overlay.closed") && OVERLAY.equals(payload.get("key"))) {
            shown = false;
            manual = false;
            dismissed = true;
        } else if (event.equals("settings.button") && "testKey".equals(payload.get("id"))) {
            testKey(Json.string(payload.get("run")));
        } else if (event.equals("settings.choices") && "maPlayer".equals(payload.get("key"))) {
            listPlayers(Json.string(payload.get("run")));
        }
    }

    @Override public synchronized void stop() {
        host = null;
        generation++;
        shown = false;
        if (runner != null) runner.shutdown();
        runner = null;
    }

    // ---------------------------------------------------------------- settings

    private void apply(Map<String, Object> settings) {
        boolean keyChanged = client.setKey(Json.string(settings.get("apiKey")));
        allowLikely = !Boolean.FALSE.equals(settings.get("allowLikely"));
        tapToSeek = !Boolean.FALSE.equals(settings.get("tapToSeek"));
        model.offset = number(settings.get("offset"), 0.3);
        model.textScale = (float) (number(settings.get("textSize"), 100) / 100);
        model.reduced = "Reduced".equals(settings.get("animations"));
        model.controls = !Boolean.FALSE.equals(settings.get("showControls"));
        showWith = SHOW_MANUAL.equals(settings.get("showWith")) ? SHOW_MANUAL : SHOW_AUTO;
        layout = LAYOUT_RIGHT.equals(settings.get("layout")) ? LAYOUT_RIGHT : LAYOUT_FULL;
        String nextSource = SOURCE_HA.equals(settings.get("source")) ? SOURCE_HA : SOURCE_MA;
        String nextEntity = Json.string(settings.get("mediaPlayer"));
        String nextPlayer = Json.string(settings.get("maPlayer"));
        if (nextPlayer.equals(KIOSK_PLAYER)) nextPlayer = "";
        if (!nextSource.equals(source) || !nextEntity.equals(entity) || !nextPlayer.equals(chosenPlayer)) {
            switchSource(nextSource, nextEntity, nextPlayer);
        } else if (keyChanged && model.track != null) {
            model.lyrics = null;
            lookup(model.track);
        }
        model.canSeek = tapToSeek && !queueId.isEmpty();
        model.changed();
        updateOverlay();
    }

    private void switchSource(String nextSource, String nextEntity, String nextPlayer) {
        follow("");
        if (!haEntity.isEmpty()) {
            host.unsubscribe("ha.entity." + haEntity);
            haEntity = "";
        }
        trackChanged(null);
        source = nextSource;
        entity = nextEntity;
        chosenPlayer = nextPlayer;
        if (source.equals(SOURCE_MA)) {
            if (!chosenPlayer.isEmpty()) follow(chosenPlayer);
            else if (lastIdentity != null) identity(lastIdentity);
            return;
        }
        if (entity.isEmpty()) {
            status("Pick the Home Assistant media player to follow in the plugin settings.", true);
            return;
        }
        haEntity = entity;
        host.subscribe("ha.entity." + haEntity);
        final String reading = haEntity;
        read("getHaEntityState", Collections.<String, Object>singletonMap("entityId", haEntity), new Reader() {
            @Override public void read(Map<?, ?> data) { if (reading.equals(haEntity)) haState(data); }
        });
    }

    // ---------------------------------------------------------------- Music Assistant

    private void identity(Map<?, ?> player) {
        lastIdentity = new HashMap<>();
        for (Map.Entry<?, ?> entry : player.entrySet()) lastIdentity.put(String.valueOf(entry.getKey()), entry.getValue());
        if (!source.equals(SOURCE_MA) || !chosenPlayer.isEmpty()) return;
        String next = Json.string(player.get("maPlayerId"));
        follow(next);
        if (!next.isEmpty()) return;
        if (!Boolean.TRUE.equals(player.get("maConfigured"))) status("Set up Music Assistant under Settings > Media Player, or follow a Home Assistant media player instead.", true);
        else status("Waiting for Music Assistant to list this kiosk's player.", false);
    }

    /** Follow a Music Assistant player, or none. */
    private void follow(String next) {
        if (next.equals(playerId)) return;
        if (!playerId.isEmpty()) maUnsubscribe(SUB_PLAYER);
        followQueue("");
        playerId = next;
        if (playerId.isEmpty()) {
            trackChanged(null);
            return;
        }
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("events", Collections.singletonList("player_updated"));
        filter.put("objectId", playerId);
        maSubscribe(SUB_PLAYER, filter);
        readPlayer();
    }

    private void readPlayer() {
        final String reading = playerId;
        host.maCall("players/get", Collections.<String, Object>singletonMap("player_id", playerId), new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                synchronized (SpicyLyricsPlugin.this) {
                    if (host == null || !reading.equals(playerId)) return;
                    if (ok) playerData(Json.map(data));
                    else status("Couldn't read the Music Assistant player: " + error, true);
                }
            }
        });
    }

    private void playerData(Map<?, ?> player) {
        String id = Json.string(player.get("player_id"));
        if (!id.isEmpty() && !id.equals(playerId)) return;
        boolean playing = playing(player.get("playback_state") != null ? player.get("playback_state") : player.get("state"));
        Map<?, ?> media = Json.map(player.get("current_media"));
        String queue = Json.string(media.get("queue_id"));
        if (!queue.isEmpty()) {
            followQueue(queue);
            model.clock.setPlaying(playing, System.nanoTime());
            return;
        }
        // Not from a queue, such as an AirPlay or Spotify Connect stream into the player: the player knows the track.
        followQueue("");
        boolean changed = trackChanged(TrackInfo.fromPlayerMedia(media));
        position(player.get("elapsed_time"), player.get("elapsed_time_last_updated"), playing, changed);
    }

    private void followQueue(String next) {
        if (next.equals(queueId)) return;
        if (!queueId.isEmpty()) maUnsubscribe(SUB_QUEUE);
        queueId = next;
        model.canSeek = tapToSeek && !queueId.isEmpty();
        if (queueId.isEmpty()) return;
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("events", Arrays.asList("queue_updated", "queue_time_updated"));
        filter.put("objectId", queueId);
        maSubscribe(SUB_QUEUE, filter);
        readQueue();
    }

    private void readQueue() {
        final String reading = queueId;
        host.maCall("player_queues/get", Collections.<String, Object>singletonMap("queue_id", queueId), new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                synchronized (SpicyLyricsPlugin.this) {
                    if (host == null || !reading.equals(queueId)) return;
                    if (ok) queueData(Json.map(data));
                    else status("Couldn't read the Music Assistant queue: " + error, true);
                }
            }
        });
    }

    private void queueData(Map<?, ?> queue) {
        String id = Json.string(queue.get("queue_id"));
        if (!id.isEmpty() && !id.equals(queueId)) return;
        boolean playing = playing(queue.get("state"));
        boolean changed = trackChanged(TrackInfo.fromQueueItem(Json.map(queue.get("current_item"))));
        position(queue.get("elapsed_time"), queue.get("elapsed_time_last_updated"), playing, changed);
    }

    private void maEvent(Map<String, Object> payload, boolean queue) {
        Object type = payload.get("type");
        if ("event".equals(type)) {
            if (!queue) playerData(Json.map(payload.get("data")));
            else if ("queue_time_updated".equals(payload.get("event"))) {
                double seconds = Json.number(payload.get("data"));
                if (!Double.isNaN(seconds) && model.clock.known()) model.clock.sync(seconds, model.clock.playing(), System.nanoTime());
            } else {
                queueData(Json.map(payload.get("data")));
            }
        } else if ("disconnected".equals(type) && !queue) {
            status("Reconnecting to Music Assistant", false);
        } else if ("reconnected".equals(type)) {
            // Events may have been missed meanwhile.
            if (!queue && !playerId.isEmpty()) readPlayer();
            if (queue && !queueId.isEmpty()) readQueue();
        }
    }

    private void maSubscribe(final String key, Map<String, Object> filter) {
        try {
            host.maSubscribe(key, filter, new PluginHost.CommandCallback() {
                @Override public void onResult(boolean ok, Object data, String error) {
                    if (!ok) status("Music Assistant updates failed: " + error, true);
                }
            });
        } catch (RuntimeException failed) {
            status("Music Assistant updates failed: " + failed.getMessage(), true);
        }
    }

    private void maUnsubscribe(String key) {
        try { host.maUnsubscribe(key); } catch (RuntimeException ignored) {}
    }

    private void listPlayers(final String run) {
        if (run.isEmpty()) return;
        host.setupResult(run, "running", "Asking Music Assistant for its players");
        host.maCall("players/all", Collections.<String, Object>emptyMap(), new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                synchronized (SpicyLyricsPlugin.this) {
                    if (host == null) return;
                    if (!ok) {
                        host.setupResult(run, "error", "Music Assistant did not answer: " + error);
                        return;
                    }
                    List<String> ids = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    ids.add(KIOSK_PLAYER);
                    labels.add("This kiosk's own player");
                    for (Object entry : Json.list(data)) {
                        Map<?, ?> player = Json.map(entry);
                        String id = Json.string(player.get("player_id"));
                        if (id.isEmpty() || id.length() > 512 || ids.size() >= 200) continue;
                        String name = Json.string(player.get("display_name"));
                        if (name.isEmpty()) name = Json.string(player.get("name"));
                        ids.add(id);
                        labels.add(name.isEmpty() ? id : name.substring(0, Math.min(80, name.length())));
                    }
                    host.setSettingChoices("maPlayer", ids.toArray(new String[0]), labels.toArray(new String[0]));
                    host.setupResult(run, "success", "Found " + (ids.size() - 1) + " players.");
                }
            }
        });
    }

    // ---------------------------------------------------------------- Home Assistant

    private void haState(Map<?, ?> snapshot) {
        String status = Json.string(snapshot.get("status"));
        if (!status.isEmpty() && !status.equals("available")) {
            if (status.equals("missing")) status("Home Assistant has no entity " + haEntity + ".", true);
            trackChanged(null);
            return;
        }
        String state = Json.string(snapshot.get("state"));
        if (!state.equals("playing") && !state.equals("paused") && !state.equals("buffering")) {
            trackChanged(null);
            return;
        }
        Map<?, ?> attributes = Json.map(snapshot.get("attributes"));
        boolean playing = state.equals("playing");
        boolean changed = trackChanged(TrackInfo.fromHaAttributes(attributes));
        double updated = Times.epochSeconds(Json.string(attributes.get("media_position_updated_at")));
        position(attributes.get("media_position"), Double.isNaN(updated) ? null : updated, playing, changed);
    }

    // ---------------------------------------------------------------- tracks and lyrics

    /** Take a new track, or the same one again. Returns whether it changed. */
    private boolean trackChanged(TrackInfo track) {
        TrackInfo current = model.track;
        String before = current == null ? "" : current.key;
        String after = track == null ? "" : track.key;
        if (before.equals(after)) return false;
        model.track = track;
        model.lyrics = null;
        model.clock.reset();
        dismissed = false;
        generation++;
        if (track == null) {
            model.message = "Nothing is playing";
            model.changed();
            updateOverlay();
            return true;
        }
        lookup(track);
        return true;
    }

    private void lookup(final TrackInfo track) {
        if (!client.hasKey()) {
            model.message = "Add your Spicy Lyrics API key in the plugin settings";
            status("Add your Spicy Lyrics API key in the plugin settings.", true);
            model.changed();
            updateOverlay();
            return;
        }
        final int lookup = ++generation;
        final boolean likely = allowLikely;
        model.message = "Looking up lyrics…";
        model.changed();
        status("Looking up lyrics for " + track.label(), false);
        final LyricsClient api = client;
        runner.run(new Runnable() {
            @Override public void run() { delivered(lookup, track, api.fetch(track, likely)); }
        });
        updateOverlay();
    }

    private synchronized void delivered(int lookup, TrackInfo track, LyricsClient.Result result) {
        if (host == null || lookup != generation) return;
        if (result.found()) {
            model.lyrics = result.lyrics;
            model.message = "";
            status(track.label() + ": " + result.lyrics.typeName().toLowerCase() + " synced lyrics from " + provider(result.lyrics.source) + ".", false);
        } else {
            String message = describe(result.state);
            model.message = message;
            boolean error = !LyricsClient.NONE.equals(result.state) && !LyricsClient.NO_MATCH.equals(result.state);
            status(track.label() + ": " + message, error);
        }
        model.changed();
        updateOverlay();
    }

    static String describe(String state) {
        switch (state) {
            case LyricsClient.NONE: return "Spicy Lyrics has no lyrics for this track";
            case LyricsClient.NO_MATCH: return "Couldn't match this track on Spicy Lyrics";
            case LyricsClient.NO_KEY: return "Add your Spicy Lyrics API key in the plugin settings";
            case LyricsClient.BAD_KEY: return "Spicy Lyrics refused the API key";
            case LyricsClient.RATE_LIMITED: return "Spicy Lyrics asks to slow down for a moment";
            case LyricsClient.OFFLINE: return "Couldn't reach Spicy Lyrics";
            default: return "Spicy Lyrics sent an answer this plugin couldn't read";
        }
    }

    static String provider(String source) {
        if (source.equals("spicy_lyrics")) return "a Spicy Lyrics community sync";
        if (source.equals("apple_music")) return "Apple Music";
        if (source.equals("spotify")) return "Spotify";
        return source.isEmpty() ? "an unknown source" : source;
    }

    /** Move the clock to what a player reported: a position at a time, in seconds since 1970. */
    private void position(Object elapsed, Object updatedAt, boolean playing, boolean exact) {
        long now = System.nanoTime();
        double seconds = Json.number(elapsed);
        if (Double.isNaN(seconds)) {
            model.clock.setPlaying(playing, now);
            return;
        }
        double updated = Json.number(updatedAt);
        if (playing && !Double.isNaN(updated)) {
            // The report is from a moment ago. Clocks of two machines disagree a little, so trust at most ten seconds.
            seconds += Math.max(0, Math.min(10, System.currentTimeMillis() / 1000.0 - updated));
        }
        if (exact) model.clock.set(seconds, playing, now);
        else model.clock.sync(seconds, playing, now);
    }

    // ---------------------------------------------------------------- the overlay

    private void appearance(Map<?, ?> appearance) {
        appearanceNowPlaying = Boolean.TRUE.equals(appearance.get("nowPlaying"));
        nowPlayingChanged();
    }

    private void navigation(Map<?, ?> view) {
        viewNowPlaying = "now_playing".equals(view.get("view"));
        nowPlayingChanged();
    }

    private void nowPlayingChanged() {
        // A dismissal lasts as long as the Now Playing it closed.
        if (!appearanceNowPlaying && !viewNowPlaying) dismissed = false;
        updateOverlay();
    }

    private void deviceInfo(Map<?, ?> info) {
        double width = Json.number(info.get("screenWidth"));
        double density = Json.number(info.get("screenDensity"));
        if (!Double.isNaN(width) && !Double.isNaN(density) && density > 0) screenWidthDp = (int) Math.round(width / density);
        if (shown && layout.equals(LAYOUT_RIGHT)) {
            shownLayout = "";
            updateOverlay();
        }
    }

    private void userShow() {
        manual = true;
        dismissed = false;
        updateOverlay();
    }

    private void userHide() {
        manual = false;
        dismissed = true;
        updateOverlay();
    }

    boolean overlayShown() { return shown; }

    private void updateOverlay() {
        if (host == null || host.isHeadless()) return;
        boolean nowPlaying = appearanceNowPlaying || viewNowPlaying;
        boolean want = manual || (showWith.equals(SHOW_AUTO) && nowPlaying && !dismissed && model.lyrics != null);
        if (!want) {
            if (shown) host.hideOverlay(OVERLAY);
            shown = false;
            return;
        }
        if (shown && layout.equals(shownLayout)) return;
        try {
            host.showOverlay(OVERLAY, spec(), new Factory());
            shown = true;
            shownLayout = layout;
        } catch (UnsupportedOperationException olderHost) {
            status("This Kiosk Satellite version has no native overlays. Update it to see the lyrics.", true);
        } catch (IllegalStateException busy) {
            host.log("Overlay not shown: " + busy.getMessage());
        }
    }

    private OverlaySpec spec() {
        OverlaySpec spec;
        if (layout.equals(LAYOUT_RIGHT)) {
            int half = screenWidthDp > 0 ? Math.max(240, screenWidthDp / 2) : 420;
            spec = OverlaySpec.at(OverlaySpec.RIGHT).size(half, OverlaySpec.FILL);
        } else {
            spec = OverlaySpec.fullScreen();
        }
        try {
            // Over the screensaver, where Now Playing usually shows.
            spec.onTop(true);
        } catch (LinkageError olderHost) {
            // Older versions draw it over the dashboard only.
        }
        try {
            spec.enter(OverlaySpec.FADE).exit(OverlaySpec.FADE).transitionDuration(260);
        } catch (LinkageError olderHost) {
            // Shown at once there.
        }
        return spec;
    }

    private final class Factory implements OverlayFactory {
        @Override public View create(Context context, KsTheme theme) {
            return new LyricsView(context, theme, model, new Actions());
        }

        @Override public void onThemeChanged(View view, KsTheme theme) {
            if (view instanceof LyricsView) ((LyricsView) view).setTheme(theme);
        }

        @Override public void onDestroy(View view) {
            if (view instanceof LyricsView) ((LyricsView) view).release();
        }
    }

    /** The overlay's buttons. They run on the main thread, where a throw would end the app. */
    private final class Actions implements LyricsView.Actions {
        @Override public void control(String command) {
            try { SpicyLyricsPlugin.this.control(command); } catch (RuntimeException sessionEnded) {}
        }

        @Override public void seek(double seconds) {
            try { SpicyLyricsPlugin.this.seek(seconds); } catch (RuntimeException sessionEnded) {}
        }

        @Override public void close() {
            try {
                synchronized (SpicyLyricsPlugin.this) {
                    if (host != null) userHide();
                }
            } catch (RuntimeException sessionEnded) {}
        }

        @Override public void openLink(String url) {
            try {
                synchronized (SpicyLyricsPlugin.this) {
                    if (host != null) host.executeCommand("showLinkPage", Collections.<String, Object>singletonMap("url", url), ignore());
                }
            } catch (RuntimeException sessionEnded) {}
        }
    }

    synchronized void control(String command) {
        if (host == null) return;
        if (command.equals("play") || command.equals("pause")) model.clock.setPlaying(command.equals("play"), System.nanoTime());
        host.executeCommand("sendspinControl", Collections.<String, Object>singletonMap("command", command), new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                if (!ok) status("The player did not take " + command + ": " + error, true);
            }
        });
    }

    synchronized void seek(double seconds) {
        if (host == null || queueId.isEmpty() || !model.canSeek) return;
        int target = (int) Math.max(0, Math.floor(seconds));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("queue_id", queueId);
        args.put("position", target);
        model.clock.set(target, model.clock.playing(), System.nanoTime());
        host.maCall("player_queues/seek", args, new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                if (!ok) status("Music Assistant did not seek: " + error, true);
            }
        });
    }

    // ---------------------------------------------------------------- settings buttons

    private void testKey(final String run) {
        if (run.isEmpty()) return;
        host.setupResult(run, "running", "Asking Spicy Lyrics");
        final LyricsClient api = client;
        runner.run(new Runnable() {
            @Override public void run() {
                String state = api.test();
                synchronized (SpicyLyricsPlugin.this) {
                    if (host == null) return;
                    if (LyricsClient.OK.equals(state) || LyricsClient.NONE.equals(state)) host.setupResult(run, "success", "The key works.");
                    else host.setupResult(run, "error", describe(state) + ".");
                }
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private interface Reader {
        void read(Map<?, ?> data);
    }

    private void read(String command, Map<String, Object> arguments, final Reader reader) {
        host.executeCommand(command, arguments, new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {
                synchronized (SpicyLyricsPlugin.this) {
                    if (host != null && ok) reader.read(Json.map(data));
                }
            }
        });
    }

    private static PluginHost.CommandCallback ignore() {
        return new PluginHost.CommandCallback() {
            @Override public void onResult(boolean ok, Object data, String error) {}
        };
    }

    private void status(String message, boolean error) {
        PluginHost current = host;
        if (current == null) return;
        try {
            current.status(message.length() > 1000 ? message.substring(0, 1000) : message, error);
        } catch (RuntimeException sessionEnded) {}
    }

    private static boolean playing(Object state) {
        return "playing".equals(state);
    }

    private static double number(Object value, double fallback) {
        double number = Json.number(value);
        return Double.isNaN(number) ? fallback : number;
    }

    private static Runner threadRunner() {
        final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "spicy-lyrics");
                thread.setDaemon(true);
                return thread;
            }
        });
        return new Runner() {
            @Override public void run(Runnable task) {
                try {
                    executor.execute(task);
                } catch (RuntimeException shutDown) {
                    // The plugin stopped meanwhile.
                }
            }

            @Override public void shutdown() { executor.shutdownNow(); }
        };
    }
}
