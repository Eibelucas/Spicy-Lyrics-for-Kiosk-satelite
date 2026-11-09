// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

/**
 * Where the track is right now. A player reports its position now and then. Between two reports this clock runs on
 * by itself while music plays, so the view can ask for a position on every frame. Safe to use from any thread.
 */
final class PlaybackClock {
    /** A report further than this from the running position is a seek or a new track, in seconds. */
    static final double JUMP = 0.75;
    /** How much of a small difference one report corrects. The rest is taken as jitter of the report itself. */
    static final double PULL = 0.25;

    private double anchor;
    private long anchorNanos;
    private boolean playing;
    private boolean known;

    /** Take a report as the exact position, for a new track, a seek or a change between playing and paused. */
    synchronized void set(double position, boolean playing, long nowNanos) {
        anchor = Math.max(0, position);
        anchorNanos = nowNanos;
        this.playing = playing;
        known = true;
    }

    /**
     * Take a routine report. A position close to the running one only nudges it, so a report that arrives a little
     * early or late does not make the words stutter. Anything else is taken exactly.
     */
    synchronized void sync(double position, boolean playing, long nowNanos) {
        double running = positionAt(nowNanos);
        if (!known || playing != this.playing || Math.abs(running - position) > JUMP) {
            set(position, playing, nowNanos);
            return;
        }
        anchor = Math.max(0, running + (position - running) * PULL);
        anchorNanos = nowNanos;
    }

    /** Keep the position and change only whether it runs. */
    synchronized void setPlaying(boolean playing, long nowNanos) {
        if (!known || playing == this.playing) return;
        anchor = positionAt(nowNanos);
        anchorNanos = nowNanos;
        this.playing = playing;
    }

    synchronized void reset() {
        known = false;
        playing = false;
        anchor = 0;
    }

    /** The position in seconds, 0 until the first report. */
    synchronized double position(long nowNanos) { return positionAt(nowNanos); }

    synchronized boolean playing() { return known && playing; }

    synchronized boolean known() { return known; }

    private double positionAt(long nowNanos) {
        if (!known) return 0;
        if (!playing) return anchor;
        return anchor + Math.max(0, nowNanos - anchorNanos) / 1e9;
    }
}
