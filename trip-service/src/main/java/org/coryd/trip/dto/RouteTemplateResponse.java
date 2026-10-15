package org.coryd.trip.dto;

import org.coryd.trip.entity.RouteTemplate;
import org.coryd.trip.util.GeoMath;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

public record RouteTemplateResponse(
        String id,
        String vehicleId,
        String originLabel,
        Double originLat,
        Double originLng,
        String destinationLabel,
        Double destinationLat,
        Double destinationLng,
        List<DayOfWeek> days,
        String departureTime,
        Integer seatCapacity,
        BigDecimal farePerSeat,
        String routeNote,
        LocalDate activeUntil,
        boolean paused,
        List<PostRouteRequest.ReservationRequest> standingSeats
) {
    public static RouteTemplateResponse from(RouteTemplate t) {
        int mask = t.getDaysMask() == null ? 0 : t.getDaysMask();
        Integer minutes = t.getDepartureMinutes();
        return new RouteTemplateResponse(t.getId(), t.getVehicleId(), t.getOriginLabel(), t.getOriginLat(), t.getOriginLng(),
                t.getDestinationLabel(), t.getDestinationLat(), t.getDestinationLng(),
                Arrays.stream(DayOfWeek.values()).filter(d -> (mask & GeoMath.dayBit(d)) != 0).toList(),
                minutes == null ? null : String.format("%02d:%02d", minutes / 60, minutes % 60),
                t.getTypicalSeatCount(), t.getFarePerSeat(), t.getRouteNote(), t.getActiveUntil(), t.isPaused(),
                t.getStandingSeats().stream().map(s -> new PostRouteRequest.ReservationRequest(s.getRiderId(), s.getSeatCount())).toList());
    }
}
