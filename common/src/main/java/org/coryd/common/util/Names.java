package org.coryd.common.util;

/** Display-name helpers. Profiles store a full legal name; most screens only greet by first name. */
public final class Names {

    private Names() {
    }

    /** "Victor Elijah Oyekanmi" -> "Victor". Null/blank and single-word names pass through unchanged. */
    public static String firstName(String fullName) {
        if (fullName == null) {
            return null;
        }
        String trimmed = fullName.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        int firstSpace = trimmed.indexOf(' ');
        return firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    }
}
