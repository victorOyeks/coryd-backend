package org.coryd.notification.onboarding;

import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.enums.OnboardingStep;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.coryd.common.settings.PlatformSettings;
import org.coryd.notification.broadcast.BroadcastAuditWriter;
import org.coryd.notification.broadcast.BroadcastProperties;
import org.coryd.notification.broadcast.NotificationOpsAudit;
import org.coryd.notification.broadcast.NotificationOpsAuditRepository;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.entity.NotificationPreferences;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.NotificationPreferencesRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two daily jobs against notification-service's own migrations, with identity- and profile-service and the providers
 * stubbed: who is reminded (and who isn't), the push data and feed entry, one reminder per person per Lagos day however
 * many runs or instances, the cadence across days, dry runs, the digest's recipients, order, highlight and emptiness, and
 * the scheduler's time, quiet hours, on/off setting and once-a-day claim.
 */
@DataJpaTest(properties = {"services.internal.api-key=k", "coryd.ops.console-url=https://admin.coryd.ng/"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({OnboardingReminderService.class, VerificationDigestService.class, VerificationDigestEmail.class, NotificationJobSettings.class,
        DailyJobRuns.class, NotificationDailyJobs.class, BroadcastAuditWriter.class, BroadcastProperties.class, PlatformSettings.class,
        JacksonAutoConfiguration.class})
class NotificationDailyJobsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Thu 5 Nov 2026, 08:30 Lagos. */
    static final Instant MORNING = Instant.parse("2026-11-05T07:30:00Z");
    static final Instant SIGNED_UP = Instant.parse("2026-11-01T12:00:00Z");

    @Autowired OnboardingReminderService reminders;
    @Autowired VerificationDigestService digest;
    @Autowired NotificationDailyJobs jobs;
    @Autowired PlatformSettings settings;
    @Autowired DailyJobRuns runs;
    @Autowired UserNotificationRepository feed;
    @Autowired DeviceTokenRepository devices;
    @Autowired NotificationLogRepository logs;
    @Autowired NotificationPreferencesRepository preferences;
    @Autowired NotificationOpsAuditRepository audit;
    @Autowired JdbcTemplate jdbc;

    @MockBean OnboardingDataClient client;
    @MockBean FcmService fcm;
    @MockBean EmailService email;

    @BeforeEach
    void setUp() {
        feed.deleteAll();
        devices.deleteAll();
        logs.deleteAll();
        preferences.deleteAll();
        audit.deleteAll();
        jdbc.update("DELETE FROM notification.onboarding_reminders");
        jdbc.update("DELETE FROM notification.daily_job_runs");
        settings.clear();
        at(MORNING);
        clearInvocations(client, fcm, email);

        List<OnboardingDataClient.Candidate> people = List.of(
                person("rider-nin", "Ada", "RIDER"), person("driver-ins", "Tunde", "DRIVER"), person("rider-done", "Bola", "RIDER"),
                person("driver-ops", "Chidi", "DRIVER"), person("rider-optout", "Dayo", "RIDER"), person("rider-locked", "Efe", "RIDER"));
        when(client.candidates(isNull(), any(Integer.class))).thenReturn(new OnboardingDataClient.CandidatePage(people.subList(0, 3), "rider-done"));
        when(client.candidates(eq("rider-done"), any(Integer.class))).thenReturn(new OnboardingDataClient.CandidatePage(people.subList(3, 6), null));
        when(client.status(any())).thenAnswer(inv -> {
            List<OnboardingDataClient.Person> asked = inv.getArgument(0);
            return asked.stream().map(p -> status(p.userId())).toList();
        });
        devices.save(DeviceToken.builder().userId("rider-nin").fcmToken("tok-nin-1").build());
        devices.save(DeviceToken.builder().userId("rider-nin").fcmToken("tok-nin-2").build());
        devices.save(DeviceToken.builder().userId("rider-optout").fcmToken("tok-optout").build());
        preferences.save(NotificationPreferences.builder().userId("rider-optout").promotionalOptIn(false).build());
        when(fcm.sendPush(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new FcmService.PushResult(true, "m-1", null));
        when(email.sendHtmlWithText(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
    }

    private void at(Instant now) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        reminders.useClock(clock);
        digest.useClock(clock);
        jobs.useClock(clock);
        settings.useClock(clock);
    }

    private static OnboardingDataClient.Candidate person(String id, String name, String role) {
        return new OnboardingDataClient.Candidate(id, name, List.of(role), SIGNED_UP, SIGNED_UP);
    }

    private static OnboardingDataClient.Status status(String id) {
        OnboardingDataClient.RoleStatus role = switch (id) {
            case "rider-nin", "rider-optout" -> new OnboardingDataClient.RoleStatus("RIDER", false,
                    List.of(new OnboardingDataClient.Step(OnboardingStep.NIN, OnboardingStep.Reason.NOT_DONE)), false, null);
            case "driver-ins" -> new OnboardingDataClient.RoleStatus("DRIVER", false,
                    List.of(new OnboardingDataClient.Step(OnboardingStep.INSURANCE, OnboardingStep.Reason.RESUBMIT)), true, null);
            case "rider-done" -> new OnboardingDataClient.RoleStatus("RIDER", true, List.of(), false, null);
            case "driver-ops" -> new OnboardingDataClient.RoleStatus("DRIVER", false, List.of(), true, null);
            case "rider-locked" -> new OnboardingDataClient.RoleStatus("RIDER", false,
                    List.of(new OnboardingDataClient.Step(OnboardingStep.NIN_LOCKED_OUT, OnboardingStep.Reason.LOCKED_OUT)), false, null);
            default -> throw new IllegalArgumentException(id);
        };
        return new OnboardingDataClient.Status(id, List.of(role));
    }

    private void set(String key, String value) {
        settings.apply(PlatformSettingChangedEvent.builder().key(key).value(value).version(System.nanoTime()).changedAt(MORNING).build());
    }

    // ---- onboarding reminders ------------------------------------------------------------------------------------

    @Test
    void everyoneWaitingOnThemselvesGetsOnePushAndOneFeedEntryNamingTheirNextStep() {
        OnboardingReminderService.Run run = reminders.run(false, JobActor.SCHEDULER);

        assertThat(run.considered()).isEqualTo(6);
        assertThat(run.reminded()).isEqualTo(4);
        assertThat(run.skipped()).containsEntry(OnboardingReminderPlanner.Outcome.COMPLETE, 1)
                .containsEntry(OnboardingReminderPlanner.Outcome.WAITING_ON_OPS, 1);
        assertThat(feed.findAll()).extracting(UserNotification::getUserId)
                .containsExactlyInAnyOrder("rider-nin", "driver-ins", "rider-optout", "rider-locked");
        UserNotification insurance = feed.findAll().stream().filter(n -> n.getUserId().equals("driver-ins")).findFirst().orElseThrow();
        assertThat(insurance.getBody()).isEqualTo("CoRyd asked you to upload your insurance certificate again. Upload it to start driving.");
        assertThat(insurance.getEventType()).isEqualTo("ONBOARDING_REMINDER");
        assertThat(insurance.getStep()).isEqualTo("INSURANCE");
        assertThat(insurance.getRole()).isEqualTo("DRIVER");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(fcm, times(2)).sendPush(eq("rider-nin"), anyString(), eq("You're almost there, Ada"), eq("Verify your NIN to start booking rides."),
                eq("ONBOARDING_REMINDER"), data.capture());
        assertThat(data.getValue()).containsEntry("step", "NIN").containsEntry("role", "RIDER").containsEntry("reason", "NOT_DONE");
        verify(fcm).sendPush(eq("rider-optout"), eq("tok-optout"), anyString(), eq("Verify your NIN to start booking rides."),
                eq("ONBOARDING_REMINDER"), anyMap());
        assertThat(logs.findAll()).as("a service message - sent even to someone who turned announcements off")
                .filteredOn(l -> l.getChannel() == NotificationChannel.PUSH).hasSize(3)
                .allSatisfy(l -> assertThat(l.getEventType()).isEqualTo("ONBOARDING_REMINDER"));
        assertThat(run.withoutDevice()).as("driver-ins and rider-locked have no phone - feed entry only").isEqualTo(2);
        assertThat(run.pushesDelivered()).isEqualTo(3);

        assertThat(audit.findAll()).extracting(NotificationOpsAudit::getAction).containsExactly("ONBOARDING_REMINDERS_SENT");
        assertThat(audit.findAll().get(0).getAfterState()).contains("\"reminded\":4");
        assertThat(jdbc.queryForObject("SELECT reminder_count FROM notification.onboarding_reminders WHERE user_id = 'driver-ins'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aSecondRunTheSameLagosDayRemindsNobodyAndTheNextDayRemindsAgain() {
        reminders.run(false, JobActor.SCHEDULER);
        clearInvocations(fcm);
        at(MORNING.plus(Duration.ofHours(10))); // 18:30 Lagos, same day
        OnboardingReminderService.Run again = reminders.run(false, JobActor.admin("admin-1", "Ada Ops"));
        assertThat(again.reminded()).isZero();
        assertThat(again.skipped()).containsEntry(OnboardingReminderPlanner.Outcome.ALREADY_TODAY, 4);
        assertThat(feed.count()).isEqualTo(4);
        verify(fcm, never()).sendPush(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
        assertThat(audit.findAll()).as("a run by hand is audited even when it sent nothing").hasSize(2);

        at(MORNING.plus(Duration.ofDays(1)));
        assertThat(reminders.run(false, JobActor.SCHEDULER).reminded()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT reminder_count FROM notification.onboarding_reminders WHERE user_id = 'rider-nin'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void afterTheDailyStretchItIsEveryFewDaysAndTheSettingsDecideHowMany() {
        set("onboarding.reminder-daily-days", "2");
        set("onboarding.reminder-then-every-days", "3");
        int[] reminded = new int[8];
        for (int d = 0; d < 8; d++) {
            at(Instant.parse("2026-11-01T07:30:00Z").plus(Duration.ofDays(d)));
            reminded[d] = reminders.run(false, JobActor.SCHEDULER).reminded();
        }
        // signed up 1 Nov (day 0): days 1 and 2 daily, then 5
        assertThat(reminded).containsExactly(0, 4, 4, 0, 0, 4, 0, 0);
    }

    @Test
    void twoRunsAtOnceStillRemindEachPersonOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<OnboardingReminderService.Run> run = () -> reminders.run(false, JobActor.SCHEDULER);
            List<java.util.concurrent.Future<OnboardingReminderService.Run>> both = pool.invokeAll(List.of(run, run));
            int total = both.get(0).get().reminded() + both.get(1).get().reminded();
            assertThat(total).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
        assertThat(feed.count()).isEqualTo(4);
        verify(fcm, times(2)).sendPush(eq("rider-nin"), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void aDryRunListsWhoWouldBeRemindedAndSendsAndRecordsNothing() {
        OnboardingReminderService.Run dry = reminders.run(true, JobActor.admin("admin-1", "Ada Ops"));
        assertThat(dry.dryRun()).isTrue();
        assertThat(dry.reminded()).isEqualTo(4);
        assertThat(dry.people()).extracting(OnboardingReminderService.Reminded::step)
                .contains(OnboardingStep.NIN, OnboardingStep.INSURANCE, OnboardingStep.NIN_LOCKED_OUT);
        assertThat(feed.count()).isZero();
        assertThat(logs.count()).isZero();
        assertThat(audit.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.onboarding_reminders", Integer.class)).isZero();
        verify(fcm, never()).sendPush(anyString(), anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    // ---- the verification digest ---------------------------------------------------------------------------------

    private void waiting(OnboardingDataClient.WaitingItem... items) {
        long drivers = java.util.Arrays.stream(items).filter(i -> i.kind().equals("DRIVER_DOCUMENTS")).count();
        long identity = java.util.Arrays.stream(items).filter(i -> i.kind().equals("IDENTITY_CHECK")).count();
        when(client.verificationWaiting()).thenReturn(new OnboardingDataClient.Waiting(drivers, identity, items.length - drivers - identity,
                List.of(items)));
    }

    private void team() {
        when(client.opsAdmins()).thenReturn(List.of(
                new OnboardingDataClient.OpsAdmin("a1", "Vera Officer", "Vera@CoRyd.ng", "VERIFICATION", "ACTIVE"),
                new OnboardingDataClient.OpsAdmin("a2", "Sam Boss", "sam@coryd.ng", "SUPER_ADMIN", "ACTIVE"),
                new OnboardingDataClient.OpsAdmin("a3", "Fola Finance", "fola@coryd.ng", "FINANCE", "ACTIVE"),
                new OnboardingDataClient.OpsAdmin("a4", "Gone Officer", "gone@coryd.ng", "VERIFICATION", "DEACTIVATED"),
                new OnboardingDataClient.OpsAdmin("a5", "New Officer", "new@coryd.ng", "VERIFICATION", "INVITED"),
                new OnboardingDataClient.OpsAdmin("a6", "No Email", null, "VERIFICATION", "ACTIVE")));
    }

    @Test
    void oneDigestOldestFirstWithLongWaitsHighlightedGoesToEachOfficerOnce() {
        team();
        set("verification.digest-extra-recipients", "vera@coryd.ng,lead@coryd.ng");
        waiting(new OnboardingDataClient.WaitingItem("IDENTITY_CHECK", "u-new", "RIDER", "R-0DE4B2", "Ngozi Eze",
                        "Identity (NIN) check - name mismatch", MORNING.minus(Duration.ofHours(5)), MORNING.minus(Duration.ofHours(5))),
                new OnboardingDataClient.WaitingItem("DRIVER_DOCUMENTS", "d-old", "DRIVER", "D-4F2A91", "Emeka Okafor",
                        "Insurance certificate (re-uploaded)", MORNING.minus(Duration.ofDays(6)), MORNING.minus(Duration.ofDays(4))),
                new OnboardingDataClient.WaitingItem("PARTIAL_NAME_MATCH", "u-mid", "RIDER", "R-7C34F1", "Ade <b>Bold</b>",
                        "Partial name match on NIN check - approved, needs a look", MORNING.minus(Duration.ofDays(2)), null));

        VerificationDigestService.Run run = digest.run(false, JobActor.SCHEDULER);

        assertThat(run.recipients()).as("active verification and super admins, then the extra list, each once")
                .containsExactly("vera@coryd.ng", "sam@coryd.ng", "lead@coryd.ng");
        assertThat(run.rows()).extracting(VerificationDigestService.Row::userId).containsExactly("d-old", "u-mid", "u-new");
        assertThat(run.rows()).extracting(VerificationDigestService.Row::highlighted).containsExactly(true, false, false);
        assertThat(run.counts().total()).isEqualTo(3);
        assertThat(run.sent()).isTrue();
        assertThat(run.delivered()).isEqualTo(3);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email, times(3)).sendHtmlWithText(anyString(), subject.capture(), html.capture(), text.capture());
        assertThat(subject.getValue()).isEqualTo("Verification: 3 waiting (1 over 3 days) · Thu 5 Nov");
        assertThat(html.getValue()).contains("https://admin.coryd.ng/verification-queue/d-old", "https://admin.coryd.ng/identity-checks?person=u-new",
                "3 people waiting", "Driver documents", "Ade &lt;b&gt;Bold&lt;/b&gt;", "4d 0h", "over 3 days")
                .doesNotContain("<b>Bold</b>");
        assertThat(html.getValue().indexOf("Emeka Okafor")).isLessThan(html.getValue().indexOf("Ngozi Eze"));
        assertThat(text.getValue()).contains("! Emeka Okafor (D-4F2A91, Driver)", "- Ngozi Eze (R-0DE4B2, Rider)",
                "https://admin.coryd.ng/verification-queue/d-old");
        assertThat(logs.findAll()).filteredOn(l -> l.getChannel() == NotificationChannel.EMAIL)
                .extracting(NotificationLog::getRecipientEmail).containsExactlyInAnyOrder("vera@coryd.ng", "sam@coryd.ng", "lead@coryd.ng");
        assertThat(audit.findAll()).extracting(NotificationOpsAudit::getAction).containsExactly("VERIFICATION_DIGEST_SENT");
        assertThat(audit.findAll().get(0).getAfterState()).contains("\"recipients\":3", "\"waiting\":3");
    }

    @Test
    void noEmailWhenNobodyIsWaitingAndADryRunSendsNothing() {
        team();
        waiting();
        VerificationDigestService.Run empty = digest.run(false, JobActor.SCHEDULER);
        assertThat(empty.sent()).isFalse();
        assertThat(empty.notSentBecause()).isEqualTo("Nobody is waiting for a verification officer");
        assertThat(audit.count()).as("a scheduled run that sent nothing isn't audited").isZero();

        waiting(new OnboardingDataClient.WaitingItem("IDENTITY_CHECK", "u-1", "RIDER", "R-000001", "A B", "Identity (NIN) check",
                MORNING.minus(Duration.ofHours(1)), MORNING.minus(Duration.ofHours(1))));
        VerificationDigestService.Run dry = digest.run(true, JobActor.admin("admin-1", "Ada Ops"));
        assertThat(dry.rows()).hasSize(1);
        assertThat(dry.recipients()).hasSize(2);
        assertThat(dry.subject()).startsWith("Verification: 1 waiting");
        assertThat(dry.sent()).isFalse();
        verify(email, never()).sendHtmlWithText(anyString(), anyString(), anyString(), anyString());
        assertThat(logs.count()).isZero();
        assertThat(audit.count()).isZero();
    }

    // ---- the scheduler -------------------------------------------------------------------------------------------

    @Test
    void theJobsRunOnceADayFromTheirTimeAndRemindersWaitForQuietHoursToEnd() {
        team();
        waiting(new OnboardingDataClient.WaitingItem("IDENTITY_CHECK", "u-1", "RIDER", "R-000001", "A B", "Identity (NIN) check",
                MORNING.minus(Duration.ofHours(1)), MORNING.minus(Duration.ofHours(1))));

        at(Instant.parse("2026-11-05T06:59:00Z")); // 07:59 Lagos
        jobs.onboardingReminders();
        jobs.verificationDigest();
        assertThat(feed.count()).isZero();
        verify(email, never()).sendHtmlWithText(anyString(), anyString(), anyString(), anyString());

        at(Instant.parse("2026-11-05T07:00:00Z")); // 08:00
        jobs.onboardingReminders();
        jobs.verificationDigest();
        jobs.onboardingReminders();
        jobs.verificationDigest();
        assertThat(feed.count()).isEqualTo(4);
        verify(email, times(2)).sendHtmlWithText(anyString(), anyString(), anyString(), anyString());
        assertThat(runs.status(OnboardingReminderService.JOB, java.time.LocalDate.of(2026, 11, 5))).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT recipients FROM notification.daily_job_runs WHERE job_name = 'verification-digest'", Integer.class))
                .isEqualTo(2);

        // a reminder time inside quiet hours waits for them to end (07:00 Lagos)
        set("onboarding.reminder-time", "05:00");
        at(Instant.parse("2026-11-06T04:30:00Z")); // 05:30 Lagos, 6 Nov
        jobs.onboardingReminders();
        assertThat(feed.count()).isEqualTo(4);
        at(Instant.parse("2026-11-06T06:00:00Z")); // 07:00
        jobs.onboardingReminders();
        assertThat(feed.count()).isEqualTo(8);
    }

    @Test
    void switchedOffMeansNoRun() {
        set("onboarding.reminders-enabled", "false");
        set("verification.digest-enabled", "false");
        team();
        waiting();
        jobs.onboardingReminders();
        jobs.verificationDigest();
        assertThat(feed.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.daily_job_runs", Integer.class)).isZero();
    }

    @Test
    void aFailedRunIsRetriedButADoneOneIsNot() {
        java.time.LocalDate day = java.time.LocalDate.of(2026, 11, 5);
        assertThat(runs.claim("x", day, MORNING)).isTrue();
        assertThat(runs.claim("x", day, MORNING)).as("running").isFalse();
        runs.failed("x", day, MORNING, "boom");
        assertThat(runs.claim("x", day, MORNING)).isTrue();
        runs.done("x", day, MORNING, 1, 1);
        assertThat(runs.claim("x", day, MORNING.plus(Duration.ofHours(5)))).isFalse();
        assertThat(runs.claim("y", day, MORNING)).isTrue();
        assertThat(runs.claim("y", day, MORNING.plus(Duration.ofHours(3)))).as("a run stuck for hours is taken over").isTrue();
    }
}
