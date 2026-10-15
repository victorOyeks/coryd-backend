package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsSosItem;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SosIncident;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SosIncidentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Every SOS for the Ops Console, newest first, with the route it happened on - one route lookup per page. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpsSosQueryService {

    private final SosIncidentRepository sosIncidentRepository;
    private final RoutePostRepository routePostRepository;

    public PagedResponse<OpsSosItem> sos(Pageable pageable) {
        Page<SosIncident> page = sosIncidentRepository.findAllByOrderByCreatedAtDesc(pageable);
        Map<String, RoutePost> routes = routePostRepository.findAllById(page.getContent().stream()
                        .map(SosIncident::getRoutePostId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(RoutePost::getId, Function.identity()));
        return PagedResponse.from(page.map(s -> {
            RoutePost route = s.getRoutePostId() == null ? null : routes.get(s.getRoutePostId());
            return new OpsSosItem(s.getId(), s.getCreatedAt(), s.getTriggeredBy(), s.getTriggeredByRole(), s.getBookingId(),
                    s.getRoutePostId(), route == null ? null : route.getDriverId(),
                    route == null ? null : route.getOriginLabel(), route == null ? null : route.getDestinationLabel(),
                    route == null ? null : route.getStatus(), s.getLatitude(), s.getLongitude(), s.getOpsNotifiedAt());
        }));
    }
}
