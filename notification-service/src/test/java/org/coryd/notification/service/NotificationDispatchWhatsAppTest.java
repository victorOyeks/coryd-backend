package org.coryd.notification.service;

import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.enums.OtpChannel;
import org.coryd.notification.config.WhatsAppProperties;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.gateway.TermiiSmsService;
import org.coryd.notification.gateway.whatsapp.TermiiWhatsAppProviderTestAccess;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessage;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.coryd.notification.gateway.whatsapp.WhatsAppService;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * WhatsApp first, SMS as the fallback, never both - with Termii's WhatsApp endpoint mocked over HTTP and the
 * SMS gateway mocked, so nothing is really sent.
 */
class NotificationDispatchWhatsAppTest {

    private static final String WHATSAPP_URL = "https://termii.test/api/send/template";
    private static final String PHONE = "08031234567";
    private static final String SMS = "Hi Ada, this is an emergency alert from CoRyd";
    private static final WhatsAppMessage SOS = new WhatsAppMessage(WhatsAppMessageKind.SOS_CONTACT,
            Map.of("name", "Ada", "location", "https://maps.google.com/?q=6.5,3.3"));

    private final WhatsAppProperties properties = new WhatsAppProperties();
    private final TermiiSmsService sms = mock(TermiiSmsService.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final NotificationPreferencesRepository preferences = mock(NotificationPreferencesRepository.class);
    private MockRestServiceServer termii;
    private NotificationDispatchService dispatch;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.getTermii().setApiKey("test-key");
        properties.getTermii().setDeviceId("device-1");
        properties.getTemplates().setSosContact("tpl-sos");
        properties.getTemplates().setOtp("tpl-otp");

        RestClient.Builder builder = RestClient.builder().baseUrl("https://termii.test");
        termii = MockRestServiceServer.bindTo(builder).build();
        WhatsAppService whatsApp = new WhatsAppService(properties,
                TermiiWhatsAppProviderTestAccess.create(builder.build(), properties));

        when(sms.send(anyString(), anyString())).thenReturn(true);
        when(preferences.findByUserId(anyString())).thenReturn(Optional.empty());
        dispatch = new NotificationDispatchService(sms, whatsApp, preferences, mock(FcmService.class), mock(EmailService.class),
                mock(DeviceTokenRepository.class), logs, mock(UserNotificationRepository.class));
    }

    @Test
    void whatsappAcceptedMeansNoSms() {
        termii.expect(once(), requestTo(WHATSAPP_URL))
                .andExpect(jsonPath("$.phone_number").value("2348031234567"))
                .andExpect(jsonPath("$.template_id").value("tpl-sos"))
                .andExpect(jsonPath("$.data.name").value("Ada"))
                .andRespond(withSuccess("{\"code\":\"ok\",\"message_id\":\"m-1\"}", MediaType.APPLICATION_JSON));

        dispatch.sendWhatsappOrSms(PHONE, SMS, SOS, "SOS_TRIGGERED", "rider-1");

        termii.verify();
        verify(sms, never()).send(anyString(), anyString());
        List<NotificationLog> saved = savedLogs(1);
        assertThat(saved.get(0).getChannel()).isEqualTo(NotificationChannel.WHATSAPP);
        assertThat(saved.get(0).isDelivered()).isTrue();
        assertThat(saved.get(0).getProviderMessageId()).isEqualTo("m-1");
        assertThat(saved.get(0).getEventType()).isEqualTo("SOS_TRIGGERED");
    }

    @Test
    void providerErrorFallsBackToSmsAndRecordsBoth() {
        termii.expect(once(), requestTo(WHATSAPP_URL)).andRespond(withServerError());

        dispatch.sendWhatsappOrSms(PHONE, SMS, SOS, "SOS_TRIGGERED", "rider-1");

        termii.verify();
        verify(sms, times(1)).send(PHONE, SMS);
        List<NotificationLog> saved = savedLogs(2);
        assertThat(saved.get(0).getChannel()).isEqualTo(NotificationChannel.WHATSAPP);
        assertThat(saved.get(0).isDelivered()).isFalse();
        assertThat(saved.get(0).getErrorCode()).isEqualTo("HTTP_500");
        assertThat(saved.get(1).getChannel()).isEqualTo(NotificationChannel.SMS);
        assertThat(saved.get(1).isDelivered()).isTrue();
    }

    @Test
    void disabledMakesNoWhatsappCallAndSendsSms() {
        properties.setEnabled(false);

        dispatch.sendWhatsappOrSms(PHONE, SMS, SOS, "SOS_TRIGGERED", "rider-1");

        termii.verify(); // no expectations: any request to Termii's WhatsApp endpoint fails the test
        verify(sms).send(PHONE, SMS);
        assertThat(savedLogs(1).get(0).getChannel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    void noTemplateForTheNotificationMeansSms() {
        properties.getTemplates().setSosContact(" ");

        dispatch.sendWhatsappOrSms(PHONE, SMS, SOS, "SOS_TRIGGERED", "rider-1");

        termii.verify();
        verify(sms).send(PHONE, SMS);
    }

    @Test
    void userWhoTurnedWhatsappOffGetsSmsOnly() {
        when(preferences.findByUserId("rider-1")).thenReturn(Optional.of(
                NotificationPreferences.builder().userId("rider-1").whatsappOptIn(false).build()));

        dispatch.sendWhatsappOrSms(PHONE, SMS, SOS, "SOS_TRIGGERED", "rider-1");

        termii.verify();
        verify(sms).send(PHONE, SMS);
        assertThat(savedLogs(1).get(0).getChannel()).isEqualTo(NotificationChannel.SMS);
    }

    @Test
    void preferencesThatCannotBeReadMeanSmsNotSilence() {
        when(preferences.findByUserId("rider-1")).thenThrow(new IllegalStateException("db down"));

        dispatch.sendWhatsappOrSms(PHONE, SMS, SOS, "SOS_TRIGGERED", "rider-1");

        termii.verify();
        verify(sms).send(PHONE, SMS);
    }

    @Test
    void opsAlertsWithNoUserIgnorePreferences() {
        properties.getTemplates().setSosOps("tpl-ops");
        termii.expect(once(), requestTo(WHATSAPP_URL))
                .andRespond(withSuccess("{\"code\":\"ok\",\"message_id\":\"m-2\"}", MediaType.APPLICATION_JSON));

        dispatch.sendWhatsappOrSms(PHONE, "ops sms", new WhatsAppMessage(WhatsAppMessageKind.SOS_OPS, Map.of("who", "rider")),
                "SOS_OPS_ALERT", null);

        termii.verify();
        verify(preferences, never()).findByUserId(any());
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void whatsappOtpUsesTheAuthenticationTemplateWithOnlyTheCode() {
        termii.expect(once(), requestTo(WHATSAPP_URL))
                .andExpect(jsonPath("$.template_id").value("tpl-otp"))
                .andExpect(jsonPath("$.data.otp").value("482910"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andRespond(withSuccess("{\"code\":\"ok\",\"message_id\":\"m-3\"}", MediaType.APPLICATION_JSON));

        dispatch.sendOtp(PHONE, "Your CoRyd verification code is 482910.", "482910", "OTP_ISSUED", OtpChannel.WHATSAPP);

        termii.verify();
        verify(sms, never()).send(anyString(), anyString());
    }

    @Test
    void whatsappOtpFailureStillDeliversTheCodeBySms() {
        termii.expect(once(), requestTo(WHATSAPP_URL)).andRespond(withServerError());

        dispatch.sendOtp(PHONE, "Your CoRyd verification code is 482910.", "482910", "OTP_ISSUED", OtpChannel.WHATSAPP);

        verify(sms).send(PHONE, "Your CoRyd verification code is 482910.");
    }

    @Test
    void smsOtpNeverTouchesWhatsapp() {
        dispatch.sendOtp(PHONE, "code", "1", "OTP_ISSUED", OtpChannel.SMS);

        termii.verify();
        verify(sms).send(PHONE, "code");
    }

    private List<NotificationLog> savedLogs(int expected) {
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }
}
