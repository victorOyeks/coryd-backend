package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.trip.dto.PostRouteRequest;
import org.coryd.trip.dto.RouteTemplateRequest;
import org.coryd.trip.dto.RouteTemplateResponse;
import org.coryd.trip.entity.RouteTemplate;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteTemplateRepository;
import org.coryd.trip.util.GeoMath;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A driver's regular routes, and posting their trips. Each trip goes through RouteService.post exactly
 * as one posted by hand - verification, payment standing, overlap, notify-me and demand all apply - so a
 * trip that can't be posted (driver suspended, owing too much, already busy) is simply skipped and
 * tried again next run. Trips are posted no further ahead than a driver may post by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteTemplateService {

    /** Too close to departure to be worth posting - nobody could book it in time. */
    private static final Duration MIN_LEAD = Duration.ofMinutes(30);

    private final RouteTemplateRepository routeTemplateRepository;
    private final RoutePostRepository routePostRepository;
    private final RouteService routeService;

    @Value("${trip.max-days-ahead:3}")
    private int maxDaysAhead;

    @Transactional(readOnly = true)
    public List<RouteTemplateResponse> mine(String driverId) {
        return routeTemplateRepository.findByDriverIdOrderByCreatedAtDesc(driverId).stream().map(RouteTemplateResponse::from).toList();
    }

    @Transactional
    public RouteTemplateResponse create(String driverId, RouteTemplateRequest request) {
        RouteTemplate template = RouteTemplate.builder().driverId(driverId).build();
        apply(template, request);
        return RouteTemplateResponse.from(routeTemplateRepository.save(template));
    }

    /** Changes apply to trips not yet posted - ones already posted stay as they are. */
    @Transactional
    public RouteTemplateResponse update(String driverId, String templateId, RouteTemplateRequest request) {
        RouteTemplate template = own(driverId, templateId);
        apply(template, request);
        return RouteTemplateResponse.from(routeTemplateRepository.save(template));
    }

    @Transactional
    public RouteTemplateResponse setPaused(String driverId, String templateId, boolean paused) {
        RouteTemplate template = own(driverId, templateId);
        template.setPaused(paused);
        return RouteTemplateResponse.from(routeTemplateRepository.save(template));
    }

    /** Stops future trips; trips already posted stay up until the driver cancels them. */
    @Transactional
    public void delete(String driverId, String templateId) {
        routeTemplateRepository.delete(own(driverId, templateId));
    }

    /** Every active regular route's trips in the posting window - safe to run again, a posted (or cancelled) trip is never re-posted. */
    public int postUpcomingTrips() {
        int posted = 0;
        for (RouteTemplate template : routeTemplateRepository.findByPausedFalse()) {
            posted += postUpcomingTrips(template);
        }
        return posted;
    }

    /** Right after a driver saves or resumes one - their next trips appear without waiting for the job. */
    public int postUpcomingTrips(String driverId, String templateId) {
        return routeTemplateRepository.findById(templateId)
                .filter(t -> driverId.equals(t.getDriverId()))
                .map(this::postUpcomingTrips)
                .orElse(0);
    }

    public int postUpcomingTrips(RouteTemplate template) {
        if (template.isPaused() || template.getDaysMask() == null || template.getDepartureMinutes() == null) {
            return 0;
        }
        Instant now = Instant.now();
        LocalDate today = GeoMath.lagos(now).toLocalDate();
        int posted = 0;
        for (int i = 0; i <= maxDaysAhead; i++) {
            LocalDate day = today.plusDays(i);
            if (template.getActiveUntil() != null && day.isAfter(template.getActiveUntil())) {
                break;
            }
            if ((template.getDaysMask() & GeoMath.dayBit(day.getDayOfWeek())) == 0) {
                continue;
            }
            Instant departure = day.atTime(LocalTime.MIN.plusMinutes(template.getDepartureMinutes()))
                    .atZone(GeoMath.LAGOS).toInstant();
            if (departure.isBefore(now.plus(MIN_LEAD)) || departure.isAfter(now.plus(Duration.ofDays(maxDaysAhead)))
                    || routePostRepository.existsByRouteTemplateIdAndDepartureTime(template.getId(), departure)) {
                continue;
            }
            try {
                routeService.post(template.getDriverId(), template.getDriverId(), toRequest(template, departure), template.getId());
                posted++;
            } catch (Exception e) {
                log.info("Regular route {} not posted for {}: {}", template.getId(), departure, e.getMessage());
            }
        }
        return posted;
    }

    private static PostRouteRequest toRequest(RouteTemplate t, Instant departure) {
        List<PostRouteRequest.ReservationRequest> seats = t.getStandingSeats().stream()
                .map(s -> new PostRouteRequest.ReservationRequest(s.getRiderId(), s.getSeatCount())).toList();
        return new PostRouteRequest(t.getVehicleId(), t.getOriginLabel(), t.getOriginLat(), t.getOriginLng(),
                t.getDestinationLabel(), t.getDestinationLat(), t.getDestinationLng(), departure,
                t.getTypicalSeatCount(), t.getFarePerSeat(), t.getRouteNote(), seats.isEmpty() ? null : seats);
    }

    private static void apply(RouteTemplate t, RouteTemplateRequest r) {
        if (r.activeUntil() != null && r.activeUntil().isBefore(GeoMath.lagos(Instant.now()).toLocalDate())) {
            throw new BadRequestException("The end date has already passed");
        }
        int seatsHeld = r.standingSeats() == null ? 0 : r.standingSeats().stream().mapToInt(PostRouteRequest.ReservationRequest::seatCount).sum();
        if (seatsHeld > r.seatCapacity()) {
            throw new BadRequestException("Standing seats (" + seatsHeld + ") can't exceed the seats offered (" + r.seatCapacity() + ")");
        }
        LocalTime time = LocalTime.parse(r.departureTime());
        t.setVehicleId(r.vehicleId());
        t.setOriginLabel(r.originLabel());
        t.setOriginLat(r.originLat());
        t.setOriginLng(r.originLng());
        t.setDestinationLabel(r.destinationLabel());
        t.setDestinationLat(r.destinationLat());
        t.setDestinationLng(r.destinationLng());
        t.setDaysMask(r.days().stream().distinct().mapToInt(GeoMath::dayBit).sum());
        t.setDepartureMinutes(time.getHour() * 60 + time.getMinute());
        t.setTypicalSeatCount(r.seatCapacity());
        t.setFarePerSeat(r.farePerSeat());
        t.setRouteNote(r.routeNote());
        t.setActiveUntil(r.activeUntil());
        t.getStandingSeats().clear();
        if (r.standingSeats() != null) {
            r.standingSeats().forEach(s -> t.getStandingSeats().add(new RouteTemplate.StandingSeat(s.riderId(), s.seatCount())));
        }
    }

    private RouteTemplate own(String driverId, String templateId) {
        RouteTemplate template = routeTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("Regular route not found"));
        if (!driverId.equals(template.getDriverId())) {
            throw new UnauthorizedException("Only this driver can change their regular route");
        }
        return template;
    }
}
