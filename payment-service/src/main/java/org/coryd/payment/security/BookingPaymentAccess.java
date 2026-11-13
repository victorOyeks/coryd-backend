package org.coryd.payment.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.OpsPermissions;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.payment.client.TripServiceClient;
import org.coryd.payment.repository.PaymentTransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Who may read a booking's payment (GET /payments/bookings/{bookingId} - the receipt / Pay Now status): the rider who
 * booked, the driver of that booking's trip, another CoRyd service (internal API key), or an ops admin whose role holds
 * MONEY_VIEW. Everyone else gets 403. It used to be readable by any signed-in user.
 *
 * <p>The rider comes from the payment row itself; the driver, only when someone other than the rider asks, from
 * trip-service's booking view. A booking with no payment yet is left to the normal 404. 403 rather than the 401 older
 * "only your own" checks use: the apps treat a 401 as "signed out".
 */
@Slf4j
@Component
public class BookingPaymentAccess {

    public static final String NOT_YOURS = "You can only view payments for your own bookings";

    private final PaymentTransactionRepository transactions;
    private final TripServiceClient tripServiceClient;
    private final String internalApiKey;

    public BookingPaymentAccess(PaymentTransactionRepository transactions, TripServiceClient tripServiceClient,
                                @Value("${services.internal.api-key}") String internalApiKey) {
        this.transactions = transactions;
        this.tripServiceClient = tripServiceClient;
        this.internalApiKey = internalApiKey;
    }

    public void requireCanRead(String bookingId, CoRydUserDetails caller, HttpServletRequest request) {
        if (InternalApiKeyFilter.hasValidKey(request, internalApiKey)) {
            return;
        }
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_YOURS);
        }
        if (caller.getActiveRole() == UserRole.OPS_ADMIN) {
            String refusal = OpsPermissions.refusal(caller, OpsPermission.MONEY_VIEW);
            if (refusal != null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
            }
            return;
        }
        var payment = transactions.findFirstByBookingIdOrderByCreatedAtDesc(bookingId).orElse(null);
        if (payment == null) {
            return; // the service answers 404 "No payment found for this booking"
        }
        if (caller.getUserId().equals(payment.getRiderId())) {
            return;
        }
        if (caller.getActiveRole() == UserRole.DRIVER && isTheTripsDriver(bookingId, caller.getUserId())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, NOT_YOURS);
    }

    private boolean isTheTripsDriver(String bookingId, String driverId) {
        try {
            TripServiceClient.BookingPaymentView view = tripServiceClient.fetchPaymentView(bookingId);
            return view != null && driverId.equals(view.driverId());
        } catch (RuntimeException e) {
            // Can't tell - refuse rather than show someone else's payment.
            log.warn("Couldn't check the driver of booking {}: {}", bookingId, e.getMessage());
            return false;
        }
    }
}
