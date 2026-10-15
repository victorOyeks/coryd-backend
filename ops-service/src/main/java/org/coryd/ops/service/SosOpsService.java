package org.coryd.ops.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.PagedResponse;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.TripServiceClient;
import org.coryd.ops.dto.SosListItem;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Ops Console - every SOS, newest first. trip-service has the incidents; names and phones come from identity-service in one call per page. */
@Service
@RequiredArgsConstructor
public class SosOpsService {

    private final TripServiceClient tripServiceClient;
    private final IdentityServiceClient identityServiceClient;

    public PagedResponse<SosListItem> sos(Pageable pageable) {
        TripServiceClient.SosPage page = tripServiceClient.sos(pageable.getPageNumber(), pageable.getPageSize());
        List<String> userIds = page.content().stream()
                .flatMap(s -> Stream.of(s.triggeredBy(), s.driverId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<String, IdentityServiceClient.Contact> contacts = identityServiceClient.contacts(userIds);

        List<SosListItem> items = page.content().stream().map(s -> {
            IdentityServiceClient.Contact caller = contacts.get(s.triggeredBy());
            IdentityServiceClient.Contact driver = s.driverId() == null ? null : contacts.get(s.driverId());
            return new SosListItem(s.id(), s.triggeredAt(), s.triggeredByRole(), s.triggeredBy(),
                    caller == null ? null : caller.fullName(), caller == null ? null : caller.phone(),
                    s.driverId(), driver == null ? null : driver.fullName(), driver == null ? null : driver.phone(),
                    s.bookingId(), s.routePostId(), s.originLabel(), s.destinationLabel(), s.routeStatus(),
                    s.latitude(), s.longitude(), s.opsNotifiedAt());
        }).toList();
        return new PagedResponse<>(items, page.page(), page.size(), page.totalElements(), page.totalPages(), page.last());
    }
}
