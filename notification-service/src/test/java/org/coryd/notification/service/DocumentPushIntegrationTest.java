package org.coryd.notification.service;

import org.coryd.notification.entity.UserNotification;
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
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A DOCUMENT_EXPIRING push names the document in its data and on its bell entry (the new document_type column), so the
 * app opens that document from either - against notification-service's own migrations.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({DeviceTokenService.class, NotificationDispatchService.class})
class DocumentPushIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DeviceTokenService deviceTokenService;
    @Autowired NotificationDispatchService dispatch;
    @Autowired DeviceTokenRepository devices;
    @Autowired UserNotificationRepository feed;

    @MockBean FcmService fcm;
    @MockBean EmailService email;
    @MockBean TermiiSmsService sms;
    @MockBean WhatsAppService whatsApp;

    @BeforeEach
    void setUp() {
        devices.deleteAll();
        feed.deleteAll();
        when(fcm.sendPush(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new FcmService.PushResult(true, "m-1", null));
    }

    @Test
    void thePushDataAndTheBellEntryNameTheDocument() {
        deviceTokenService.register("driver-1", "phone-a");

        dispatch.sendDocumentPush("driver-1", "Insurance certificate expiring soon", "Your insurance certificate expires in 3 days.",
                "DOCUMENT_EXPIRING", "INSURANCE", "vehicle-1");

        verify(fcm).sendPush(eq("driver-1"), eq("phone-a"), eq("Insurance certificate expiring soon"), any(), eq("DOCUMENT_EXPIRING"),
                eq(Map.of("documentType", "INSURANCE", "vehicleId", "vehicle-1")));
        UserNotification entry = feed.findAll().getFirst();
        assertThat(entry.getEventType()).isEqualTo("DOCUMENT_EXPIRING");
        assertThat(entry.getDocumentType()).isEqualTo("INSURANCE");
        assertThat(entry.getBookingId()).isNull();
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
