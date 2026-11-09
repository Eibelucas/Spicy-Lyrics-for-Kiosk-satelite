// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/** The plugin against a pretend Kiosk Satellite: following Music Assistant and Home Assistant, lookups and the overlay. */
public final class PluginTest {
    static final class Host implements PluginHost {
        final Set<String> subscriptions = new HashSet<>();
        final Map<String, CommandCallback> reads = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        final List<Map<String, Object>> commandArgs = new ArrayList<>();
        final Map<String, Map<String, Object>> maSubscriptions = new HashMap<>();
        final List<String> maCommands = new ArrayList<>();
        final List<Map<String, Object>> maArgs = new ArrayList<>();
        final List<CommandCallback> maCallbacks = new ArrayList<>();
        final Map<String, String> setup = new HashMap<>();
        String[] choices;
        String overlay;
        OverlaySpec spec;
        int shows;
        String status = "";
        boolean statusError;

        public void subscribe(String event) { subscriptions.add(event); }
        public void unsubscribe(String event) { subscriptions.remove(event); }
        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            commands.add(command);
            commandArgs.add(arguments);
            reads.put(command, callback);
        }
        public void maCall(String command, Map<String, Object> args, CommandCallback callback) {
            maCommands.add(command);
            maArgs.add(args);
            maCallbacks.add(callback);
        }
        public void maSubscribe(String key, Map<String, Object> filter, CommandCallback callback) {
            assert !maSubscriptions.containsKey(key) : key;
            maSubscriptions.put(key, filter);
            callback.onResult(true, null, null);
        }
        public void maUnsubscribe(String key) { maSubscriptions.remove(key); }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            assert key.matches("[a-z][a-z0-9_]{0,39}");
            overlay = key;
            this.spec = spec;
            shows++;
        }
        public void hideOverlay(String key) { if (key.equals(overlay)) overlay = null; }
        public boolean setupResult(String run, String state, String message) { setup.put(run, state + ": " + message); return true; }
        public void setSettingChoices(String key, String[] ids, String[] labels) { choices = ids; }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { assert message.length() <= 1000; status = message; statusError = error; }

        /** The last Music Assistant call of a command. */
        int ma(String command) { return maCommands.lastIndexOf(command); }
    }

    static Map<String, Object> settings(Object... pairs) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("apiKey", "sl_pk_test");
        settings.put("source", "Music Assistant");
        settings.put("maPlayer", "");
        settings.put("mediaPlayer", "");
        settings.put("allowLikely", true);
        settings.put("offset", 0.3);
        settings.put("showWith", "With Now Playing");
        settings.put("layout", "Full screen");
        settings.put("textSize", 100.0);
        settings.put("animations", "Full");
        settings.put("showControls", true);
        settings.put("tapToSeek", true);
        for (int i = 0; i < pairs.length; i += 2) settings.put((String) pairs[i], pairs[i + 1]);
        return settings;
    }

    static Map<String, Object> map(String json) {
        @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) Json.parse(json);
        return value;
    }

    static Map<String, Object> event(String name, Object data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "event");
        payload.put("event", name);
        payload.put("data", data);
        return payload;
    }

    public static void main(String[] args) {
        musicAssistant();
        homeAssistant();
        noKey();
        System.out.println("PluginTest passed");
    }

    static void musicAssistant() {
        CoreTest.FakeApi api = new CoreTest.FakeApi();
        api.replies.put(LyricsClient.BASE + "/v1/lyrics/4cOdK2wGLETKBW3PvgPWqT", new LyricsClient.Reply(200, CoreTest.SYLLABLE, 0));
        List<Runnable> pending = new ArrayList<>();
        SpicyLyricsPlugin plugin = new SpicyLyricsPlugin(new SpicyLyricsPlugin.Runner() {
            @Override public void run(Runnable task) { pending.add(task); }
            @Override public void shutdown() {}
        }, new LyricsClient(api, System::currentTimeMillis));
        Host host = new Host();
        plugin.start(host, settings());
        assert host.subscriptions.containsAll(Arrays.asList("sendspin.player", "screensaver.appearance", "navigation.view"));
        assert host.commands.containsAll(Arrays.asList("getSendspinPlayer", "getScreensaverAppearance", "getNavigationView", "getDeviceInfo"));
        assert host.overlay == null;

        // Music Assistant names the kiosk's player: follow it and read it.
        host.reads.get("getSendspinPlayer").onResult(true, map("{\"maConfigured\":true,\"connected\":true,\"maPlayerId\":\"up_kitchen\"}"), null);
        assert "up_kitchen".equals(host.maSubscriptions.get("player").get("objectId"));
        int get = host.ma("players/get");
        assert "up_kitchen".equals(host.maArgs.get(get).get("player_id"));

        // The player plays from its queue: follow the queue.
        host.maCallbacks.get(get).onResult(true, map("{\"player_id\":\"up_kitchen\",\"playback_state\":\"playing\",\"current_media\":{\"queue_id\":\"up_kitchen\",\"title\":\"x\"}}"), null);
        assert Arrays.asList("queue_updated", "queue_time_updated").equals(host.maSubscriptions.get("queue").get("events"));
        int queue = host.ma("player_queues/get");
        double now = System.currentTimeMillis() / 1000.0;
        host.maCallbacks.get(queue).onResult(true, map("{\"queue_id\":\"up_kitchen\",\"state\":\"playing\",\"elapsed_time\":19,\"elapsed_time_last_updated\":" + (now - 1)
            + ",\"current_item\":{\"name\":\"Rick Astley - Never Gonna Give You Up\",\"media_item\":{\"media_type\":\"track\",\"name\":\"Never Gonna Give You Up\","
            + "\"artists\":[{\"name\":\"Rick Astley\"}],\"provider_mappings\":[{\"provider_domain\":\"spotify\",\"item_id\":\"4cOdK2wGLETKBW3PvgPWqT\"}]}}}"), null);
        Model model = plugin.model();
        assert "spotify:4cOdK2wGLETKBW3PvgPWqT".equals(model.track.key);
        double position = model.clock.position(System.nanoTime());
        assert position > 19.9 && position < 20.5 : position;
        assert model.canSeek && "Looking up lyrics…".equals(model.message);

        // The lookup finishes. Now Playing is not up yet, so nothing shows.
        assert pending.size() == 1;
        pending.remove(0).run();
        assert model.lyrics != null && model.lyrics.type == Lyrics.SYLLABLE && !host.statusError;
        assert host.status.contains("a Spicy Lyrics community sync") : host.status;
        assert host.overlay == null;

        // Now Playing comes up: the lyrics show over it, once.
        plugin.onEvent("ks.screensaver.appearance", map("{\"nowPlaying\":true}"));
        assert SpicyLyricsPlugin.OVERLAY.equals(host.overlay) && host.shows == 1 && host.spec.onTop();
        plugin.onEvent("ks.screensaver.appearance", map("{\"nowPlaying\":true}"));
        assert host.shows == 1;

        // Back closes them, and they stay closed for this Now Playing.
        plugin.onEvent("overlay.closed", Collections.<String, Object>singletonMap("key", SpicyLyricsPlugin.OVERLAY));
        host.overlay = null;
        plugin.onEvent("ks.screensaver.appearance", map("{\"nowPlaying\":true}"));
        assert host.overlay == null;
        // The next Now Playing shows them again.
        plugin.onEvent("ks.screensaver.appearance", map("{\"nowPlaying\":false}"));
        plugin.onEvent("ks.navigation.view", map("{\"view\":\"now_playing\"}"));
        assert host.overlay != null && host.shows == 2;

        // A seek arrives as a time update, a pause as a queue update.
        plugin.onEvent("ks.ma.subscription.queue", event("queue_time_updated", 120.0));
        assert Math.abs(model.clock.position(System.nanoTime()) - 120) < 0.5;
        plugin.onEvent("ks.ma.subscription.queue", event("queue_updated", map("{\"queue_id\":\"up_kitchen\",\"state\":\"paused\",\"elapsed_time\":121,"
            + "\"current_item\":{\"media_item\":{\"media_type\":\"track\",\"name\":\"Never Gonna Give You Up\",\"artists\":[{\"name\":\"Rick Astley\"}],"
            + "\"provider_mappings\":[{\"provider_domain\":\"spotify\",\"item_id\":\"4cOdK2wGLETKBW3PvgPWqT\"}]}}}")));
        assert !model.clock.playing() && model.lyrics != null && pending.isEmpty();

        // A tap on a line seeks the queue.
        plugin.seek(30.7);
        int seek = host.ma("player_queues/seek");
        assert "up_kitchen".equals(host.maArgs.get(seek).get("queue_id")) && Integer.valueOf(30).equals(host.maArgs.get(seek).get("position"));
        plugin.control("play");
        assert host.commands.contains("sendspinControl") && model.clock.playing();

        // The next track has no lyrics: the overlay makes way for the built-in Now Playing.
        plugin.onEvent("ks.ma.subscription.queue", event("queue_updated", map("{\"queue_id\":\"up_kitchen\",\"state\":\"playing\",\"elapsed_time\":0,"
            + "\"current_item\":{\"media_item\":{\"media_type\":\"track\",\"name\":\"Unknown\",\"artists\":[{\"name\":\"Nobody\"}],"
            + "\"provider_mappings\":[{\"provider_domain\":\"spotify\",\"item_id\":\"ZZZZZZZZZZZZZZZZZZZZZZ\"}]}}}")));
        assert host.overlay == null && model.lyrics == null;
        pending.remove(0).run();
        assert "Spicy Lyrics has no lyrics for this track".equals(model.message) && !host.statusError;

        // An answer for a track that already ended is dropped.
        plugin.onEvent("ks.ma.subscription.queue", event("queue_updated", map("{\"queue_id\":\"up_kitchen\",\"state\":\"playing\",\"elapsed_time\":0,"
            + "\"current_item\":{\"media_item\":{\"media_type\":\"track\",\"name\":\"Never Gonna Give You Up\",\"artists\":[{\"name\":\"Rick Astley\"}],"
            + "\"provider_mappings\":[{\"provider_domain\":\"spotify\",\"item_id\":\"4cOdK2wGLETKBW3PvgPWqT\"}]}}}")));
        Runnable stale = pending.remove(0);
        plugin.onEvent("ks.ma.subscription.queue", event("queue_updated", map("{\"queue_id\":\"up_kitchen\",\"state\":\"idle\",\"current_item\":null}")));
        stale.run();
        assert model.track == null && model.lyrics == null && host.overlay == null;

        // Actions show and hide on demand, even without lyrics.
        plugin.execute("toggle", Collections.<String, Object>emptyMap());
        assert host.overlay != null;
        plugin.execute("toggle", Collections.<String, Object>emptyMap());
        assert host.overlay == null;

        // The right half layout replaces the overlay with a narrower one.
        host.reads.get("getDeviceInfo").onResult(true, map("{\"screenWidth\":960,\"screenDensity\":1.0}"), null);
        plugin.execute("show", Collections.<String, Object>emptyMap());
        plugin.configure(settings("layout", "Right half"));
        assert host.spec.width() == 480 && OverlaySpec.RIGHT.equals(host.spec.anchor());

        // Picking another Music Assistant player lists them and follows the pick.
        plugin.onEvent("settings.choices", map("{\"key\":\"maPlayer\",\"run\":\"r1\"}"));
        host.maCallbacks.get(host.ma("players/all")).onResult(true, Json.parse("[{\"player_id\":\"media_player.wohnzimmer\",\"display_name\":\"Wohnzimmer\"}]"), null);
        assert Arrays.asList(host.choices).equals(Arrays.asList("kiosk", "media_player.wohnzimmer")) && host.setup.get("r1").startsWith("success");
        plugin.configure(settings("layout", "Right half", "maPlayer", "media_player.wohnzimmer"));
        assert "media_player.wohnzimmer".equals(host.maSubscriptions.get("player").get("objectId")) && !host.maSubscriptions.containsKey("queue");

        // The Test button.
        plugin.onEvent("settings.button", map("{\"id\":\"testKey\",\"run\":\"r2\"}"));
        pending.remove(0).run();
        assert host.setup.get("r2").startsWith("success") : host.setup.get("r2");

        plugin.stop();
        plugin.onEvent("ks.screensaver.appearance", map("{\"nowPlaying\":true}"));
    }

    static void homeAssistant() {
        CoreTest.FakeApi api = new CoreTest.FakeApi();
        api.replies.put(LyricsClient.BASE + "/v1/lyrics/4cOdK2wGLETKBW3PvgPWqT", new LyricsClient.Reply(200, CoreTest.SYLLABLE, 0));
        SpicyLyricsPlugin plugin = new SpicyLyricsPlugin(new SpicyLyricsPlugin.Runner() {
            @Override public void run(Runnable task) { task.run(); }
            @Override public void shutdown() {}
        }, new LyricsClient(api, System::currentTimeMillis));
        Host host = new Host();
        plugin.start(host, settings("source", "Home Assistant media player", "mediaPlayer", "media_player.spotify_luca"));
        assert host.subscriptions.contains("ha.entity.media_player.spotify_luca") && host.maSubscriptions.isEmpty();
        assert "media_player.spotify_luca".equals(host.commandArgs.get(host.commands.indexOf("getHaEntityState")).get("entityId"));
        // Music Assistant's player is ignored while following Home Assistant.
        host.reads.get("getSendspinPlayer").onResult(true, map("{\"maPlayerId\":\"up_kitchen\"}"), null);
        assert host.maSubscriptions.isEmpty();

        String updated = "2026-11-09T19:00:00+00:00";
        double epoch = Times.epochSeconds(updated);
        host.reads.get("getHaEntityState").onResult(true, map("{\"status\":\"available\",\"state\":\"paused\",\"attributes\":{"
            + "\"media_title\":\"Never Gonna Give You Up\",\"media_artist\":\"Rick Astley\",\"media_content_id\":\"spotify:track:4cOdK2wGLETKBW3PvgPWqT\","
            + "\"media_duration\":213,\"media_position\":42,\"media_position_updated_at\":\"" + updated + "\"}}"), null);
        Model model = plugin.model();
        assert model.lyrics != null && !model.clock.playing() && Math.abs(model.clock.position(System.nanoTime()) - 42) < 1e-6;
        assert epoch > 0 && !model.canSeek;

        plugin.onEvent("ks.ha.entity.media_player.spotify_luca", map("{\"status\":\"available\",\"state\":\"idle\",\"attributes\":{}}"));
        assert model.track == null;

        // Switching back to Music Assistant lets go of the entity.
        plugin.configure(settings());
        assert !host.subscriptions.contains("ha.entity.media_player.spotify_luca") && host.maSubscriptions.containsKey("player");
    }

    static void noKey() {
        SpicyLyricsPlugin plugin = new SpicyLyricsPlugin(new SpicyLyricsPlugin.Runner() {
            @Override public void run(Runnable task) { task.run(); }
            @Override public void shutdown() {}
        }, new LyricsClient(new CoreTest.FakeApi(), System::currentTimeMillis));
        Host host = new Host();
        plugin.start(host, settings("apiKey", ""));
        host.reads.get("getSendspinPlayer").onResult(true, map("{\"maConfigured\":true,\"maPlayerId\":\"up_kitchen\"}"), null);
        host.maCallbacks.get(host.ma("players/get")).onResult(true, map("{\"player_id\":\"up_kitchen\",\"playback_state\":\"playing\",\"current_media\":{\"title\":\"Song\",\"artist\":\"Band\",\"uri\":\"airplay://x\"},\"elapsed_time\":3}"), null);
        assert host.statusError && host.status.contains("API key") : host.status;
        assert plugin.model().track != null && !host.maSubscriptions.containsKey("queue");
        // A key entered later looks the playing track up right away.
        plugin.configure(settings());
        assert "Couldn't match this track on Spicy Lyrics".equals(plugin.model().message) : plugin.model().message;
    }
}
