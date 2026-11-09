// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;
import me.jxl.kiosk.plugins.KsTheme;

/**
 * Spicy Lyrics style lyrics, drawn on a canvas: the current line centered and lit, the words of a syllable sync
 * filling as they are sung and lifting a little, the other lines receding, dots through instrumental breaks and the
 * credits the API asks for at the end of the scroll. Everything runs on the main thread.
 */
final class LyricsView extends View {
    interface Actions {
        /** play, pause, next or previous. */
        void control(String command);
        void seek(double seconds);
        void close();
        void openLink(String url);
    }

    private static final int BACKGROUND = 0xFF0B0B10;
    /** Where the current line sits, as a share of the height. */
    private static final float ANCHOR = 0.42f;
    private static final long USER_SCROLL_NANOS = 4_000_000_000L;

    private static final class Piece {
        final String text;
        final double start;
        final double end;
        float x;
        float baseline;
        float width;

        Piece(String text, double start, double end) {
            this.text = text;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Row {
        /** Null for a credit row. */
        final Lyrics.Line line;
        final List<Piece> pieces = new ArrayList<>();
        float top;
        float height;
        float size;
        float emphasis;
        String url = "";

        Row(Lyrics.Line line) { this.line = line; }
    }

    private final Model model;
    private final Actions actions;
    private KsTheme theme;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowA = new Paint();
    private final Paint glowB = new Paint();
    private final Paint topFade = new Paint();
    private final Paint bottomFade = new Paint();
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final List<Row> rows = new ArrayList<>();
    private final List<RectF> buttons = new ArrayList<>();
    private final List<String> buttonCommands = new ArrayList<>();
    private final int touchSlop;
    private Typeface bold;
    private Typeface regular;

    private Lyrics laidOut;
    private int laidVersion = -1;
    private int laidWidth = -1;
    private int laidHeight = -1;
    private float base;
    private float pad;
    private float contentHeight;
    private float scroll;
    private boolean scrollKnown;
    private long lastFrame;
    private long userScrollUntil;
    private boolean attached;
    private float downX;
    private float downY;
    private float lastY;
    private boolean dragging;
    private final RectF close = new RectF();

    private final Runnable tick = new Runnable() {
        @Override public void run() { if (attached) invalidate(); }
    };

    LyricsView(Context context, KsTheme theme, Model model, Actions actions) {
        super(context);
        this.model = model;
        this.actions = actions;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setTheme(theme);
        setClickable(true);
    }

    void setTheme(KsTheme theme) {
        this.theme = theme;
        try {
            bold = theme.typeface(700);
            regular = theme.typeface(400);
        } catch (RuntimeException | LinkageError olderHost) {
            bold = Typeface.DEFAULT_BOLD;
            regular = Typeface.DEFAULT;
        }
        glowA.setShader(null);
        laidVersion = -1;
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrame = 0;
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    void release() {
        attached = false;
        removeCallbacks(tick);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        glowA.setShader(null);
        laidVersion = -1;
    }

    // ---------------------------------------------------------------- layout

    private void layout(Lyrics lyrics, int width, int height) {
        laidOut = lyrics;
        laidVersion = model.version;
        laidWidth = width;
        laidHeight = height;
        base = Math.max(theme.px(14), Math.min(height / 11f, width / 15f) * model.textScale);
        pad = Math.max(theme.px(16), width * 0.06f);
        rows.clear();
        scrollKnown = false;
        if (lyrics == null) return;
        float maxWidth = width - 2 * pad;
        float y = 0;
        for (Lyrics.Line line : lyrics.lines) {
            Row row = new Row(line);
            if (line.interlude) {
                row.size = base;
                row.height = base * 0.9f;
                row.top = y + base * 0.3f;
                y = row.top + row.height;
                rows.add(row);
                continue;
            }
            row.size = line.background ? base * 0.62f : base;
            List<List<Piece>> words = words(line, lyrics.type == Lyrics.SYLLABLE);
            float gap = line.background ? row.size * 0.12f : base * 0.42f;
            row.top = y + gap;
            row.height = place(row, words, maxWidth, line.opposite);
            y = row.top + row.height;
            rows.add(row);
        }
        y += base * 1.4f;
        for (String[] credit : credits(lyrics)) {
            Row row = new Row(null);
            row.size = base * 0.36f;
            row.url = credit[1];
            List<List<Piece>> words = new ArrayList<>();
            for (String word : credit[0].split(" ")) {
                List<Piece> one = new ArrayList<>();
                one.add(new Piece(word, -1, -1));
                words.add(one);
            }
            row.top = y + row.size * 0.4f;
            row.height = place(row, words, maxWidth, false);
            y = row.top + row.height;
            rows.add(row);
        }
        contentHeight = y;
    }

    /** The words of a line. A word holds every syllable up to one that does not join the next. */
    private static List<List<Piece>> words(Lyrics.Line line, boolean syllables) {
        List<List<Piece>> words = new ArrayList<>();
        if (syllables && !line.syllables.isEmpty()) {
            List<Piece> word = new ArrayList<>();
            for (Lyrics.Syllable syllable : line.syllables) {
                word.add(new Piece(syllable.text, syllable.start, syllable.end));
                if (!syllable.joinNext) {
                    words.add(word);
                    word = new ArrayList<>();
                }
            }
            if (!word.isEmpty()) words.add(word);
        } else {
            for (String part : line.text.split("\\s+")) {
                if (part.isEmpty()) continue;
                List<Piece> word = new ArrayList<>();
                word.add(new Piece(part, line.start, line.end));
                words.add(word);
            }
        }
        return words;
    }

    /** Wraps the words into the width and gives every piece its place. Returns the row's height. */
    private float place(Row row, List<List<Piece>> words, float maxWidth, boolean opposite) {
        text.setTypeface(row.line == null ? regular : bold);
        text.setTextSize(row.size);
        Paint.FontMetrics metrics = text.getFontMetrics();
        float lineHeight = row.size * 1.22f;
        float space = text.measureText(" ");
        List<List<Piece>> wraps = new ArrayList<>();
        List<Float> widths = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        float x = 0;
        for (List<Piece> word : words) {
            float wordWidth = 0;
            for (Piece piece : word) {
                piece.width = text.measureText(piece.text);
                wordWidth += piece.width;
            }
            if (x > 0 && x + wordWidth > maxWidth) {
                wraps.add(current);
                widths.add(x - space);
                current = new ArrayList<>();
                x = 0;
            }
            for (Piece piece : word) {
                piece.x = x;
                x += piece.width;
                current.add(piece);
            }
            x += space;
        }
        if (!current.isEmpty()) {
            wraps.add(current);
            widths.add(x - space);
        }
        for (int i = 0; i < wraps.size(); i++) {
            float shift = pad + (opposite ? maxWidth - widths.get(i) : 0);
            for (Piece piece : wraps.get(i)) {
                piece.x += shift;
                piece.baseline = i * lineHeight - metrics.ascent;
                row.pieces.add(piece);
            }
        }
        return Math.max(1, wraps.size()) * lineHeight;
    }

    /** The lines of credit the API asks for, each with the link it gives, or an empty one. */
    private static List<String[]> credits(Lyrics lyrics) {
        List<String[]> lines = new ArrayList<>();
        String source = lyrics.source;
        if (source.equals("spicy_lyrics")) lines.add(new String[] {"Community sync on Spicy Lyrics", ""});
        else if (source.equals("apple_music")) lines.add(new String[] {"Lyrics from Apple Music, via Spicy Lyrics", ""});
        else if (source.equals("spotify")) lines.add(new String[] {"Lyrics from Spotify, via Spicy Lyrics", ""});
        else if (source.isEmpty()) lines.add(new String[] {"Lyrics via Spicy Lyrics, source unknown", ""});
        else lines.add(new String[] {"Lyrics from " + source + ", via Spicy Lyrics", ""});
        for (Lyrics.Credit credit : lyrics.credits) {
            String url = credit.url.startsWith("https://") ? credit.url : "";
            lines.add(new String[] {(credit.role.equals("maker") ? "Made by " : "Synced by ") + credit.name, url});
        }
        if (!lyrics.writers.isEmpty()) {
            StringBuilder writers = new StringBuilder("Written by ");
            for (int i = 0; i < lyrics.writers.size(); i++) writers.append(i == 0 ? "" : ", ").append(lyrics.writers.get(i));
            lines.add(new String[] {writers.toString(), ""});
        }
        return lines;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(0.25f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        int width = getWidth();
        int height = getHeight();
        boolean reduced = model.reduced;
        Lyrics lyrics = model.lyrics;
        if (lyrics != laidOut || laidVersion != model.version || laidWidth != width || laidHeight != height) {
            layout(lyrics, width, height);
        }
        drawBackground(canvas, width, height, reduced ? 0 : now / 1e9f);

        double position = model.position(now);
        boolean settled = true;
        if (lyrics == null) {
            drawMessage(canvas, width, height);
        } else {
            settled = drawLyrics(canvas, lyrics, position, dt, width, height, now);
        }
        drawEdges(canvas, width, height);
        drawHeader(canvas, width);
        if (model.controls) drawControls(canvas, width, height);
        else buttons.clear();
        drawClose(canvas, width);

        if (!attached) return;
        boolean moving = model.clock.playing() || !settled;
        if (!moving) postDelayed(tick, 250);
        else if (reduced) postDelayed(tick, 66);
        else postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas canvas, int width, int height, float seconds) {
        canvas.drawColor(BACKGROUND);
        if (glowA.getShader() == null) {
            float radius = Math.max(width, height) * 0.75f;
            glowA.setShader(new RadialGradient(0, 0, radius, withAlpha(color("primary"), 0x70), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            glowB.setShader(new RadialGradient(0, 0, radius * 0.9f, withAlpha(color("tertiary"), 0x5C), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            float edge = Math.max(1, height * 0.16f);
            topFade.setShader(new LinearGradient(0, 0, 0, edge, 0xE60B0B10, 0x000B0B10, Shader.TileMode.CLAMP));
            bottomFade.setShader(new LinearGradient(0, height - edge, 0, height, 0x000B0B10, 0xE60B0B10, Shader.TileMode.CLAMP));
        }
        matrix.setTranslate(width * (0.22f + 0.12f * (float) Math.sin(seconds * 0.13)), height * (0.28f + 0.16f * (float) Math.cos(seconds * 0.11)));
        glowA.getShader().setLocalMatrix(matrix);
        canvas.drawRect(0, 0, width, height, glowA);
        matrix.setTranslate(width * (0.78f + 0.1f * (float) Math.cos(seconds * 0.09)), height * (0.74f + 0.14f * (float) Math.sin(seconds * 0.12)));
        glowB.getShader().setLocalMatrix(matrix);
        canvas.drawRect(0, 0, width, height, glowB);
    }

    private void drawEdges(Canvas canvas, int width, int height) {
        float edge = height * 0.16f;
        canvas.drawRect(0, 0, width, edge, topFade);
        canvas.drawRect(0, height - edge, width, height, bottomFade);
    }

    private boolean drawLyrics(Canvas canvas, Lyrics lyrics, double position, float dt, int width, int height, long now) {
        boolean timed = lyrics.timed();
        int focus = timed ? lyrics.focus(position) : -1;
        float top = headerHeight();
        float bottom = height - (model.controls ? controlsHeight() : base * 0.6f);
        float origin;
        float target;
        if (timed) {
            origin = top + (bottom - top) * ANCHOR;
            Row anchor = focus >= 0 ? rows.get(focus) : firstLyricRow();
            target = anchor == null ? 0 : anchor.top + anchor.height / 2;
        } else {
            origin = top + base * 0.4f;
            target = Math.max(0, Math.min(scroll, contentHeight - (bottom - origin)));
        }
        boolean userScrolling = now < userScrollUntil;
        if (!scrollKnown) {
            scroll = target;
            scrollKnown = true;
        } else if (!userScrolling && timed) {
            float distance = target - scroll;
            if (Math.abs(distance) > height * 1.5f) scroll = target;
            else scroll += distance * Math.min(1f, dt * (model.reduced ? 12f : 5.5f));
        } else if (!timed) {
            scroll = target;
        }
        boolean settled = Math.abs(target - scroll) < 0.5f;

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float y = origin + row.top - scroll;
            if (y + row.height < 0 || y > height) {
                row.emphasis = 0;
                continue;
            }
            if (row.line == null) {
                drawCredit(canvas, row, y);
                continue;
            }
            Lyrics.Line line = row.line;
            boolean active;
            if (!timed) active = true;
            else if (line.background) active = position >= line.start && position < line.end + 0.3;
            else active = i == focus && position < line.end + 0.5;
            float goal = active ? 1f : 0f;
            row.emphasis += (goal - row.emphasis) * Math.min(1f, dt * 9f);
            if (Math.abs(row.emphasis - goal) > 0.01f) settled = false;
            if (line.interlude) {
                drawInterlude(canvas, row, y, position);
                continue;
            }
            int distance = focus < 0 ? Math.abs(i) : Math.abs(i - focus);
            float fadeOut = timed ? Math.max(0.3f, 1f - 0.11f * distance) : 1f;
            drawLine(canvas, lyrics, row, y, position, fadeOut);
        }
        return settled;
    }

    private Row firstLyricRow() {
        for (Row row : rows) if (row.line != null && !row.line.interlude) return row;
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void drawLine(Canvas canvas, Lyrics lyrics, Row row, float y, double position, float fadeOut) {
        Lyrics.Line line = row.line;
        boolean timed = lyrics.timed();
        float emphasis = timed ? row.emphasis : 1f;
        boolean full = !model.reduced;
        text.setTypeface(bold);
        text.setTextSize(row.size);
        canvas.save();
        float scale = 0.93f + 0.07f * emphasis;
        float pivot = line.opposite ? getWidth() - pad : pad;
        canvas.scale(scale, scale, pivot, y + row.height / 2);
        int idleAlpha = Math.round(255 * fadeOut * (0.34f + 0.2f * emphasis));
        boolean syllables = lyrics.type == Lyrics.SYLLABLE && emphasis > 0.02f;
        for (Piece piece : row.pieces) {
            float x = piece.x;
            float baseline = y + piece.baseline;
            if (!timed) {
                text.setShader(null);
                text.setColor(Color.argb(235, 255, 255, 255));
                canvas.drawText(piece.text, x, baseline, text);
                continue;
            }
            if (!syllables) {
                // Line timed lyrics, or a line that is not being sung: one color for all of it.
                int alpha = Math.round(idleAlpha + (255 - idleAlpha) * (lyrics.type == Lyrics.LINE ? emphasis : 0));
                text.setShader(null);
                text.setColor(Color.argb(alpha, 255, 255, 255));
                canvas.drawText(piece.text, x, baseline, text);
                continue;
            }
            double length = Math.max(0.001, piece.end - piece.start);
            float progress = (float) Math.max(0, Math.min(1, (position - piece.start) / length));
            float lift = full ? -row.size * 0.055f * easeOut(progress) * emphasis : 0;
            if (progress <= 0) {
                text.setShader(null);
                text.setColor(Color.argb(idleAlpha, 255, 255, 255));
                canvas.drawText(piece.text, x, baseline, text);
            } else if (progress >= 1) {
                text.setShader(null);
                text.setColor(Color.argb(Math.round(idleAlpha + (255 - idleAlpha) * emphasis), 255, 255, 255));
                canvas.drawText(piece.text, x, baseline + lift, text);
            } else {
                // The sung part bright, the rest dim, with a soft edge between them that runs across the syllable.
                float edge = x + piece.width * progress;
                float soft = Math.max(row.size * 0.25f, 1f);
                text.setColor(Color.WHITE);
                text.setShader(new LinearGradient(edge - soft, 0, edge + soft, 0, Color.WHITE, Color.argb(idleAlpha, 255, 255, 255), Shader.TileMode.CLAMP));
                if (full && length >= 0.45) text.setShadowLayer(row.size * 0.3f, 0, 0, Color.argb(Math.round(150 * (1 - Math.abs(progress * 2 - 1))), 255, 255, 255));
                canvas.drawText(piece.text, x, baseline + lift, text);
                text.clearShadowLayer();
                text.setShader(null);
            }
        }
        canvas.restore();
    }

    private void drawInterlude(Canvas canvas, Row row, float y, double position) {
        Lyrics.Line line = row.line;
        double length = Math.max(0.001, line.end - line.start);
        float progress = (float) Math.max(0, Math.min(1, (position - line.start) / length));
        boolean active = position >= line.start && position < line.end;
        float radius = row.size * 0.13f;
        float breath = model.reduced || !active ? 1f : 1f + 0.12f * (float) Math.sin(position * 3.2);
        float cy = y + row.height / 2;
        for (int dot = 0; dot < 3; dot++) {
            float filled = Math.max(0, Math.min(1, progress * 3 - dot));
            int alpha = active ? Math.round(70 + 185 * filled) : Math.round(40 * row.emphasis);
            if (alpha <= 0) continue;
            fill.setColor(Color.argb(alpha, 255, 255, 255));
            canvas.drawCircle(pad + radius + dot * radius * 3.2f, cy, radius * breath, fill);
        }
    }

    private void drawCredit(Canvas canvas, Row row, float y) {
        text.setTypeface(regular);
        text.setTextSize(row.size);
        text.setShader(null);
        text.setUnderlineText(!row.url.isEmpty());
        text.setColor(Color.argb(row.url.isEmpty() ? 130 : 175, 255, 255, 255));
        for (Piece piece : row.pieces) canvas.drawText(piece.text, piece.x, y + piece.baseline, text);
        text.setUnderlineText(false);
    }

    private void drawMessage(Canvas canvas, int width, int height) {
        String message = model.message;
        if (message == null || message.isEmpty()) return;
        text.setTypeface(bold);
        text.setShader(null);
        text.setTextSize(base * 0.6f);
        text.setColor(Color.argb(200, 255, 255, 255));
        float textWidth = text.measureText(message);
        if (textWidth > width - 2 * pad) {
            text.setTextSize(base * 0.6f * (width - 2 * pad) / textWidth);
            textWidth = text.measureText(message);
        }
        canvas.drawText(message, (width - textWidth) / 2, height * 0.5f, text);
    }

    private float headerHeight() { return base * 1.25f; }

    private float controlsHeight() { return Math.max(theme.px(56), base * 1.6f); }

    private void drawHeader(Canvas canvas, int width) {
        TrackInfo track = model.track;
        if (track == null) return;
        text.setTypeface(bold);
        text.setShader(null);
        text.setTextSize(base * 0.38f);
        text.setColor(Color.argb(210, 255, 255, 255));
        float maxWidth = width - 2 * pad - theme.px(48);
        String title = ellipsize(track.title, maxWidth);
        float y = theme.px(14) - text.ascent();
        canvas.drawText(title, pad, y, text);
        if (!track.artist.isEmpty()) {
            text.setTypeface(regular);
            text.setColor(Color.argb(150, 255, 255, 255));
            canvas.drawText(ellipsize(track.artist, maxWidth), pad, y + base * 0.46f, text);
        }
    }

    private String ellipsize(String value, float maxWidth) {
        if (text.measureText(value) <= maxWidth) return value;
        int end = value.length();
        while (end > 1 && text.measureText(value, 0, end) + text.measureText("…") > maxWidth) end--;
        return value.substring(0, end) + "…";
    }

    private void drawClose(Canvas canvas, int width) {
        float size = theme.px(40);
        float margin = theme.px(10);
        close.set(width - margin - size, margin, width - margin, margin + size);
        fill.setColor(Color.argb(46, 255, 255, 255));
        canvas.drawCircle(close.centerX(), close.centerY(), size / 2, fill);
        fill.setColor(Color.argb(220, 255, 255, 255));
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(theme.px(2));
        fill.setStrokeCap(Paint.Cap.ROUND);
        float arm = size * 0.18f;
        canvas.drawLine(close.centerX() - arm, close.centerY() - arm, close.centerX() + arm, close.centerY() + arm, fill);
        canvas.drawLine(close.centerX() + arm, close.centerY() - arm, close.centerX() - arm, close.centerY() + arm, fill);
        fill.setStyle(Paint.Style.FILL);
    }

    private void drawControls(Canvas canvas, int width, int height) {
        buttons.clear();
        buttonCommands.clear();
        float size = Math.max(theme.px(44), base * 1.05f);
        float gap = size * 0.55f;
        float cy = height - controlsHeight() / 2;
        String[] commands = {"previous", model.clock.playing() ? "pause" : "play", "next"};
        float x = width / 2f - size * 1.5f - gap;
        for (String command : commands) {
            RectF bounds = new RectF(x, cy - size / 2, x + size, cy + size / 2);
            buttons.add(bounds);
            buttonCommands.add(command);
            boolean main = command.equals("play") || command.equals("pause");
            fill.setColor(Color.argb(main ? 235 : 0, 255, 255, 255));
            if (main) canvas.drawCircle(bounds.centerX(), bounds.centerY(), size / 2, fill);
            drawGlyph(canvas, command, bounds, main ? BACKGROUND : Color.argb(225, 255, 255, 255));
            x += size + gap;
        }
    }

    private void drawGlyph(Canvas canvas, String command, RectF bounds, int color) {
        fill.setColor(color);
        float cx = bounds.centerX();
        float cy = bounds.centerY();
        float s = bounds.width() * 0.2f;
        path.reset();
        switch (command) {
            case "play":
                path.moveTo(cx - s * 0.7f, cy - s);
                path.lineTo(cx + s, cy);
                path.lineTo(cx - s * 0.7f, cy + s);
                path.close();
                canvas.drawPath(path, fill);
                break;
            case "pause":
                rect.set(cx - s * 0.8f, cy - s, cx - s * 0.25f, cy + s);
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, fill);
                rect.set(cx + s * 0.25f, cy - s, cx + s * 0.8f, cy + s);
                canvas.drawRoundRect(rect, s * 0.12f, s * 0.12f, fill);
                break;
            default:
                float dir = command.equals("next") ? 1 : -1;
                path.moveTo(cx - dir * s * 0.8f, cy - s);
                path.lineTo(cx + dir * s * 0.55f, cy);
                path.lineTo(cx - dir * s * 0.8f, cy + s);
                path.close();
                canvas.drawPath(path, fill);
                float barX = cx + dir * s * 0.75f;
                rect.set(Math.min(barX, barX + dir * s * 0.28f), cy - s, Math.max(barX, barX + dir * s * 0.28f), cy + s);
                canvas.drawRect(rect, fill);
        }
    }

    // ---------------------------------------------------------------- touch

    @Override public boolean onTouchEvent(MotionEvent event) {
        try {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = lastY = event.getY();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(event.getY() - downY) > touchSlop) dragging = true;
                    if (dragging) {
                        scroll -= event.getY() - lastY;
                        if (!rows.isEmpty()) scroll = Math.max(-getHeight() * 0.3f, Math.min(contentHeight, scroll));
                        userScrollUntil = System.nanoTime() + USER_SCROLL_NANOS;
                        invalidate();
                    }
                    lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) tap(downX, downY);
                    return true;
                default:
                    return true;
            }
        } catch (RuntimeException sessionEnded) {
            // Touch handlers run outside the guarded plugin callbacks. A throw here would end the app.
            return true;
        }
    }

    private void tap(float x, float y) {
        if (close.contains(x, y)) {
            actions.close();
            return;
        }
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).contains(x, y)) {
                actions.control(buttonCommands.get(i));
                invalidate();
                return;
            }
        }
        Lyrics lyrics = laidOut;
        if (lyrics == null) return;
        float top = headerHeight();
        float bottom = getHeight() - (model.controls ? controlsHeight() : base * 0.6f);
        float origin = lyrics.timed() ? top + (bottom - top) * ANCHOR : top + base * 0.4f;
        for (Row row : rows) {
            float rowTop = origin + row.top - scroll;
            if (y < rowTop || y > rowTop + row.height) continue;
            if (row.line == null) {
                if (!row.url.isEmpty()) actions.openLink(row.url);
            } else if (lyrics.timed() && model.canSeek && !row.line.interlude) {
                userScrollUntil = 0;
                actions.seek(row.line.start);
            }
            return;
        }
    }

    // ---------------------------------------------------------------- helpers

    private int color(String role) {
        try {
            return theme.color(role);
        } catch (RuntimeException | LinkageError unknown) {
            return role.equals("primary") ? 0xFF7C4DFF : 0xFFE040FB;
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static float easeOut(float t) {
        float inverse = 1 - t;
        return 1 - inverse * inverse * inverse;
    }
}
