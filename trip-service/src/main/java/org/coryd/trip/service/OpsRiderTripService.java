package org.coryd.trip.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.trip.dto.OpsRiderReceipt;
import org.coryd.trip.dto.OpsRiderTrip;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.BookingStatusHistory;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Ops Console's view of one rider's trips: their paid bookings, searchable by route, driver, date or day, paged in the
 * database; and a trip's receipt.
 */
@Service
@RequiredArgsConstructor
public class OpsRiderTripService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final BookingRepository bookingRepository;
    private final RoutePostRepository routePostRepository;
    private final RatingRepository ratingRepository;
    private final BookingStatusHistoryRepository statusHistoryRepository;

    @Transactional(readOnly = true)
    public PagedResponse<OpsRiderTrip> trips(String riderId, String search, int page, int size) {
        DateTerm date = DateTerm.parse(search);
        String text = search == null || search.isBlank() ? ""
                : "%" + search.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        DateTerm d = date == null ? DateTerm.NONE : date;
        Page<Booking> bookings = bookingRepository.findRiderTripsForOps(riderId, text, d.year(), d.month(), d.day(), d.isoDayOfWeek(),
                date == null ? 0 : 1, lagosShiftSeconds(), PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50))));

        Map<String, RoutePost> routes = routePostRepository.findAllById(bookings.getContent().stream().map(Booking::getRoutePostId).distinct().toList())
                .stream().collect(Collectors.toMap(RoutePost::getId, Function.identity()));
        List<String> driverIds = routes.values().stream().map(RoutePost::getDriverId).distinct().toList();
        Map<String, RatingRepository.RatingSummary> ratings = driverIds.isEmpty() ? Map.of() : ratingRepository.summarize(driverIds).stream()
                .collect(Collectors.toMap(RatingRepository.RatingSummary::getRateeId, Function.identity()));

        return PagedResponse.from(bookings.map(b -> {
            RoutePost route = routes.get(b.getRoutePostId());
            RatingRepository.RatingSummary rating = route == null ? null : ratings.get(route.getDriverId());
            return new OpsRiderTrip(b.getId(), b.getRoutePostId(), b.getStatus(),
                    route == null ? null : route.getOriginLabel(), route == null ? null : route.getDestinationLabel(),
                    route == null ? null : route.getDepartureTime(), b.getSeatCount() == null ? 0 : b.getSeatCount(), b.getFareTotal(),
                    route == null ? null : route.getDriverId(), b.getDriverNameSnapshot(),
                    rating == null ? null : rating.getAvgStars(), rating == null ? null : rating.getRatingCount());
        }));
    }

    @Transactional(readOnly = true)
    public OpsRiderReceipt receipt(String riderId, String bookingId) {
        Booking b = bookingRepository.findById(bookingId)
                .filter(booking -> booking.getRiderId().equals(riderId))
                .orElseThrow(() -> new ResourceNotFoundException("Trip not found for this rider"));
        RoutePost route = routePostRepository.findById(b.getRoutePostId()).orElse(null);
        Instant completedAt = statusHistoryRepository.findByBookingIdOrderByCreatedAtAsc(bookingId).stream()
                .filter(h -> h.getStatus() == BookingStatus.COMPLETED)
                .map(BookingStatusHistory::getCreatedAt)
                .reduce((first, second) -> second)
                .orElse(b.getStatus() == BookingStatus.COMPLETED && route != null ? route.getCompletedAt() : null);
        return new OpsRiderReceipt(b.getId(), b.getRiderId(), b.getRoutePostId(), b.getStatus(),
                route == null ? null : route.getOriginLabel(), route == null ? null : route.getDestinationLabel(),
                b.getPickupLabel() != null ? b.getPickupLabel() : route == null ? null : route.getOriginLabel(),
                b.getDropoffLabel() != null ? b.getDropoffLabel() : route == null ? null : route.getDestinationLabel(),
                route == null ? null : route.getDepartureTime(), b.getCreatedAt(), b.getConfirmedAt(), b.getPickupConfirmedAt(), completedAt,
                route == null ? null : route.getDriverId(), b.getDriverNameSnapshot(), b.getVehiclePlateSnapshot(),
                b.getSeatCount() == null ? 0 : b.getSeatCount(),
                b.getFarePerSeatSnapshot() != null ? b.getFarePerSeatSnapshot() : route == null ? null : route.getFarePerSeat(),
                b.getFareTotal());
    }

    /** departure_time holds the JVM's wall-clock time; this many seconds turns it into Lagos's. */
    static int lagosShiftSeconds() {
        Instant now = Instant.now();
        return LAGOS.getRules().getOffset(now).getTotalSeconds() - ZoneId.systemDefault().getRules().getOffset(now).getTotalSeconds();
    }

    /**
     * A search that names a date or a day - "11 July", "Wed, 11th July", "2026-07-11", "11/7", "July", "wed". 0 is "any".
     * The search is matched as text too, so a name that is also a day or month ("Sunday", "May") still finds the driver.
     */
    record DateTerm(int year, int month, int day, int isoDayOfWeek) {

        static final DateTerm NONE = new DateTerm(0, 0, 0, 0);

        private static final Map<String, DayOfWeek> DAYS = Map.ofEntries(
                Map.entry("mon", DayOfWeek.MONDAY), Map.entry("monday", DayOfWeek.MONDAY),
                Map.entry("tue", DayOfWeek.TUESDAY), Map.entry("tues", DayOfWeek.TUESDAY), Map.entry("tuesday", DayOfWeek.TUESDAY),
                Map.entry("wed", DayOfWeek.WEDNESDAY), Map.entry("wednesday", DayOfWeek.WEDNESDAY),
                Map.entry("thu", DayOfWeek.THURSDAY), Map.entry("thur", DayOfWeek.THURSDAY), Map.entry("thurs", DayOfWeek.THURSDAY),
                Map.entry("thursday", DayOfWeek.THURSDAY),
                Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("friday", DayOfWeek.FRIDAY),
                Map.entry("sat", DayOfWeek.SATURDAY), Map.entry("saturday", DayOfWeek.SATURDAY),
                Map.entry("sun", DayOfWeek.SUNDAY), Map.entry("sunday", DayOfWeek.SUNDAY));

        private static final List<DateTimeFormatter> FULL = formatters("d MMM uuuu", "d MMMM uuuu", "MMM d uuuu", "MMMM d uuuu", "uuuu-M-d", "d/M/uuuu");
        private static final List<DateTimeFormatter> MONTH_DAY = formatters("d MMM", "d MMMM", "MMM d", "MMMM d", "d/M");
        private static final List<DateTimeFormatter> MONTH = formatters("MMM", "MMMM");

        static DateTerm parse(String search) {
            if (search == null || search.isBlank()) {
                return null;
            }
            String cleaned = search.toLowerCase(Locale.ROOT).replaceAll("(\\d+)(st|nd|rd|th)\\b", "$1")
                    .replace(",", " ").replace(".", " ").trim().replaceAll("\\s+", " ");
            int dow = 0;
            List<String> rest = new ArrayList<>();
            for (String token : cleaned.split(" ")) {
                DayOfWeek day = DAYS.get(token);
                if (day != null && dow == 0) {
                    dow = day.getValue();
                } else {
                    rest.add(token);
                }
            }
            String date = String.join(" ", rest);
            if (date.isEmpty()) {
                return dow == 0 ? null : new DateTerm(0, 0, 0, dow);
            }
            for (DateTimeFormatter f : FULL) {
                try {
                    LocalDate d = LocalDate.parse(date, f);
                    return new DateTerm(d.getYear(), d.getMonthValue(), d.getDayOfMonth(), 0);
                } catch (DateTimeParseException ignored) {
                    // try the next shape
                }
            }
            for (DateTimeFormatter f : MONTH_DAY) {
                try {
                    MonthDay md = MonthDay.parse(date, f);
                    return new DateTerm(0, md.getMonthValue(), md.getDayOfMonth(), 0);
                } catch (DateTimeParseException ignored) {
                    // try the next shape
                }
            }
            for (DateTimeFormatter f : MONTH) {
                try {
                    return new DateTerm(0, f.parse(date).get(java.time.temporal.ChronoField.MONTH_OF_YEAR), 0, dow);
                } catch (DateTimeParseException ignored) {
                    // try the next shape
                }
            }
            return null;
        }

        private static List<DateTimeFormatter> formatters(String... patterns) {
            List<DateTimeFormatter> result = new ArrayList<>();
            for (String p : patterns) {
                result.add(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p).toFormatter(Locale.ENGLISH));
            }
            return result;
        }
    }
}
