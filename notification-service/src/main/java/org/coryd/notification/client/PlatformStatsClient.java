package org.coryd.notification.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.InternalApiKeyInterceptor;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.SystemAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Pulls platform stats from the services that own the data. Calls originate from a scheduler,
 * so they carry a minted system token rather than a forwarded user one. A service that is down
 * degrades to zeroes so one outage does not cost you the whole report.
 */
@Slf4j
@Component
public class PlatformStatsClient {

    private final RestClient trip;
    private final RestClient payment;
    private final RestClient identity;
    private final RestClient profile;

    public PlatformStatsClient(@Value("${services.trip.url}") String tripUrl,
                               @Value("${services.payment.url}") String paymentUrl,
                               @Value("${services.identity.url}") String identityUrl,
                               @Value("${services.profile.url}") String profileUrl,
                               @Value("${services.internal.api-key}") String internalApiKey,
                               JwtUtil jwtUtil) {
        this.trip = build(tripUrl, internalApiKey, jwtUtil);
        this.payment = build(paymentUrl, internalApiKey, jwtUtil);
        this.identity = build(identityUrl, internalApiKey, jwtUtil);
        this.profile = build(profileUrl, internalApiKey, jwtUtil);
    }

    private static RestClient build(String baseUrl, String internalApiKey, JwtUtil jwtUtil) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    public TripStats tripStats(Instant from, Instant to) {
        return fetch("trip", trip, from, to, TripStatsView.class, TripStats.EMPTY);
    }

    public PaymentStats paymentStats(Instant from, Instant to) {
        return fetch("payment", payment, from, to, PaymentStatsView.class, PaymentStats.EMPTY);
    }

    public IdentityStats identityStats(Instant from, Instant to) {
        return fetch("identity", identity, from, to, IdentityStatsView.class, IdentityStats.EMPTY);
    }

    public ProfileStats profileStats() {
        try {
            ProfileStatsView view = profile.get()
                    .uri("/internal/stats")
                    .retrieve()
                    .body(ProfileStatsView.class);
            return view == null || view.data() == null ? ProfileStats.EMPTY : view.data();
        } catch (Exception e) {
            log.error("Could not fetch profile stats: {}", e.getMessage());
            return ProfileStats.EMPTY;
        }
    }

    private <V extends StatsView<T>, T> T fetch(String name, RestClient client, Instant from, Instant to,
                                                Class<V> viewType, T fallback) {
        try {
            V view = client.get()
                    .uri(uri -> uri.path("/internal/stats")
                            .queryParam("from", from.toString())
                            .queryParam("to", to.toString())
                            .build())
                    .retrieve()
                    .body(viewType);
            return view == null || view.data() == null ? fallback : view.data();
        } catch (Exception e) {
            log.error("Could not fetch {} stats for {}..{}: {}", name, from, to, e.getMessage());
            return fallback;
        }
    }

    private interface StatsView<T> {
        T data();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TripStatsView(TripStats data) implements StatsView<TripStats> {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentStatsView(PaymentStats data) implements StatsView<PaymentStats> {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IdentityStatsView(IdentityStats data) implements StatsView<IdentityStats> {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProfileStatsView(ProfileStats data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripStats(
            long routesPosted,
            long routesCompleted,
            long routesCancelled,
            long seatsOffered,
            long activeDrivers,
            long bookingsConfirmed,
            long bookingsCompleted,
            long bookingsCancelledRider,
            long bookingsCancelledDriver,
            long noShows,
            long seatsBooked,
            long distinctRiders,
            long repeatRiders,
            List<TopRoute> topRoutes) {

        public static final TripStats EMPTY =
                new TripStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());

        public List<TopRoute> topRoutes() {
            return topRoutes == null ? List.of() : topRoutes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TopRoute(String origin, String destination, long seats, BigDecimal grossFare) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentStats(
            BigDecimal grossFares,
            BigDecimal grossViaCard,
            BigDecimal grossViaWallet,
            BigDecimal commission,
            BigDecimal creditedToDrivers,
            long refundCount,
            BigDecimal refundAmount,
            long payoutCount,
            BigDecimal payoutAmount,
            BigDecimal unsettledDriverBalance) {

        public static final PaymentStats EMPTY = new PaymentStats(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IdentityStats(long newRiders, long newDrivers, long totalRiders, long totalDrivers) {
        public static final IdentityStats EMPTY = new IdentityStats(0, 0, 0, 0);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfileStats(long approvedDrivers, long awaitingApproval, Instant oldestPendingAt) {
        public static final ProfileStats EMPTY = new ProfileStats(0, 0, null);
    }
}
