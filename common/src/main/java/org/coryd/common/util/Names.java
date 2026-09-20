package org.coryd.common.util;

/**
 * Names are stored as separate first/last fields so identity checks (Prembly, FRSC) can match
 * a surname on its own. These helpers cover the two directions that still need a single string:
 * joining for display, and splitting a legacy one-field name during migration or seeding.
 */
public final class Names {

    private Names() {
    }

    /** "Victor", "Oyekanmi" -> "Victor Oyekanmi". Tolerates either side being null or blank. */
    public static String join(String firstName, String lastName) {
        String first = trimToNull(firstName);
        String last = trimToNull(lastName);
        if (first == null) {
            return last;
        }
        return last == null ? first : first + " " + last;
    }

    /** "Victor Elijah Oyekanmi" -> "Victor". Null/blank and single-word names pass through unchanged. */
    public static String firstName(String fullName) {
        String trimmed = trimToNull(fullName);
        if (trimmed == null) {
            return fullName == null ? null : fullName.trim();
        }
        int firstSpace = trimmed.indexOf(' ');
        return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    }

    /**
     * "Victor Elijah Oyekanmi" -> "Oyekanmi". Takes the last word rather than everything after the
     * first, because the surname is what an identity check compares against - a middle name in the
     * middle of the string would never match. Single-word names have no surname, so this is null.
     */
    public static String lastName(String fullName) {
        String trimmed = trimToNull(fullName);
        if (trimmed == null) {
            return null;
        }
        int lastSpace = trimmed.lastIndexOf(' ');
        return lastSpace < 0 ? null : trimmed.substring(lastSpace + 1);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
