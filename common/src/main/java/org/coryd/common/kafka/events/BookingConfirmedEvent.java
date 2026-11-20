package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingConfirmedEvent {
    private String bookingId;
    private String routePostId;
    private String riderId;
    private String driverId;
    private int seatCount;
    private BigDecimal fareTotal;
    private String pickupCode;
    private LocalDateTime confirmedAt;
    /** For the driver's "Ada booked 1 seat on Ikeja City Mall → Victoria Island" push (M20) - null on older events. */
    private String riderFirstName;
    private String originLabel;
    private String destinationLabel;
}
