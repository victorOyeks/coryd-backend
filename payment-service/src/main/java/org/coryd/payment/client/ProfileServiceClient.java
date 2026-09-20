package org.coryd.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only lookups against profile-service for display data on the Wallet feed (the rider name
 * and photo on a trip row) - same pattern and same degradation as trip-service's client of the
 * same name: a failure here blanks the name, it never fails the feed, since none of it is
 * transactional.
 */
@Slf4j
@Component
public class ProfileServiceClient {

    private final RestClient restClient;

    public ProfileServiceClient(@Value("${services.profile.url}") String profileServiceUrl,
                                 @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(profileServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor())
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    /** One call for a whole page of the wallet feed, not one per trip row - keyed by userId for easy zipping. */
    public Map<String, RiderSummaryItem> fetchRiderSummaries(List<String> riderIds) {
        if (riderIds == null || riderIds.isEmpty()) {
            return Map.of();
        }
        try {
            RiderSummaryBatchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/riders/batch").queryParam("userIds", riderIds).build())
                    .retrieve()
                    .body(RiderSummaryBatchResponse.class);
            List<RiderSummaryItem> items = response == null || response.data() == null ? List.of() : response.data();
            return items.stream().collect(Collectors.toMap(RiderSummaryItem::userId, item -> item));
        } catch (Exception e) {
            log.warn("Could not fetch rider summaries for {}: {}", riderIds, e.getMessage());
            return Map.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    /** Email/phone for the Paystack checkout. Degrades to empty rather than blocking a payment. */
    public BillingContact fetchBillingContact(String riderId) {
        try {
            BillingContactView view = restClient.get()
                    .uri("/internal/riders/{riderId}/billing-contact", riderId)
                    .retrieve()
                    .body(BillingContactView.class);
            return view == null || view.data() == null ? BillingContact.EMPTY : view.data();
        } catch (Exception e) {
            log.warn("Could not fetch billing contact for rider {}: {}", riderId, e.getMessage());
            return BillingContact.EMPTY;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BillingContactView(BillingContact data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BillingContact(String email, String phone) {
        public static final BillingContact EMPTY = new BillingContact(null, null);
    }

    public record RiderSummaryItem(String userId, String firstName, String lastName, String photoUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RiderSummaryBatchResponse(List<RiderSummaryItem> data) {
    }
}
