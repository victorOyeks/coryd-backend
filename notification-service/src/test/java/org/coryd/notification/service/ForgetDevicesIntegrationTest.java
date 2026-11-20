package org.coryd.notification.service;

import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.gateway.TermiiSmsService;
import org.coryd.notification.gateway.whatsapp.WhatsAppService;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A forced sign-out drops the phones' push tokens registered before it - against notification-service's own migrations.
 * A phone that signed in again since keeps its token, other people's phones are untouched, and the sign-out notice is
 * pushed to the old phones before their tokens go.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({DeviceTokenService.class, NotificationDispatchService.class})
class ForgetDevicesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DeviceTokenService deviceTokenService;
    @Autowired NotificationDispatchService dispatch;
    @Autowired DeviceTokenRepository devices;
    @Autowired UserNotificationRepository feed;
    @Autowired JdbcTemplate jdbc;

    @MockBean FcmService fcm;
    @MockBean EmailService email;
    @MockBean TermiiSmsService sms;
    @MockBean WhatsAppService whatsApp;

    @BeforeEach
    void setUp() {
        devices.deleteAll();
        feed.deleteAll();
        jdbc.update("DELETE FROM notification.notification_log");
        when(fcm.sendPush(anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new FcmService.PushResult(true, "m-1", null));
    }

    @Test
    void onlyPhonesRegisteredBeforeTheCutOffAreDropped() {
        deviceTokenService.register("user-1", "old-phone");
        deviceTokenService.register("user-1", "signed-in-again");
        deviceTokenService.register("user-2", "someone-else");
        registeredAnHourAgo();
        Instant cutOff = Instant.now().minus(1, ChronoUnit.MINUTES);

        // Same token, same user, nothing else changed - re-registering still marks it as registered now.
        deviceTokenService.register("user-1", "signed-in-again");

        assertThat(dispatch.forgetDevices("user-1", cutOff)).isEqualTo(1);
        assertThat(devices.findAll()).extracting(DeviceToken::getFcmToken).containsExactlyInAnyOrder("signed-in-again", "someone-else");
        assertThat(dispatch.forgetDevices("user-1", cutOff)).as("nothing left to drop").isZero();
    }

    @Test
    void theSignOutNoticeReachesThePhonesBeforeTheirTokensGo() {
        deviceTokenService.register("user-1", "phone-a");
        deviceTokenService.register("user-1", "phone-b");
        registeredAnHourAgo();

        dispatch.sendPushThenForgetDevices("user-1", "You've been signed out", "Sign in again", "SIGNED_OUT_EVERYWHERE", Instant.now());

        verify(fcm).sendPush(eq("user-1"), eq("phone-a"), anyString(), anyString(), eq("SIGNED_OUT_EVERYWHERE"), any(), any());
        verify(fcm).sendPush(eq("user-1"), eq("phone-b"), anyString(), anyString(), eq("SIGNED_OUT_EVERYWHERE"), any(), any());
        assertThat(devices.findByUserId("user-1")).as("dropped once the notice was sent").isEmpty();
        assertThat(feed.findAll()).as("the bell keeps the notice").hasSize(1);
    }

    /** Shifted rather than set, so the JVM's and the database's time zones can't skew it. */
    private void registeredAnHourAgo() {
        jdbc.update("UPDATE notification.device_tokens SET created_at = created_at - interval '1 hour', updated_at = updated_at - interval '1 hour'");
    }

    /** Runs @Async work on the caller's thread, so a test sees it finished. */
    @TestConfiguration
    static class SameThread {
        @Bean
        Executor notificationExecutor() {
            return Runnable::run;
        }
    }
}
