package org.coryd.payment.service;

import org.coryd.payment.gateway.PaystackGatewayClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bank's answer to a driver's "Is this you?" lookup, kept briefly so saving the same account
 * doesn't ask Paystack again. Keyed by driver, so one driver's lookup never vouches for another's
 * save, and the name always comes from the bank - never from the client. Per instance; a miss just
 * means one more lookup.
 */
@Component
public class ResolvedAccountCache {

    static final Duration TTL = Duration.ofMinutes(10);

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void put(String driverId, String bankCode, String accountNumber, PaystackGatewayClient.ResolvedAccount account) {
        Instant now = Instant.now();
        entries.values().removeIf(e -> e.expiresAt().isBefore(now));
        entries.put(key(driverId, bankCode, accountNumber), new Entry(account, now.plus(TTL)));
    }

    public Optional<PaystackGatewayClient.ResolvedAccount> get(String driverId, String bankCode, String accountNumber) {
        Entry entry = entries.get(key(driverId, bankCode, accountNumber));
        return entry == null || entry.expiresAt().isBefore(Instant.now()) ? Optional.empty() : Optional.of(entry.account());
    }

    private static String key(String driverId, String bankCode, String accountNumber) {
        return driverId + "|" + bankCode + "|" + accountNumber;
    }

    private record Entry(PaystackGatewayClient.ResolvedAccount account, Instant expiresAt) {
    }
}
