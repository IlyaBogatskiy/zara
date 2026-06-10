package com.ibdev.bot.zara.util;

/**
 * Fuzzy size comparison: "EU40" and "40" are the same size;
 * whitespace and letter case are ignored.
 *
 * @author i.bogatskii
 */
public final class Sizes {

    private Sizes() {
    }

    public static boolean equalsSize(final String actual, final String expected) {
        if (actual == null || expected == null) {
            return false;
        }

        final var a = normalize(actual);
        final var e = normalize(expected);

        if (a.startsWith("EU") && a.substring(2).equals(e)) {
            return true;
        }
        if (e.startsWith("EU") && e.substring(2).equals(a)) {
            return true;
        }

        return a.equals(e);
    }

    private static String normalize(final String s) {
        return s.replaceAll("\\s+", "").toUpperCase();
    }
}
