package org.coryd.common.util;

import java.time.LocalDate;

/**
 * How the ops console shows personal identifiers by default (NDPA 2023 - docs/ops-console/privacy.md): only the last four
 * characters, the rest as bullets. The full value comes only from a deliberate, audited reveal.
 *
 * <ul>
 *   <li>NIN: always {@code "•••• 5040"} - four bullets, a space, the last four digits (only those are kept anyway).</li>
 *   <li>Licence and bank account numbers: one bullet per hidden character, then the last four - {@code "•••••••12AB"},
 *       {@code "••••••6789"}.</li>
 *   <li>Dates of birth: the year only.</li>
 * </ul>
 */
public final class PiiMask {

    public static final char BULLET = '•';
    private static final String FOUR_BULLETS = String.valueOf(BULLET).repeat(4);

    private PiiMask() {
    }

    /** "•••• 5040" from the last four digits (or a full NIN); null when there's nothing. */
    public static String nin(String ninOrLast4) {
        if (ninOrLast4 == null || ninOrLast4.isBlank()) {
            return null;
        }
        String v = ninOrLast4.trim();
        return FOUR_BULLETS + " " + v.substring(Math.max(0, v.length() - 4));
    }

    /** "•••••••12AB": a bullet for every character but the last four. Four characters or fewer: all bullets. */
    public static String trailing(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() <= 4) {
            return String.valueOf(BULLET).repeat(v.length());
        }
        return String.valueOf(BULLET).repeat(v.length() - 4) + v.substring(v.length() - 4);
    }

    /** The year of a date of birth, or null. */
    public static Integer birthYear(LocalDate dateOfBirth) {
        return dateOfBirth == null ? null : dateOfBirth.getYear();
    }

    /** The year of a date of birth given as text ("1990-01-01", "01/01/1990"...) - the first four-digit run; null if none. */
    public static Integer birthYear(String dateOfBirth) {
        if (dateOfBirth == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?<!\\d)(1[89]\\d\\d|20\\d\\d)(?!\\d)").matcher(dateOfBirth);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }
}
