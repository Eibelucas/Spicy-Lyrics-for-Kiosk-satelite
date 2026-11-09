// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

/**
 * What the lyrics view draws. The plugin writes it from its worker and network threads, the view reads it on the
 * main thread every frame, so every field is volatile and replaced as a whole.
 */
final class Model {
    final PlaybackClock clock = new PlaybackClock();
    volatile TrackInfo track;
    volatile Lyrics lyrics;
    /** Shown in place of the lyrics while there are none, such as "Looking up lyrics". */
    volatile String message = "Nothing is playing";
    /** Seconds the words run ahead of the music. */
    volatile double offset = 0.3;
    volatile float textScale = 1f;
    volatile boolean reduced;
    volatile boolean controls = true;
    /** A tap on a line jumps there. Only while the player takes seeks. */
    volatile boolean canSeek;
    /** Raised whenever something the layout depends on changes. */
    volatile int version;

    double position(long nowNanos) { return clock.position(nowNanos) + offset; }

    void changed() { version++; }
}
