package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.client.TripSafetyClient;
import org.coryd.ops.dto.FirstTripStoryItem;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ops Console - First trip stories: what drivers and riders shared after their first trip, from trip-service, with names. */
@Service
@RequiredArgsConstructor
public class FirstTripStoryOpsService {

    private final TripSafetyClient tripSafetyClient;
    private final OpsPeople opsPeople;

    public PagedResponse<FirstTripStoryItem> list(String role, Boolean consent, int page, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("role", role);
        params.put("consent", consent);
        params.put("page", page);
        params.put("size", size);
        TripSafetyClient.FirstTripPage found = tripSafetyClient.get(TripSafetyClient.FirstTripPage.class, "/internal/ops/first-trip-stories", params);
        OpsPeople.Lookup people = opsPeople.lookup(
                found.content().stream().filter(r -> "DRIVER".equals(r.role())).map(TripSafetyClient.FirstTripRow::userId).toList(),
                found.content().stream().filter(r -> !"DRIVER".equals(r.role())).map(TripSafetyClient.FirstTripRow::userId).toList(),
                java.util.List.of());
        return new PagedResponse<>(found.content().stream().map(r -> new FirstTripStoryItem(r.id(), r.role(), people.person(r.userId(), r.role()),
                        r.routePostId(), TripOpsService.ref("T", r.routePostId()), r.bookingId(), TripOpsService.ref("B", r.bookingId()),
                        r.originLabel(), r.destinationLabel(), r.feeling(), r.story(), r.consentToShare(), r.completedAt(), r.submittedAt()))
                .toList(), found.page(), found.size(), found.totalElements(), found.totalPages(), found.last());
    }
}
