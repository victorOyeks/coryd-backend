package org.coryd.notification.onboarding;

import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;

/**
 * The two daily jobs' settings (docs/ops-console/onboarding-reminders.md): what ops set in Platform Settings, else this
 * service's own property - the same env vars trip-service shows as the defaults.
 */
@Component
public class NotificationJobSettings {

    private final PlatformSettings settings;
    private final boolean remindersEnabled;
    private final LocalTime reminderTime;
    private final int dailyDays;
    private final int everyDays;
    private final boolean digestEnabled;
    private final LocalTime digestTime;
    private final int highlightDays;
    private final List<String> extraRecipients;

    public NotificationJobSettings(@Autowired(required = false) PlatformSettings settings,
                                   @Value("${onboarding.reminders.enabled:true}") String remindersEnabled,
                                   @Value("${onboarding.reminders.time:08:00}") String reminderTime,
                                   @Value("${onboarding.reminders.daily-days:14}") String dailyDays,
                                   @Value("${onboarding.reminders.every-days:3}") String everyDays,
                                   @Value("${verification.digest.enabled:true}") String digestEnabled,
                                   @Value("${verification.digest.time:08:00}") String digestTime,
                                   @Value("${verification.digest.highlight-days:3}") String highlightDays,
                                   @Value("${verification.digest.extra-recipients:}") String extraRecipients) {
        this.settings = settings;
        this.remindersEnabled = Boolean.parseBoolean(spelled(PlatformSetting.ONBOARDING_REMINDERS_ENABLED, remindersEnabled, "true"));
        this.reminderTime = PlatformSetting.parseTime(spelled(PlatformSetting.ONBOARDING_REMINDER_TIME, reminderTime, "08:00"));
        this.dailyDays = Integer.parseInt(spelled(PlatformSetting.ONBOARDING_REMINDER_DAILY_DAYS, dailyDays, "14"));
        this.everyDays = Integer.parseInt(spelled(PlatformSetting.ONBOARDING_REMINDER_EVERY_DAYS, everyDays, "3"));
        this.digestEnabled = Boolean.parseBoolean(spelled(PlatformSetting.VERIFICATION_DIGEST_ENABLED, digestEnabled, "true"));
        this.digestTime = PlatformSetting.parseTime(spelled(PlatformSetting.VERIFICATION_DIGEST_TIME, digestTime, "08:00"));
        this.highlightDays = Integer.parseInt(spelled(PlatformSetting.VERIFICATION_DIGEST_HIGHLIGHT_DAYS, highlightDays, "3"));
        this.extraRecipients = extraRecipients == null || extraRecipients.isBlank() ? List.of()
                : PlatformSetting.parseEmails(spelled(PlatformSetting.VERIFICATION_DIGEST_EXTRA_RECIPIENTS, extraRecipients, PlatformSetting.NONE));
    }

    public boolean remindersEnabled() {
        return PlatformSettings.bool(settings, PlatformSetting.ONBOARDING_REMINDERS_ENABLED, remindersEnabled);
    }

    public LocalTime reminderTime() {
        return PlatformSettings.timeOfDay(settings, PlatformSetting.ONBOARDING_REMINDER_TIME, reminderTime);
    }

    public int dailyDays() {
        return PlatformSettings.integer(settings, PlatformSetting.ONBOARDING_REMINDER_DAILY_DAYS, dailyDays);
    }

    public int everyDays() {
        return PlatformSettings.integer(settings, PlatformSetting.ONBOARDING_REMINDER_EVERY_DAYS, everyDays);
    }

    public boolean digestEnabled() {
        return PlatformSettings.bool(settings, PlatformSetting.VERIFICATION_DIGEST_ENABLED, digestEnabled);
    }

    public LocalTime digestTime() {
        return PlatformSettings.timeOfDay(settings, PlatformSetting.VERIFICATION_DIGEST_TIME, digestTime);
    }

    public int highlightDays() {
        return PlatformSettings.integer(settings, PlatformSetting.VERIFICATION_DIGEST_HIGHLIGHT_DAYS, highlightDays);
    }

    public List<String> extraRecipients() {
        return PlatformSettings.emails(settings, PlatformSetting.VERIFICATION_DIGEST_EXTRA_RECIPIENTS, extraRecipients);
    }

    /** A configured value in the setting's spelling, or the documented default when the env var holds something it wouldn't accept. */
    private static String spelled(PlatformSetting setting, String configured, String fallback) {
        try {
            return setting.normalize(configured);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
