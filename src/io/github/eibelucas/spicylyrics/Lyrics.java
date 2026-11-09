// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * One track's lyrics as the Spicy Lyrics API returns them in the Body of GET /v1/lyrics/{id}, reduced to what the
 * view draws. Times are seconds from the start of the track.
 */
final class Lyrics {
    /** Every syllable has its own timing. */
    static final int SYLLABLE = 0;
    /** Every line has a timing. */
    static final int LINE = 1;
    /** Plain text without timings. */
    static final int STATIC = 2;
    /** A silence between two lines at least this long gets its own interlude row, in seconds. */
    static final double INTERLUDE_GAP = 4.5;

    static final class Syllable {
        final String text;
        final double start;
        final double end;
        /** True when the next syllable continues the same word, so no space follows this one. */
        final boolean joinNext;

        Syllable(String text, double start, double end, boolean joinNext) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.joinNext = joinNext;
        }
    }

    static final class Line {
        final String text;
        final double start;
        final double end;
        /** Empty unless the lyrics are syllable timed. */
        final List<Syllable> syllables;
        /** A second voice, drawn on the other side. */
        final boolean opposite;
        /** Background vocals of the line before it, drawn smaller. */
        final boolean background;
        /** An instrumental stretch between two lines, drawn as dots. */
        final boolean interlude;

        Line(String text, double start, double end, List<Syllable> syllables, boolean opposite, boolean background, boolean interlude) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.syllables = syllables;
            this.opposite = opposite;
            this.background = background;
            this.interlude = interlude;
        }
    }

    /** Someone the API asks to be credited next to a community sync. */
    static final class Credit {
        /** "uploader" or "maker". */
        final String role;
        final String name;
        final String url;

        Credit(String role, String name, String url) {
            this.role = role;
            this.name = name;
            this.url = url;
        }
    }

    final int type;
    final List<Line> lines;
    /** The catalogue that answered, such as spicy_lyrics, apple_music or spotify. Empty when the API did not say. */
    final String source;
    final List<Credit> credits;
    final List<String> writers;

    private Lyrics(int type, List<Line> lines, String source, List<Credit> credits, List<String> writers) {
        this.type = type;
        this.lines = Collections.unmodifiableList(lines);
        this.source = source;
        this.credits = Collections.unmodifiableList(credits);
        this.writers = Collections.unmodifiableList(writers);
    }

    boolean isEmpty() { return lines.isEmpty(); }

    boolean timed() { return type != STATIC; }

    /**
     * The line the view should center on at a position: the last lead line, or interlude, that has started. -1 before
     * the first one. Background vocals never take the focus from their lead.
     */
    int focus(double position) {
        int focus = -1;
        if (!timed()) return focus;
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.background) continue;
            if (line.start > position) break;
            focus = i;
        }
        return focus;
    }

    String typeName() {
        return type == SYLLABLE ? "Syllable" : type == LINE ? "Line" : "Static";
    }

    /** Reads the Body object of a lyrics response. Throws IllegalArgumentException when it is not one. */
    static Lyrics parse(Map<?, ?> body) {
        String name = Json.string(body.get("Type"));
        int type;
        if (name.equals("Syllable")) type = SYLLABLE;
        else if (name.equals("Line")) type = LINE;
        else if (name.equals("Static")) type = STATIC;
        else throw new IllegalArgumentException("Unknown lyrics type");

        List<Line> lines = new ArrayList<>();
        if (type == STATIC) {
            Object source = body.get("Lines") instanceof List ? body.get("Lines") : body.get("Content");
            for (Object item : Json.list(source)) {
                String text = Json.string(Json.map(item).get("Text"));
                if (!text.isEmpty()) lines.add(new Line(text, -1, -1, Collections.<Syllable>emptyList(), false, false, false));
            }
        } else {
            List<List<Line>> groups = new ArrayList<>();
            for (Object entry : Json.list(body.get("Content"))) {
                Map<?, ?> item = Json.map(entry);
                boolean opposite = Boolean.TRUE.equals(item.get("OppositeAligned"));
                Line lead = vocal(type == SYLLABLE ? Json.map(item.get("Lead")) : item, opposite, false);
                if (lead == null) continue;
                List<Line> group = new ArrayList<>();
                group.add(lead);
                for (Object background : Json.list(item.get("Background"))) {
                    Line voice = vocal(Json.map(background), opposite, true);
                    if (voice != null) group.add(voice);
                }
                groups.add(group);
            }
            // The API lists lines in order. Sorting by the lead keeps that order and repairs one that is not.
            Collections.sort(groups, new Comparator<List<Line>>() {
                @Override public int compare(List<Line> a, List<Line> b) { return Double.compare(a.get(0).start, b.get(0).start); }
            });
            double sungUntil = 0;
            for (List<Line> group : groups) {
                double start = Double.MAX_VALUE;
                double end = 0;
                for (Line line : group) {
                    start = Math.min(start, line.start);
                    end = Math.max(end, line.end);
                }
                if (start - sungUntil >= INTERLUDE_GAP) {
                    lines.add(new Line("", sungUntil, start, Collections.<Syllable>emptyList(), false, false, true));
                }
                lines.addAll(group);
                sungUntil = Math.max(sungUntil, end);
            }
        }

        List<Credit> credits = new ArrayList<>();
        Map<?, ?> attribution = Json.map(body.get("UploadAttribution"));
        credit(credits, "uploader", Json.map(attribution.get("Uploader")));
        credit(credits, "maker", Json.map(attribution.get("Maker")));
        List<String> writers = new ArrayList<>();
        for (Object writer : Json.list(body.get("SongWriters"))) {
            String text = Json.string(writer);
            if (!text.isEmpty() && writers.size() < 12) writers.add(text);
        }
        return new Lyrics(type, lines, Json.string(body.get("source")), credits, writers);
    }

    private static void credit(List<Credit> credits, String role, Map<?, ?> person) {
        String name = Json.string(person.get("username"));
        if (name.isEmpty()) return;
        for (Credit existing : credits) if (existing.name.equals(name)) return;
        credits.add(new Credit(role, name, Json.string(person.get("url"))));
    }

    /** One sung line, from a Lead, a Background entry or a line timed Content item. Null when it has nothing to show. */
    private static Line vocal(Map<?, ?> raw, boolean opposite, boolean background) {
        List<Syllable> syllables = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (Object entry : Json.list(raw.get("Syllables"))) {
            Map<?, ?> item = Json.map(entry);
            String text = Json.string(item.get("Text"));
            double start = Json.number(item.get("StartTime"));
            double end = Json.number(item.get("EndTime"));
            if (text.isEmpty() || Double.isNaN(start) || Double.isNaN(end) || end < start || start < 0) continue;
            boolean joinNext = Boolean.TRUE.equals(item.get("IsPartOfWord"));
            syllables.add(new Syllable(text, start, end, joinNext));
            joined.append(text);
            if (!joinNext) joined.append(' ');
        }
        double start = Json.number(raw.get("StartTime"));
        double end = Json.number(raw.get("EndTime"));
        if (!syllables.isEmpty()) {
            if (Double.isNaN(start)) start = syllables.get(0).start;
            if (Double.isNaN(end)) end = syllables.get(syllables.size() - 1).end;
        }
        String text = syllables.isEmpty() ? Json.string(raw.get("Text")) : joined.toString().trim();
        if (text.isEmpty() || Double.isNaN(start) || Double.isNaN(end) || end < start || start < 0) return null;
        return new Line(text, start, end, Collections.unmodifiableList(syllables), opposite, background, false);
    }
}
