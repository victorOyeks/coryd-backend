package org.coryd.identity.service;

import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ExternalServiceException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.UserDeletedEvent;
import org.coryd.identity.client.DeletionBlockerClient;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.BetaSignupRepository;
import org.coryd.identity.repository.OtpCodeRepository;
import org.coryd.identity.repository.RefreshTokenRepository;
import org.coryd.identity.repository.UserDeviceRepository;
import org.coryd.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who can delete an account, what stops it, and what goes when it's deleted. */
class AccountDeletionServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
    private final UserDeviceRepository devices = mock(UserDeviceRepository.class);
    private final OtpCodeRepository otps = mock(OtpCodeRepository.class);
    private final BetaSignupRepository waitlist = mock(BetaSignupRepository.class);
    private final DeletionBlockerClient blockers = mock(DeletionBlockerClient.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final AccountDeletionRecorder recorder = mock(AccountDeletionRecorder.class);
    private final SessionRevocationService sessions = mock(SessionRevocationService.class);
    private final org.coryd.identity.repository.AccountActionRepository history = mock(org.coryd.identity.repository.AccountActionRepository.class);
    private final AccountDeletionService service = new AccountDeletionService(users, tokens, devices, otps, waitlist, blockers, kafka,
            recorder, sessions, history);

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id("user-1").phone("08031234567").build();
        when(users.findById("user-1")).thenReturn(Optional.of(user));
        when(blockers.blockers(eq("user-1"), anyBoolean())).thenReturn(List.of());
        when(waitlist.findByWhatsappNumber(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void deletingYourOwnAccountErasesItAndTellsEveryService() {
        service.deleteOwn("user-1");

        verify(tokens).deleteByUserId("user-1");
        verify(devices).deleteByUserId("user-1");
        verify(otps).deleteByPhone("08031234567");
        verify(users).delete(user);
        verify(kafka).send(eq(KafkaTopics.USER_DELETED), argThat(e -> {
            UserDeletedEvent event = (UserDeletedEvent) e;
            return "user-1".equals(event.getUserId()) && "08031234567".equals(event.getPhone())
                    && AccountDeletionService.SELF.equals(event.getDeletedBy()) && event.getDeletedAt() != null;
        }));
    }

    @Test
    void someoneOnATripOrWithMoneyMovingIsToldWhyAndNothingIsDeleted() {
        when(blockers.blockers(eq("user-1"), anyBoolean())).thenReturn(List.of("you're on a trip right now - wait until it's finished",
                "you owe CoRyd ₦2,500 - pay it back first"));

        assertThatThrownBy(() -> service.deleteOwn("user-1"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("This account can't be deleted yet: you're on a trip right now - wait until it's finished; "
                        + "you owe CoRyd ₦2,500 - pay it back first");
        verify(users, never()).delete(any());
        verify(kafka, never()).send(anyString(), any());
    }

    @Test
    void aSuspendedPersonCantDeleteThemselvesButOpsCan() {
        user.setActive(false);

        assertThatThrownBy(() -> service.deleteOwn("user-1")).hasMessageContaining("suspended");
        verify(users, never()).delete(any());

        service.deleteByOps("user-1", "ops-9");
        verify(users).delete(user);
        verify(kafka).send(eq(KafkaTopics.USER_DELETED), argThat(e -> "ops-9".equals(((UserDeletedEvent) e).getDeletedBy())));
    }

    @Test
    void paymentsAreToldWhetherOpsOrTheOwnerIsDeleting() {
        service.deleteOwn("user-1");
        verify(blockers).blockers("user-1", false);

        service.deleteByOps("user-1", "ops-9");
        verify(blockers).blockers("user-1", true);
    }

    @Test
    void ifTripsOrPaymentsCantBeAskedNothingIsDeleted() {
        when(blockers.blockers(eq("user-1"), anyBoolean())).thenThrow(new ExternalServiceException("try again"));

        assertThatThrownBy(() -> service.deleteOwn("user-1")).isInstanceOf(ExternalServiceException.class);
        verify(users, never()).delete(any());
    }
}
