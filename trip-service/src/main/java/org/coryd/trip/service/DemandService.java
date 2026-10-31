package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverDemandAlertEvent;
import org.coryd.trip.client.PaymentServiceClient;
import org.coryd.trip.config.DemandProperties;
import org.coryd.trip.dto.DemandDayType;
import org.coryd.trip.dto.DemandRelevance;
import org.coryd.trip.dto.DrivingPreferences;
import org.coryd.trip.dto.RouteDemandItem;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.DriverDrivingPreference;
import org.coryd.trip.entity.NotifyMeRequest;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.RouteSearch;
import org.coryd.trip.repository.DemandAlertLogRepository;
import org.coryd.trip.repository.DriverDrivingPreferenceRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteSearchRepository;
import org.coryd.trip.util.GeoMath;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rider demand: every search and every "Notify me" is grouped into routes - start area, end area,
 * weekdays or weekends - and, within a route, into the time window most riders want (riders pick
 * 6:50 and 7:10, not one clock hour). Drivers see totals only, never riders, and only from a minimum
 * number of different riders. A driver is pushed only about demand that fits the trips they drive.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandService {

    private static final List<RouteStatus> UPCOMING = List.of(RouteStatus.PUBLISHED, RouteStatus.FULL);
    private static final int DAY_MINUTES = 24 * 60;
    /** "Mornings" and "evenings" on the usual-route setting, in minutes after midnight. */
    private static final int[] MORNINGS = {5 * 60, 11 * 60 - 1};
    private static final int[] EVENINGS = {15 * 60, 21 * 60 - 1};

    private final RouteSearchRepository routeSearchRepository;
    private final NotifyMeRequestRepository notifyMeRequestRepository;
    private final RoutePostRepository routePostRepository;
    private final DriverDrivingPreferenceRepository preferenceRepository;
    private final DriverVerificationCacheRepository driverVerificationCacheRepository;
    private final DemandAlertLogRepository demandAlertLogRepository;
    private final DemandProperties properties;
    private final PaymentServiceClient paymentServiceClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RouteMatcher routeMatcher;

    /** Never lets a demand-logging problem break the search itself - no transaction of its own here, so a
     * failed insert surfaces inside the try rather than at a commit after it. */
    public void recordSearch(String riderId, SearchRequest request, int resultCount) {
        try {
            ZonedDateTime when = GeoMath.lagos(request.desiredDepartureTime());
            routeSearchRepository.save(RouteSearch.builder()
                    .riderId(riderId)
                    .originLat(request.originLat())
                    .originLng(request.originLng())
                    .destinationLat(request.destinationLat())
                    .destinationLng(request.destinationLng())
                    .originLabel(label(request.originLabel()))
                    .destinationLabel(label(request.destinationLabel()))
                    .originCell(GeoMath.cell(request.originLat(), request.originLng(), properties.getCellDegrees()))
                    .destinationCell(GeoMath.cell(request.destinationLat(), request.destinationLng(), properties.getCellDegrees()))
                    .localMinutes(when.getHour() * 60 + when.getMinute())
                    .weekend(GeoMath.isWeekend(when.getDayOfWeek()))
                    .resultCount(resultCount)
                    .desiredDepartureTime(request.desiredDepartureTime())
                    .build());
        } catch (Exception e) {
            log.warn("Could not record a search for demand: {}", e.getMessage());
        }
    }

    /** "Riders are looking for" - routes with enough riders, best fit for this driver first. */
    @Transactional(readOnly = true)
    public List<RouteDemandItem> demandFor(String driverId) {
        Instant now = Instant.now();
        List<Corridor> corridors = corridors(now).stream()
                .filter(c -> c.riders.size() >= properties.getMinRidersShown())
                .toList();
        if (corridors.isEmpty()) {
            return List.of();
        }
        List<Reference> references = referencesFor(driverId, now);
        List<RoutePost> upcoming = routePostRepository.findByStatusInAndDepartureTimeBetween(UPCOMING, now, now.plus(Duration.ofDays(7)));
        return corridors.stream()
                .map(c -> c.toItem(relevance(c, references), (int) upcoming.stream().filter(r -> serves(r, c)).count()))
                .sorted(Comparator.comparing(RouteDemandItem::relevance).thenComparing(RouteDemandItem::riderCount, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DrivingPreferences preferences(String driverId) {
        return preferenceRepository.findByDriverId(driverId).map(DemandService::toDto)
                .orElse(new DrivingPreferences(null, null, null, null, null, null, false, false, true));
    }

    /** Leaving a field out keeps what was saved. */
    @Transactional
    public DrivingPreferences savePreferences(String driverId, DrivingPreferences update) {
        DriverDrivingPreference saved = preferenceRepository.findByDriverId(driverId)
                .orElseGet(() -> DriverDrivingPreference.builder().driverId(driverId).build());
        if (update.startLat() != null && update.startLng() != null) {
            saved.setStartLat(update.startLat());
            saved.setStartLng(update.startLng());
            saved.setStartLabel(label(update.startLabel()));
        }
        if (update.endLat() != null && update.endLng() != null) {
            saved.setEndLat(update.endLat());
            saved.setEndLng(update.endLng());
            saved.setEndLabel(label(update.endLabel()));
        }
        if (update.mornings() != null) {
            saved.setMornings(update.mornings());
        }
        if (update.evenings() != null) {
            saved.setEvenings(update.evenings());
        }
        if (update.demandAlerts() != null) {
            saved.setDemandAlerts(update.demandAlerts());
        }
        return toDto(preferenceRepository.save(saved));
    }

    /**
     * The evening push: tomorrow's strong, unserved demand, sent only to drivers whose own trips it
     * matches, at most once a day and a few times a week. One push covers every route that qualifies.
     */
    @Transactional
    public int sendEveningAlerts() {
        Instant now = Instant.now();
        LocalDate today = GeoMath.lagos(now).toLocalDate();
        LocalDate tomorrow = today.plusDays(1);
        boolean weekend = GeoMath.isWeekend(tomorrow.getDayOfWeek());
        Instant tomorrowStart = tomorrow.atStartOfDay(GeoMath.LAGOS).toInstant();
        List<RoutePost> postedForTomorrow = routePostRepository.findByStatusInAndDepartureTimeBetween(
                UPCOMING, tomorrowStart, tomorrowStart.plus(Duration.ofDays(1)));

        List<Corridor> strong = corridors(now).stream()
                .filter(c -> c.weekend == weekend && c.riders.size() >= properties.getMinRidersForAlert())
                .filter(c -> postedForTomorrow.stream().noneMatch(r -> serves(r, c)))
                .toList();
        if (strong.isEmpty()) {
            return 0;
        }

        Map<String, List<Reference>> byDriver = allReferences(now);
        Set<String> optedOut = preferenceRepository.findAll().stream()
                .filter(p -> !p.isDemandAlerts()).map(DriverDrivingPreference::getDriverId).collect(Collectors.toSet());
        int sent = 0;
        for (Map.Entry<String, List<Reference>> entry : byDriver.entrySet()) {
            String driverId = entry.getKey();
            if (optedOut.contains(driverId) || !canPost(driverId)) {
                continue;
            }
            List<Corridor> matches = strong.stream()
                    .filter(c -> relevance(c, entry.getValue()) == DemandRelevance.MATCHES_YOUR_ROUTES)
                    .sorted(Comparator.comparing((Corridor c) -> c.riders.size()).reversed())
                    .toList();
            if (matches.isEmpty() || !paymentsAllowPosting(driverId)
                    || demandAlertLogRepository.countByDriverIdAndSentOnAfter(driverId, today.minusDays(7)) >= properties.getMaxAlertsPerWeek()
                    || demandAlertLogRepository.claim(UUID.randomUUID().toString(), driverId, today, matches.size()) == 0) {
                continue;
            }
            kafkaTemplate.send(KafkaTopics.DRIVER_DEMAND_ALERT, DriverDemandAlertEvent.builder()
                    .driverId(driverId)
                    .forDate(tomorrow)
                    .routes(matches.stream().limit(3).map(c -> DriverDemandAlertEvent.DemandRoute.builder()
                            .originLabel(c.label(c.originLabels)).destinationLabel(c.label(c.destinationLabels))
                            .windowStart(clock(c.windowStart)).windowEnd(clock(c.windowEnd))
                            .riderCount(c.riders.size()).build()).toList())
                    .build());
            sent++;
        }
        log.info("Demand alerts: {} strong route(s) for {}, {} driver(s) told", strong.size(), tomorrow, sent);
        return sent;
    }

    @Transactional
    public int deleteOldSearches() {
        return routeSearchRepository.deleteOlderThan(Instant.now().minus(Duration.ofDays(properties.getRetentionDays())));
    }

    // ---- grouping ----------------------------------------------------------------------------

    /** One rider's wish: where from, where to, and the time they want to leave (minutes after midnight, Lagos). */
    private record Signal(String riderId, boolean notifyMe, int minutes, double oLat, double oLng, double dLat, double dLng,
                          String originLabel, String destinationLabel) {
    }

    /** Searches from the last week plus requests still waiting, grouped by route and then by time window. */
    private List<Corridor> corridors(Instant now) {
        Map<String, List<Signal>> byRoute = new LinkedHashMap<>();
        Map<String, Boolean> weekendOf = new HashMap<>();
        for (RouteSearch s : routeSearchRepository.findByCreatedAtAfter(now.minus(Duration.ofDays(properties.getSearchWindowDays())))) {
            add(byRoute, weekendOf, s.isWeekend(), new Signal(s.getRiderId(), false, s.getLocalMinutes(), s.getOriginLat(), s.getOriginLng(),
                    s.getDestinationLat(), s.getDestinationLng(), s.getOriginLabel(), s.getDestinationLabel()));
        }
        for (NotifyMeRequest n : notifyMeRequestRepository.findByCancelledFalseAndFulfilledFalseAndExpiresAtAfter(now)) {
            if (n.isRepeating()) {
                if ((n.getDaysMask() & 0b0011111) != 0) {
                    add(byRoute, weekendOf, false, signal(n, n.getTimeMinutes()));
                }
                if ((n.getDaysMask() & 0b1100000) != 0) {
                    add(byRoute, weekendOf, true, signal(n, n.getTimeMinutes()));
                }
            } else {
                add(byRoute, weekendOf, GeoMath.isWeekend(GeoMath.lagos(n.getDesiredDepartureTime()).getDayOfWeek()),
                        signal(n, minutesOf(n.getDesiredDepartureTime())));
            }
        }
        List<Corridor> corridors = new ArrayList<>();
        byRoute.forEach((key, signals) -> corridors.addAll(windows(signals, weekendOf.get(key))));
        return corridors;
    }

    private static Signal signal(NotifyMeRequest n, int minutes) {
        return new Signal(n.getRiderId(), true, minutes, n.getOriginLat(), n.getOriginLng(), n.getDestinationLat(), n.getDestinationLng(),
                n.getOriginLabel(), n.getDestinationLabel());
    }

    private void add(Map<String, List<Signal>> byRoute, Map<String, Boolean> weekendOf, boolean weekend, Signal signal) {
        String key = GeoMath.cell(signal.oLat, signal.oLng, properties.getCellDegrees()) + "|"
                + GeoMath.cell(signal.dLat, signal.dLng, properties.getCellDegrees()) + "|" + weekend;
        byRoute.computeIfAbsent(key, k -> new ArrayList<>()).add(signal);
        weekendOf.put(key, weekend);
    }

    /**
     * Splits one route's wishes into time windows: repeatedly takes the window (at most windowMinutes
     * wide) holding the most different riders, until every wish is in one. 6:50 and 7:10 land together;
     * 7:00 and 18:00 become a morning and an evening window.
     */
    private List<Corridor> windows(List<Signal> signals, boolean weekend) {
        List<Signal> remaining = new ArrayList<>(signals);
        remaining.sort(Comparator.comparingInt(Signal::minutes));
        List<Corridor> corridors = new ArrayList<>();
        while (!remaining.isEmpty()) {
            int bestFrom = 0;
            int bestTo = 0;
            int bestRiders = 0;
            for (int from = 0; from < remaining.size(); from++) {
                Set<String> riders = new HashSet<>();
                int to = from;
                while (to < remaining.size() && remaining.get(to).minutes - remaining.get(from).minutes <= properties.getWindowMinutes()) {
                    riders.add(remaining.get(to).riderId);
                    to++;
                }
                if (riders.size() > bestRiders) {
                    bestRiders = riders.size();
                    bestFrom = from;
                    bestTo = to;
                }
            }
            List<Signal> window = new ArrayList<>(remaining.subList(bestFrom, bestTo));
            remaining.subList(bestFrom, bestTo).clear();
            corridors.add(new Corridor(window, weekend));
        }
        return corridors;
    }

    // ---- fitting demand to a driver ------------------------------------------------------------

    /** Where a driver goes, and when: their recent routes, or what they told us if they haven't posted yet.
     * times holds [from, to] ranges in minutes after midnight - a single departure is a range of one minute. */
    /** route is set when the reference is one of the driver's own trips - its road counts, not just its ends. */
    private record Reference(double originLat, double originLng, double destinationLat, double destinationLng, List<int[]> times,
                             RoutePost route) {
    }

    private List<Reference> referencesFor(String driverId, Instant now) {
        List<Reference> references = new ArrayList<>(routePostRepository
                .findByDriverIdAndCreatedAtAfter(driverId, now.minus(Duration.ofDays(properties.getDriverHistoryDays())))
                .stream().map(DemandService::fromRoute).toList());
        preferenceRepository.findByDriverId(driverId).flatMap(DemandService::fromPreference).ifPresent(references::add);
        return references;
    }

    private Map<String, List<Reference>> allReferences(Instant now) {
        Map<String, List<Reference>> byDriver = new HashMap<>();
        for (RoutePost route : routePostRepository.findByCreatedAtAfter(now.minus(Duration.ofDays(properties.getDriverHistoryDays())))) {
            byDriver.computeIfAbsent(route.getDriverId(), k -> new ArrayList<>()).add(fromRoute(route));
        }
        for (DriverDrivingPreference preference : preferenceRepository.findAll()) {
            fromPreference(preference).ifPresent(r -> byDriver.computeIfAbsent(preference.getDriverId(), k -> new ArrayList<>()).add(r));
        }
        return byDriver;
    }

    private static Reference fromRoute(RoutePost route) {
        int minutes = minutesOf(route.getDepartureTime());
        return new Reference(route.getOriginLat(), route.getOriginLng(), route.getDestinationLat(), route.getDestinationLng(),
                List.<int[]>of(new int[]{minutes, minutes}), route);
    }

    private static Optional<Reference> fromPreference(DriverDrivingPreference p) {
        if (p.getStartLat() == null || p.getEndLat() == null) {
            return Optional.empty();
        }
        List<int[]> times = new ArrayList<>();
        if (p.isMornings()) {
            times.add(MORNINGS);
        }
        if (p.isEvenings()) {
            times.add(EVENINGS);
        }
        if (times.isEmpty()) {
            times.add(new int[]{0, DAY_MINUTES - 1});
        }
        return Optional.of(new Reference(p.getStartLat(), p.getStartLng(), p.getEndLat(), p.getEndLng(), times, null));
    }

    private DemandRelevance relevance(Corridor c, List<Reference> references) {
        DemandRelevance best = DemandRelevance.OTHER;
        for (Reference r : references) {
            boolean start = GeoMath.distanceKm(r.originLat, r.originLng, c.originLat, c.originLng) <= properties.getMatchRadiusKm();
            boolean end = GeoMath.distanceKm(r.destinationLat, r.destinationLng, c.destinationLat, c.destinationLng) <= properties.getMatchRadiusKm();
            // Riders the driver passes on their usual road are on their route too.
            if (!(start && end) && passes(r.route, c)) {
                start = true;
                end = true;
            }
            boolean time = r.times.stream().anyMatch(range -> nearWindow(range[0], range[1], c));
            DemandRelevance fit = start && end ? (time ? DemandRelevance.MATCHES_YOUR_ROUTES : DemandRelevance.DIFFERENT_TIME)
                    : start ? DemandRelevance.SAME_START
                    : end ? DemandRelevance.SAME_END
                    : DemandRelevance.OTHER;
            if (fit.compareTo(best) < 0) {
                best = fit;
            }
        }
        return best;
    }

    /** A time range that overlaps the window, give or take timeSlackMinutes either side. */
    private boolean nearWindow(int from, int to, Corridor c) {
        return from <= c.windowEnd + properties.getTimeSlackMinutes() && to >= c.windowStart - properties.getTimeSlackMinutes();
    }

    private boolean passes(RoutePost route, Corridor c) {
        return route != null && routeMatcher.enabled()
                && routeMatcher.match(route, c.originLat, c.originLng, c.destinationLat, c.destinationLng).isPresent();
    }

    /** A posted ride that already covers this demand - same ends (or passing both on its road), same kind of day, leaving in or near the window. */
    private boolean serves(RoutePost route, Corridor c) {
        int minutes = minutesOf(route.getDepartureTime());
        return GeoMath.isWeekend(GeoMath.lagos(route.getDepartureTime()).getDayOfWeek()) == c.weekend
                && nearWindow(minutes, minutes, c)
                && ((GeoMath.distanceKm(route.getOriginLat(), route.getOriginLng(), c.originLat, c.originLng) <= properties.getMatchRadiusKm()
                && GeoMath.distanceKm(route.getDestinationLat(), route.getDestinationLng(), c.destinationLat, c.destinationLng) <= properties.getMatchRadiusKm())
                || passes(route, c));
    }

    /** Pushing a driver who can't post would only frustrate them. Verified, or past the posting prerequisites. */
    private boolean canPost(String driverId) {
        return driverVerificationCacheRepository.findById(driverId)
                .map(c -> c.isApproved() || c.getPrerequisitesMetAt() != null)
                .orElse(false);
    }

    /** No bank account, or blocked for owing too much, means they couldn't post the ride. Payments unreachable doesn't block. */
    private boolean paymentsAllowPosting(String driverId) {
        PaymentServiceClient.PostingStatus status = paymentServiceClient.postingStatus(driverId);
        return status == null || (status.hasPayoutAccount() && !status.overOwedLimit());
    }

    private static int minutesOf(Instant instant) {
        ZonedDateTime when = GeoMath.lagos(instant);
        return when.getHour() * 60 + when.getMinute();
    }

    /** "07:05" - how times travel in the API. */
    static String clock(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    private static DrivingPreferences toDto(DriverDrivingPreference p) {
        return new DrivingPreferences(p.getStartLat(), p.getStartLng(), p.getStartLabel(), p.getEndLat(), p.getEndLng(), p.getEndLabel(),
                p.isMornings(), p.isEvenings(), p.isDemandAlerts());
    }

    /** A place name as stored - trimmed, never empty, never longer than the column. */
    static String label(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    /** One route and time window riders want: who (searching and waiting), when, where on average, and what they called it. */
    private static final class Corridor {
        final boolean weekend;
        final int windowStart;
        final int windowEnd;
        final int typical;
        final Set<String> riders = new HashSet<>();
        final Set<String> waiting = new HashSet<>();
        final Map<String, Integer> originLabels = new HashMap<>();
        final Map<String, Integer> destinationLabels = new HashMap<>();
        final double originLat;
        final double originLng;
        final double destinationLat;
        final double destinationLng;

        Corridor(List<Signal> window, boolean weekend) {
            this.weekend = weekend;
            this.windowStart = window.get(0).minutes;
            this.windowEnd = window.get(window.size() - 1).minutes;
            this.typical = window.get(window.size() / 2).minutes;
            double oLat = 0;
            double oLng = 0;
            double dLat = 0;
            double dLng = 0;
            for (Signal s : window) {
                riders.add(s.riderId);
                if (s.notifyMe) {
                    waiting.add(s.riderId);
                }
                oLat += s.oLat;
                oLng += s.oLng;
                dLat += s.dLat;
                dLng += s.dLng;
                if (s.originLabel != null) {
                    originLabels.merge(s.originLabel, 1, Integer::sum);
                }
                if (s.destinationLabel != null) {
                    destinationLabels.merge(s.destinationLabel, 1, Integer::sum);
                }
            }
            this.originLat = oLat / window.size();
            this.originLng = oLng / window.size();
            this.destinationLat = dLat / window.size();
            this.destinationLng = dLng / window.size();
        }

        /** The name riders used most for this end - null if nobody named it. */
        String label(Map<String, Integer> labels) {
            return labels.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }

        RouteDemandItem toItem(DemandRelevance relevance, int ridesPosted) {
            return new RouteDemandItem(label(originLabels), label(destinationLabels), originLat, originLng, destinationLat, destinationLng,
                    weekend ? DemandDayType.WEEKENDS : DemandDayType.WEEKDAYS, clock(windowStart), clock(windowEnd), clock(typical),
                    riders.size(), waiting.size(), ridesPosted, relevance);
        }
    }
}
