package org.coryd.common.util;

/**
 * Canonicalises Nigerian mobile numbers to the local 11-digit form (0XXXXXXXXXX) that
 * identity.users.phone already stores. Riders type the same number four different ways
 * (+234..., 234..., 0..., bare 10 digits) and the column is UNIQUE, so without this the
 * same person either fails to log in or ends up with two accounts.
 */
public final class PhoneNumbers {

    private static final String NG_COUNTRY_CODE = "234";
    private static final int NG_LOCAL_LENGTH = 11;
    private static final int NG_SUBSCRIBER_LENGTH = 10;

    private PhoneNumbers() {
    }

    /** Null/blank in, null/blank out - validation stays the caller's job. */
    public static String normalize(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return phone.trim();
        }

        // 2349035911994 / +2349035911994
        if (digits.startsWith(NG_COUNTRY_CODE) && digits.length() == NG_COUNTRY_CODE.length() + NG_SUBSCRIBER_LENGTH) {
            return "0" + digits.substring(NG_COUNTRY_CODE.length());
        }
        // 9035911994
        if (digits.length() == NG_SUBSCRIBER_LENGTH) {
            return "0" + digits;
        }
        // 09035911994, and anything non-Nigerian we leave alone rather than mangle
        return digits;
    }

    /** International form Termii and other gateways expect, e.g. 2349035911994. */
    public static String toInternational(String phone) {
        String local = normalize(phone);
        if (local != null && local.length() == NG_LOCAL_LENGTH && local.startsWith("0")) {
            return NG_COUNTRY_CODE + local.substring(1);
        }
        return local;
    }
}
