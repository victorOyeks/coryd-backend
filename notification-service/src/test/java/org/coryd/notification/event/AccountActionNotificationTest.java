package org.coryd.notification.event;

import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.UserTokensRevokedEvent;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.notification.service.NotificationDispatchService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** What people are told when ops acts on an account: suspensions' cancelled seats, sign-outs, licence tries, vehicle re-checks. */
class AccountActionNotificationTest {

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final NotificationEventListener listener = new NotificationEventListener(dispatch,
            new org.coryd.notification.config.SosAlertProperties());

    @Test
    void aSuspendedRidersCancelledSeatTellsTheRiderTheyreRefundedAndTheDriverTheSeatIsFree() {
        listener.onBookingCancelled(BookingCancelledEvent.builder().bookingId("b-1").riderId("rider-1").driverId("driver-1")
                .cancelledBy("SUSPENDED").paid(true).riderAccountCancelled(true).build());

        verify(dispatch).sendPush(eq("rider-1"), eq("Booking cancelled"),
                eq("Your booking couldn't go ahead and has been cancelled. Your payment has been refunded."), eq("BOOKING_CANCELLED"), eq("b-1"));
        verify(dispatch).sendPush(eq("driver-1"), eq("A seat opened up"),
                eq("A rider's booking on your route was cancelled - the seat is available again."), eq("BOOKING_CANCELLED"), eq("b-1"));
    }

    @Test
    void aSuspendedDriversCancelledRouteDoesntTellThemASeatOpenedUp() {
        listener.onBookingCancelled(BookingCancelledEvent.builder().bookingId("b-1").riderId("rider-1").driverId("driver-1")
                .cancelledBy("SUSPENDED").paid(true).build());

        verify(dispatch).sendPush(eq("rider-1"), eq("Booking cancelled"), anyString(), eq("BOOKING_CANCELLED"), eq("b-1"));
        verify(dispatch, never()).sendPush(eq("driver-1"), anyString(), anyString(), anyString(), any());
    }

    @Test
    void signingOutEverywhereTellsThePersonOnceAndNeverOnAReplay() {
        listener.onUserTokensRevoked(revoked(UserTokensRevokedEvent.SIGNED_OUT_EVERYWHERE, false, Instant.now()));
        verify(dispatch).sendPush(eq("user-1"), eq("You've been signed out"),
                eq("For your security, CoRyd signed you out on all your devices. Sign in again to carry on."), eq("SIGNED_OUT_EVERYWHERE"), eq(null));

        NotificationDispatchService quiet = mock(NotificationDispatchService.class);
        NotificationEventListener replaying = new NotificationEventListener(quiet, new org.coryd.notification.config.SosAlertProperties());
        replaying.onUserTokensRevoked(revoked(UserTokensRevokedEvent.SIGNED_OUT_EVERYWHERE, false,
                Instant.now().minus(NotificationEventListener.SIGN_OUT_NOTICE_WINDOW).minus(Duration.ofMinutes(1))));
        replaying.onUserTokensRevoked(revoked(UserTokensRevokedEvent.SUSPENDED, true, Instant.now()));
        replaying.onUserTokensRevoked(revoked(UserTokensRevokedEvent.REINSTATED, false, Instant.now()));
        replaying.onUserTokensRevoked(revoked(UserTokensRevokedEvent.SIGNED_OUT_EVERYWHERE, true, Instant.now()));
        verify(quiet, never()).sendPush(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aDriverGivenTheirLicenceTriesBackIsInvitedToTryAgain() {
        listener.onVerificationItemDecided(VerificationItemDecidedEvent.builder().driverId("driver-1").item("GOVERNMENT_ID")
                .itemLabel("Driver's licence").approved(false).action("ATTEMPTS_RESET").email("d@x.ng").build());

        verify(dispatch).sendPush(eq("driver-1"), eq("You can verify your driver's licence again"),
                eq("We've reset your driver's licence check. Open the app to try again."), eq("VERIFICATION_ITEM_RETRY"), eq(null));
        verify(dispatch, never()).sendEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aVehicleSentBackToReviewByAnOpsEditIsExplained() {
        listener.onVerificationItemDecided(VerificationItemDecidedEvent.builder().driverId("driver-1").item("VEHICLE")
                .itemLabel("Vehicle registration").approved(false).action("SENT_BACK_TO_REVIEW").email("d@x.ng").build());

        String body = "We've updated your vehicle details and are checking your vehicle registration again. You can post trips once it's approved.";
        verify(dispatch).sendPush(eq("driver-1"), eq("Vehicle registration being re-checked"), eq(body), eq("VERIFICATION_ITEM_IN_REVIEW"), eq(null));
        verify(dispatch).sendEmail(eq("d@x.ng"), eq("Vehicle registration being re-checked - CoRyd"), eq(body), eq("VERIFICATION_ITEM_IN_REVIEW"));
    }

    private static UserTokensRevokedEvent revoked(String reason, boolean suspended, Instant at) {
        return UserTokensRevokedEvent.builder().userId("user-1").tokensValidAfter(at).suspended(suspended).reason(reason).stateAt(at).build();
    }
}
