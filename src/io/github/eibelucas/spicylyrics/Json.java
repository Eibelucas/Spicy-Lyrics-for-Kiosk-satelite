// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader. It turns a document into maps, lists, strings, doubles, booleans and null, the same shapes
 * Kiosk Satellite hands a plugin, so one set of helpers reads both. It has no Android dependency, which keeps the
 * lyrics code testable on a plain JVM.
 */
final class Json {
    private static final int MAX_DEPTH = 64;
    private final String text;
    private int at;

    private Json(String text) { this.text = text; }

    /** Parse a whole document. Throws IllegalArgumentException for anything that is not valid JSON. */
    static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("No JSON");
        Json json = new Json(text);
        Object value = json.value(0);
        json.space();
        if (json.at != text.length()) throw json.error("Unexpected text after the value");
        return value;
    }

    static Map<?, ?> map(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : java.util.Collections.emptyMap();
    }

    static List<?> list(Object value) {
        return value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
    }

    /** A trimmed string, or an empty one for anything else. */
    static String string(Object value) {
        return value instanceof String ? ((String) value).trim() : "";
    }

    /** A finite number, or NaN for anything else. */
    static double number(Object value) {
        if (!(value instanceof Number)) return Double.NaN;
        double number = ((Number) value).doubleValue();
        return Double.isInfinite(number) ? Double.NaN : number;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("Nested too deeply");
        space();
        if (at >= text.length()) throw error("Unexpected end");
        char c = text.charAt(at);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') return string();
        if (text.startsWith("true", at)) { at += 4; return Boolean.TRUE; }
        if (text.startsWith("false", at)) { at += 5; return Boolean.FALSE; }
        if (text.startsWith("null", at)) { at += 4; return null; }
        return number();
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        at++;
        space();
        if (peek() == '}') { at++; return result; }
        while (true) {
            space();
            if (peek() != '"') throw error("Expected a key");
            String key = string();
            space();
            if (peek() != ':') throw error("Expected a colon");
            at++;
            result.put(key, value(depth + 1));
            space();
            char c = peek();
            at++;
            if (c == '}') return result;
            if (c != ',') throw error("Expected a comma or a closing brace");
        }
    }

    private List<Object> array(int depth) {
        List<Object> result = new ArrayList<>();
        at++;
        space();
        if (peek() == ']') { at++; return result; }
        while (true) {
            result.add(value(depth + 1));
            space();
            char c = peek();
            at++;
            if (c == ']') return result;
            if (c != ',') throw error("Expected a comma or a closing bracket");
        }
    }

    private String string() {
        StringBuilder result = new StringBuilder();
        at++;
        while (true) {
            if (at >= text.length()) throw error("Unterminated string");
            char c = text.charAt(at++);
            if (c == '"') return result.toString();
            if (c != '\\') { result.append(c); continue; }
            if (at >= text.length()) throw error("Unterminated escape");
            char escape = text.charAt(at++);
            switch (escape) {
                case '"': case '\\': case '/': result.append(escape); break;
                case 'b': result.append('\b'); break;
                case 'f': result.append('\f'); break;
                case 'n': result.append('\n'); break;
                case 'r': result.append('\r'); break;
                case 't': result.append('\t'); break;
                case 'u':
                    if (at + 4 > text.length()) throw error("Short unicode escape");
                    try {
                        result.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    } catch (NumberFormatException invalid) {
                        throw error("Invalid unicode escape");
                    }
                    at += 4;
                    break;
                default: throw error("Invalid escape");
            }
        }
    }

    private Double number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
        if (start == at) throw error("Unexpected character");
        try {
            return Double.valueOf(text.substring(start, at));
        } catch (NumberFormatException invalid) {
            throw error("Invalid number");
        }
    }

    private char peek() {
        if (at >= text.length()) throw error("Unexpected end");
        return text.charAt(at);
    }

    private void space() {
        while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) at++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at " + at);
    }
}
