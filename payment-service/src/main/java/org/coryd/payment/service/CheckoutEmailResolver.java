package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.payment.client.ProfileServiceClient;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Paystack requires an email on /transaction/initialize, so this never returns null - every
 * checkout path (booking payment, wallet top-up) must go through it. Prefers the rider's own
 * address; failing that, a stable per-rider placeholder built from their phone, which keeps
 * Paystack's customer records one-per-rider instead of collapsing everyone into one inbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutEmailResolver {

    private static final String PLACEHOLDER_DOMAIN = "@coryd.ng";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    /** RFC 2606/6761 reserved - syntactically fine but not in the root zone, so Paystack rejects them. */
    private static final Set<String> UNROUTABLE_TLDS = Set.of("test", "example", "invalid", "localhost", "local");

    private final ProfileServiceClient profileServiceClient;

    public String resolve(String riderId, String suppliedEmail) {
        ProfileServiceClient.BillingContact contact = profileServiceClient.fetchBillingContact(riderId);

        if (isUsable(contact.email())) {
            return contact.email().trim();
        }
        if (isUsable(suppliedEmail)) {
            return suppliedEmail.trim();
        }
        if (isPresent(contact.email()) || isPresent(suppliedEmail)) {
            log.warn("Rider {} has an unusable billing email - falling back to a placeholder", riderId);
        }

        String phoneDigits = contact.phone() == null ? null : contact.phone().replaceAll("[^0-9]", "");
        return (isPresent(phoneDigits) ? phoneDigits : riderId) + PLACEHOLDER_DOMAIN;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Paystack rejects the whole charge on a bad address, so a questionable one is worse than none. */
    private static boolean isUsable(String email) {
        if (!isPresent(email)) {
            return false;
        }
        String trimmed = email.trim();
        if (!EMAIL.matcher(trimmed).matches()) {
            return false;
        }
        String tld = trimmed.substring(trimmed.lastIndexOf('.') + 1).toLowerCase();
        return !UNROUTABLE_TLDS.contains(tld);
    }
}
