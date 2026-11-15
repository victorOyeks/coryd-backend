package org.coryd.notification.onboarding;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.common.enums.OnboardingStep;
import org.coryd.notification.broadcast.BroadcastAuditWriter;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The daily onboarding reminders (docs/ops-console/onboarding-reminders.md): everyone who hasn't finished signing up and
 * is waiting on themselves gets one push and one entry in their notifications naming their next step - on the cadence in
 * ReminderCadence, until they finish. Each person is claimed for the Lagos day in one statement before anything is sent
 * (notification.onboarding_reminders), so a restart, a second run or a second instance never reminds anyone twice a day.
 *
 * <p>A dry run works out the same list and sends nothing, records nothing.
 */
@Slf4j
@Service
public class OnboardingReminderService {

    public static final String EVENT_TYPE = "ONBOARDING_REMINDER";
    public static final String JOB = "onboarding-reminders";
    static final int PAGE = 500;
    static final int LISTED = 200;

    /** Someone reminded (or who would be, on a dry run). pushed: devices the push reached; devices: devices on file. */
    public record Reminded(String userId, String role, OnboardingStep step, OnboardingStep.Reason reason, String title, String body,
                           int reminderNumber, int devices, int pushed, boolean pushSkippedByPreference) {
    }

    /**
     * One run. considered: accounts looked at. reminded: how many were (or would be) reminded. skipped: why the others
     * weren't. people: the first 200 reminded.
     */
    public record Run(boolean dryRun, LocalDate day, Instant at, int considered, int reminded, Map<OnboardingReminderPlanner.Outcome, Integer> skipped,
                      int pushesDelivered, int pushesFailed, int withoutDevice, int pushOptedOut, List<Reminded> people, boolean truncated) {
    }

    private final OnboardingDataClient client;
    private final NotificationJobSettings settings;
    private final NamedParameterJdbcTemplate jdbc;
    private final UserNotificationRepository feed;
    private final DeviceTokenRepository devices;
    private final NotificationLogRepository logs;
    private final FcmService fcm;
    private final BroadcastAuditWriter audit;
    private volatile Clock clock = Clock.systemUTC();

    public OnboardingReminderService(OnboardingDataClient client, NotificationJobSettings settings, NamedParameterJdbcTemplate jdbc,
                                     UserNotificationRepository feed, DeviceTokenRepository devices, NotificationLogRepository logs,
                                     FcmService fcm, BroadcastAuditWriter audit) {
        this.client = client;
        this.settings = settings;
        this.jdbc = jdbc;
        this.feed = feed;
        this.devices = devices;
        this.logs = logs;
        this.fcm = fcm;
        this.audit = audit;
    }

    /** Tests only. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    public Run run(boolean dryRun, JobActor actor) {
        Instant now = clock.instant();
        LocalDate today = now.atZone(OnboardingReminderPlanner.LAGOS).toLocalDate();
        int dailyDays = settings.dailyDays();
        int everyDays = settings.everyDays();
        int considered = 0;
        Map<OnboardingReminderPlanner.Outcome, Integer> skipped = new EnumMap<>(OnboardingReminderPlanner.Outcome.class);
        List<Reminded> reminded = new ArrayList<>();
        int count = 0;
        int delivered = 0;
        int failed = 0;
        int noDevice = 0;
        int optedOut = 0;

        String after = null;
        do {
            OnboardingDataClient.CandidatePage page = client.candidates(after, PAGE);
            after = page.nextAfter();
            List<OnboardingDataClient.Candidate> people = page.people() == null ? List.of() : page.people();
            if (people.isEmpty()) {
                break;
            }
            considered += people.size();
            Map<String, OnboardingDataClient.Status> statuses = client.status(people.stream()
                            .map(p -> new OnboardingDataClient.Person(p.userId(), p.roles())).toList()).stream()
                    .collect(Collectors.toMap(OnboardingDataClient.Status::userId, s -> s, (a, b) -> a));
            List<String> ids = people.stream().map(OnboardingDataClient.Candidate::userId).toList();
            Map<String, LocalDate> lastReminded = lastReminded(ids);
            Set<String> pushOptOuts = dryRun ? Set.of() : pushOptOuts(ids);
            Map<String, List<DeviceToken>> tokens = devices(ids);

            for (OnboardingDataClient.Candidate person : people) {
                OnboardingReminderPlanner.Plan plan = OnboardingReminderPlanner.plan(person, statuses.get(person.userId()), today,
                        lastReminded.get(person.userId()), dailyDays, everyDays);
                if (plan.outcome() != OnboardingReminderPlanner.Outcome.REMIND) {
                    skipped.merge(plan.outcome(), 1, Integer::sum);
                    continue;
                }
                OnboardingReminderCopy.Message message = OnboardingReminderCopy.of(plan.step(), plan.reason(), plan.role(), person.firstName());
                List<DeviceToken> theirDevices = tokens.getOrDefault(person.userId(), List.of());
                if (dryRun) {
                    count++;
                    if (theirDevices.isEmpty()) {
                        noDevice++;
                    }
                    if (reminded.size() < LISTED) {
                        reminded.add(new Reminded(person.userId(), plan.role(), plan.step(), plan.reason(), message.title(), message.body(),
                                0, theirDevices.size(), 0, false));
                    }
                    continue;
                }
                Integer number = claim(person.userId(), plan, today, now);
                if (number == null) {
                    // another run or instance reminded them today, a moment ago
                    skipped.merge(OnboardingReminderPlanner.Outcome.ALREADY_TODAY, 1, Integer::sum);
                    continue;
                }
                count++;
                boolean optOut = pushOptOuts.contains(person.userId());
                int pushed = send(person.userId(), plan, message, optOut ? List.of() : theirDevices);
                delivered += pushed;
                failed += optOut ? 0 : theirDevices.size() - pushed;
                if (theirDevices.isEmpty()) {
                    noDevice++;
                }
                if (optOut) {
                    optedOut++;
                }
                if (reminded.size() < LISTED) {
                    reminded.add(new Reminded(person.userId(), plan.role(), plan.step(), plan.reason(), message.title(), message.body(),
                            number, theirDevices.size(), pushed, optOut && !theirDevices.isEmpty()));
                }
            }
        } while (after != null);

        Run run = new Run(dryRun, today, now, considered, count, skipped, delivered, failed, noDevice, optedOut, reminded, count > reminded.size());
        if (!dryRun && (count > 0 || !actor.system())) {
            Map<String, Object> after2 = new LinkedHashMap<>();
            after2.put("day", today.toString());
            after2.put("considered", considered);
            after2.put("reminded", count);
            after2.put("pushesDelivered", delivered);
            after2.put("withoutDevice", noDevice);
            after2.put("skipped", skipped);
            audit.write(actor.id(), actor.name(), "ONBOARDING_REMINDERS_SENT", "NOTIFICATION_JOB", JOB, null, after2,
                    actor.system() ? "Daily onboarding reminders" : "Run by hand from the ops console");
        }
        log.info("Onboarding reminders{} for {}: {} considered, {} reminded, skipped {}", dryRun ? " (dry run)" : "", today, considered,
                count, skipped);
        return run;
    }

    /**
     * Claims the person for today in one statement: inserts their row, or moves last_reminded_on forward only if it's
     * before today. The reminder's number for them, or null when someone else already claimed today.
     */
    Integer claim(String userId, OnboardingReminderPlanner.Plan plan, LocalDate today, Instant now) {
        List<Integer> numbers = jdbc.queryForList("""
                INSERT INTO notification.onboarding_reminders AS r (user_id, role, last_step, last_reason, last_reminded_on, last_reminded_at,
                                                                    reminder_count, anchor_at, created_at, updated_at)
                VALUES (:userId, :role, :step, :reason, :today, :now, 1, :anchor, :now, :now)
                ON CONFLICT (user_id) DO UPDATE SET role = EXCLUDED.role, last_step = EXCLUDED.last_step, last_reason = EXCLUDED.last_reason,
                    last_reminded_on = EXCLUDED.last_reminded_on, last_reminded_at = EXCLUDED.last_reminded_at,
                    reminder_count = r.reminder_count + 1, anchor_at = EXCLUDED.anchor_at, updated_at = EXCLUDED.updated_at
                WHERE r.last_reminded_on < EXCLUDED.last_reminded_on
                RETURNING reminder_count
                """, new MapSqlParameterSource().addValue("userId", userId).addValue("role", plan.role())
                .addValue("step", plan.step().name()).addValue("reason", plan.reason().name()).addValue("today", Date.valueOf(today))
                .addValue("now", utc(now)).addValue("anchor", plan.anchorAt() == null ? null : utc(plan.anchorAt())),
                Integer.class);
        return numbers.isEmpty() ? null : numbers.get(0);
    }

    /** The feed entry always; a push to each device. The number of devices the push reached. */
    private int send(String userId, OnboardingReminderPlanner.Plan plan, OnboardingReminderCopy.Message message, List<DeviceToken> tokens) {
        feed.save(UserNotification.builder().userId(userId).title(message.title()).body(message.body()).eventType(EVENT_TYPE)
                .step(plan.step().name()).role(plan.role()).build());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("step", plan.step().name());
        data.put("reason", plan.reason().name());
        data.put("role", plan.role());
        int pushed = 0;
        for (DeviceToken token : tokens) {
            FcmService.PushResult result;
            try {
                result = fcm.sendPush(userId, token.getFcmToken(), message.title(), message.body(), EVENT_TYPE, data);
            } catch (Exception e) {
                result = new FcmService.PushResult(false, null, e.getClass().getSimpleName());
            }
            if (result == null) {
                result = new FcmService.PushResult(false, null, "NO_RESULT");
            }
            logs.save(NotificationLog.builder().userId(userId).channel(NotificationChannel.PUSH).eventType(EVENT_TYPE)
                    .delivered(result.delivered()).providerMessageId(result.messageId()).errorCode(result.errorCode()).build());
            if (result.delivered()) {
                pushed++;
            }
        }
        return pushed;
    }

    private Map<String, LocalDate> lastReminded(List<String> ids) {
        Map<String, LocalDate> out = new HashMap<>();
        jdbc.query("SELECT user_id, last_reminded_on FROM notification.onboarding_reminders WHERE user_id IN (:ids)",
                new MapSqlParameterSource("ids", ids), rs -> {
                    out.put(rs.getString(1), rs.getDate(2).toLocalDate());
                });
        return out;
    }

    /** Those who turned announcements (promotional) off - the one opt-out there is. Their reminder still goes in the feed. */
    private Set<String> pushOptOuts(List<String> ids) {
        return Set.copyOf(jdbc.queryForList("SELECT user_id FROM notification.notification_preferences WHERE user_id IN (:ids)"
                + " AND promotional_opt_in = false", new MapSqlParameterSource("ids", ids), String.class));
    }

    private Map<String, List<DeviceToken>> devices(List<String> ids) {
        return devices.findByUserIdIn(ids).stream().collect(Collectors.groupingBy(DeviceToken::getUserId));
    }

    /** As Hibernate writes Instants to TIMESTAMP columns: UTC wall-clock time. */
    static java.time.LocalDateTime utc(Instant instant) {
        return instant == null ? null : java.time.LocalDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
