package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.FirstTripDtos;
import org.coryd.trip.entity.FirstTripExperience;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.event.TripCompletedForUserEvent;
import org.coryd.trip.repository.FirstTripExperienceRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The "Your first drive is done!" / "First ride complete!" moment. The server decides what "first" means, so it survives
 * a reinstall and shows once: when a trip completes and it is the person's only completed one in that role (a rider's
 * completed seats, a driver's completed routes that carried someone), a PENDING row is written. Nobody who had trips
 * before this shipped gets one - there is no backfill to get wrong. The app asks "celebrate?" and the person shares or
 * skips; either ends it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FirstTripService {

    /** A first trip still unanswered after this long isn't news any more - the app stops being asked. */
    static final Duration OFFER_FOR = Duration.ofDays(14);

    private final FirstTripExperienceRepository repository;
    private final RoutePostRepository routePostRepository;
    private final PlatformTransactionManager transactionManager;

    /** After the completing transaction commits, in a transaction of its own: never able to fail the completion. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTripCompleted(TripCompletedForUserEvent event) {
        try {
            // REQUIRES_NEW: after commit the finished transaction is still bound to the thread, and joining it writes nothing.
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> record(event));
        } catch (Exception e) {
            // A unique-key race (two completions at once) lands here too - the other one wrote the row.
            log.warn("First-trip check failed for {} {} on route {}: {}", event.role(), event.userId(), event.routePostId(), e.getMessage());
        }
    }

    private void record(TripCompletedForUserEvent event) {
        if (repository.existsByUserIdAndRole(event.userId(), event.role())) {
            return;
        }
        long completed = event.role() == FirstTripExperience.Role.RIDER
                ? repository.countCompletedRides(event.userId())
                : repository.countCompletedDrivesWithRiders(event.userId());
        if (completed != 1) {
            return;
        }
        repository.saveAndFlush(FirstTripExperience.builder()
                .userId(event.userId())
                .role(event.role())
                .routePostId(event.routePostId())
                .bookingId(event.bookingId())
                .status(FirstTripExperience.Status.PENDING)
                .completedAt(Instant.now())
                .build());
        log.info("First {} for {} on route {} - the app will celebrate it", event.role() == FirstTripExperience.Role.RIDER ? "ride" : "drive",
                event.userId(), event.routePostId());
    }

    @Transactional(readOnly = true)
    public Optional<FirstTripDtos.Celebration> pending(String userId, FirstTripExperience.Role role) {
        return repository.findByUserIdAndRole(userId, role)
                .filter(e -> e.getStatus() == FirstTripExperience.Status.PENDING)
                .filter(e -> e.getCompletedAt().plus(OFFER_FOR).isAfter(Instant.now()))
                .map(e -> {
                    RoutePost route = routePostRepository.findById(e.getRoutePostId()).orElse(null);
                    return new FirstTripDtos.Celebration(e.getId(), e.getRole(), e.getRoutePostId(), e.getBookingId(),
                            route == null ? null : route.getOriginLabel(), route == null ? null : route.getDestinationLabel(), e.getCompletedAt());
                });
    }

    @Transactional
    public void share(String userId, FirstTripExperience.Role role, FirstTripDtos.ShareRequest request) {
        FirstTripExperience e = open(userId, role);
        String story = request.story() == null || request.story().isBlank() ? null : request.story().trim();
        if (story != null && story.length() > 500) {
            throw new BadRequestException("Keep it under 500 characters");
        }
        e.setFeeling(request.feeling());
        e.setStory(story);
        e.setConsentToShare(Boolean.TRUE.equals(request.consentToShare()));
        e.setStatus(FirstTripExperience.Status.SUBMITTED);
        e.setRespondedAt(Instant.now());
        repository.save(e);
    }

    @Transactional
    public void skip(String userId, FirstTripExperience.Role role) {
        FirstTripExperience e = open(userId, role);
        e.setStatus(FirstTripExperience.Status.SKIPPED);
        e.setRespondedAt(Instant.now());
        repository.save(e);
    }

    private FirstTripExperience open(String userId, FirstTripExperience.Role role) {
        FirstTripExperience e = repository.findByUserIdAndRole(userId, role)
                .orElseThrow(() -> new ResourceNotFoundException("There's no first trip to share yet"));
        if (e.getStatus() != FirstTripExperience.Status.PENDING) {
            throw new BadRequestException("You've already answered this one - thank you!");
        }
        return e;
    }

    /** The console's First trip stories: shared ones only, newest first. */
    @Transactional(readOnly = true)
    public PagedResponse<FirstTripDtos.OpsRow> listShared(FirstTripExperience.Role role, Boolean consent, int page, int size) {
        Page<FirstTripExperience> found = repository.findShared(role, consent, PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50))));
        Map<String, RoutePost> routes = routePostRepository.findAllById(found.getContent().stream().map(FirstTripExperience::getRoutePostId).distinct().toList())
                .stream().collect(Collectors.toMap(RoutePost::getId, Function.identity()));
        return PagedResponse.from(found.map(e -> {
            RoutePost r = routes.get(e.getRoutePostId());
            return new FirstTripDtos.OpsRow(e.getId(), e.getUserId(), e.getRole(), e.getRoutePostId(), e.getBookingId(),
                    r == null ? null : r.getOriginLabel(), r == null ? null : r.getDestinationLabel(), e.getFeeling(), e.getStory(),
                    e.isConsentToShare(), e.getCompletedAt(), e.getRespondedAt());
        }));
    }
}
