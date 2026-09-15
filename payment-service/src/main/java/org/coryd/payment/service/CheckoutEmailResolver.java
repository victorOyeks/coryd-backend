package org.coryd.payment.service;

import lombok.RequiredArgsConstructor;
import org.coryd.payment.client.ProfileServiceClient;
import org.springframework.stereotype.Component;

/**
 * Paystack requires an email on /transaction/initialize, so this never returns null - every
 * checkout path (booking payment, wallet top-up) must go through it. Prefers the rider's own
 * address; failing that, a stable per-rider placeholder built from their phone, which keeps
 * Paystack's customer records one-per-rider instead of collapsing everyone into one inbox.
 */
@Component
@RequiredArgsConstructor
public class CheckoutEmailResolver {

    private static final String PLACEHOLDER_DOMAIN = "@coryd.ng";

    private final ProfileServiceClient profileServiceClient;

    public String resolve(String riderId, String suppliedEmail) {
        ProfileServiceClient.BillingContact contact = profileServiceClient.fetchBillingContact(riderId);

        if (isPresent(contact.email())) {
            return contact.email().trim();
        }
        if (isPresent(suppliedEmail)) {
            return suppliedEmail.trim();
        }

        String phoneDigits = contact.phone() == null ? null : contact.phone().replaceAll("[^0-9]", "");
        return (isPresent(phoneDigits) ? phoneDigits : riderId) + PLACEHOLDER_DOMAIN;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
