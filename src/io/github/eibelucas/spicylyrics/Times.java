// SPDX-License-Identifier: Apache-2.0
package io.github.eibelucas.spicylyrics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ISO 8601 timestamps without java.time, which Android only has from API 26. */
final class Times {
    private static final Pattern ISO = Pattern.compile(
        "(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})?");

    private Times() {}

    /** Seconds since 1970 for a timestamp such as 2026-11-09T19:56:12.123456+00:00, or NaN. No zone means UTC. */
    static double epochSeconds(String text) {
        if (text == null) return Double.NaN;
        Matcher m = ISO.matcher(text.trim());
        if (!m.matches()) return Double.NaN;
        long year = Long.parseLong(m.group(1));
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        int hour = Integer.parseInt(m.group(4));
        int minute = Integer.parseInt(m.group(5));
        int second = Integer.parseInt(m.group(6));
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 60) return Double.NaN;
        double fraction = m.group(7) == null ? 0 : Double.parseDouble("0." + m.group(7));
        long offset = 0;
        String zone = m.group(8);
        if (zone != null && !zone.equals("Z")) {
            String digits = zone.substring(1).replace(":", "");
            offset = (Long.parseLong(digits.substring(0, 2)) * 60 + Long.parseLong(digits.substring(2, 4))) * 60;
            if (zone.charAt(0) == '-') offset = -offset;
        }
        return daysFromCivil(year, month, day) * 86400L + hour * 3600L + minute * 60L + second - offset + fraction;
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date. Howard Hinnant's algorithm. */
    static long daysFromCivil(long year, int month, int day) {
        year -= month <= 2 ? 1 : 0;
        long era = (year >= 0 ? year : year - 399) / 400;
        long yearOfEra = year - era * 400;
        long dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1;
        long dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear;
        return era * 146097 + dayOfEra - 719468;
    }
}
