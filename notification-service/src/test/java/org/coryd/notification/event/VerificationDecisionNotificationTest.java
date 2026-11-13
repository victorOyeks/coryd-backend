package org.coryd.notification.event;

import org.coryd.common.kafka.events.IdentityVerificationChangedEvent;
import org.coryd.common.kafka.events.VerificationItemDecidedEvent;
import org.coryd.notification.service.NotificationDispatchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** What a person is told when ops decides their identity check or asks a driver for a document again. */
class VerificationDecisionNotificationTest {

    private final NotificationDispatchService dispatch = mock(NotificationDispatchService.class);
    private final NotificationEventListener listener = new NotificationEventListener(dispatch,
            new org.coryd.notification.config.SosAlertProperties());

    @Test
    void anOpsApprovalTellsThePersonTheyAreVerified() {
        listener.onIdentityVerificationChanged(event("OVERRIDE_APPROVED", true, null));

        verify(dispatch).sendPush(eq("rider-1"), eq("Identity verified"), eq("Your identity has been verified. You're all set."),
                eq("IDENTITY_VERIFICATION_APPROVED"), eq(null));
    }

    @Test
    void aDriversNinAcceptedBeforeTheirLicenceIsToldWhatIsLeft() {
        listener.onIdentityVerificationChanged(event("APPROVED", false, null));

        verify(dispatch).sendPush(eq("rider-1"), eq("NIN confirmed"),
                eq("Your NIN has been confirmed. Finish verifying your licence to complete your identity check."),
                eq("IDENTITY_VERIFICATION_APPROVED"), eq(null));
    }

    @Test
    void anOpsRejectionCarriesTheReason() {
        listener.onIdentityVerificationChanged(event("REJECTED", false, "The name on your NIN doesn't match your profile"));

        verify(dispatch).sendPush(eq("rider-1"), eq("Identity check needs attention"),
                eq("The name on your NIN doesn't match your profile"), eq("IDENTITY_VERIFICATION_REJECTED"), eq(null));
    }

    @Test
    void triesGivenBackInviteThemToTryAgain() {
        listener.onIdentityVerificationChanged(event("ATTEMPTS_RESET", false, null));

        verify(dispatch).sendPush(eq("rider-1"), eq("You can verify your identity again"),
                eq("We've reset your identity check. Open the app to try again."), eq("IDENTITY_VERIFICATION_RETRY"), eq(null));
    }

    @Test
    void smileIdsOwnResultsStaySilentAsBefore() {
        listener.onIdentityVerificationChanged(event(null, true, null));

        verify(dispatch, never()).sendPush(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aResubmissionRequestAsksForTheDocumentAgainWithTheReason() {
        listener.onVerificationItemDecided(VerificationItemDecidedEvent.builder().driverId("driver-1").item("INSURANCE")
                .itemLabel("Insurance certificate").approved(false).rejectionReason("The certificate is cut off at the bottom")
                .action("RESUBMISSION_REQUESTED").build());

        verify(dispatch).sendPush(eq("driver-1"), eq("Please upload your insurance certificate again"),
                eq("The certificate is cut off at the bottom"), eq("VERIFICATION_ITEM_REJECTED"), eq(null));
    }

    private static IdentityVerificationChangedEvent event(String action, boolean verified, String reason) {
        return IdentityVerificationChangedEvent.builder().userId("rider-1").verified(verified).decidedBy("ops-1")
                .decidedAt(Instant.now()).action(action).reason(reason).build();
    }
}
