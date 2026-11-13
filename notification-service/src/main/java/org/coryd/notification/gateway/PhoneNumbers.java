package org.coryd.notification.gateway;

/** Phone-number formatting for the outbound providers (SMS and WhatsApp). */
public final class PhoneNumbers {

    private PhoneNumbers() {
    }

    /** Every phone number in this codebase is stored/passed around in local Nigerian format
     * (0803...) - Termii rejects that outright and needs international format with no leading
     * zero and no "+" (234803...). Converted only here, right before the API call, so nothing
     * else (storage, login, lookups) has to change format. */
    public static String toInternationalFormat(String phone) {
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 11) {
            return "234" + digits.substring(1);
        }
        return digits;
    }
}
