package org.coryd.trip.settings;

import org.coryd.common.settings.PlatformSetting;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The defaults of notification-service's daily jobs (onboarding reminders, verification digest - see
 * docs/ops-console/onboarding-reminders.md), read from the same env vars notification-service reads, in each setting's
 * stored spelling. A value in an env var that the setting wouldn't accept falls back to the documented default.
 */
@Component
public class NotificationJobDefaults {

    private final String remindersEnabled;
    private final String reminderTime;
    private final String dailyDays;
    private final String everyDays;
    private final String digestEnabled;
    private final String digestTime;
    private final String highlightDays;
    private final String extraRecipients;

    public NotificationJobDefaults(
            @Value("${coryd.platform-settings.defaults.onboarding-reminders-enabled:true}") String remindersEnabled,
            @Value("${coryd.platform-settings.defaults.onboarding-reminder-time:08:00}") String reminderTime,
            @Value("${coryd.platform-settings.defaults.onboarding-reminder-daily-days:14}") String dailyDays,
            @Value("${coryd.platform-settings.defaults.onboarding-reminder-every-days:3}") String everyDays,
            @Value("${coryd.platform-settings.defaults.verification-digest-enabled:true}") String digestEnabled,
            @Value("${coryd.platform-settings.defaults.verification-digest-time:08:00}") String digestTime,
            @Value("${coryd.platform-settings.defaults.verification-digest-highlight-days:3}") String highlightDays,
            @Value("${coryd.platform-settings.defaults.verification-digest-extra-recipients:}") String extraRecipients) {
        this.remindersEnabled = remindersEnabled;
        this.reminderTime = reminderTime;
        this.dailyDays = dailyDays;
        this.everyDays = everyDays;
        this.digestEnabled = digestEnabled;
        this.digestTime = digestTime;
        this.highlightDays = highlightDays;
        this.extraRecipients = extraRecipients;
    }

    public String of(PlatformSetting setting) {
        return switch (setting) {
            case ONBOARDING_REMINDERS_ENABLED -> spelled(setting, remindersEnabled, "true");
            case ONBOARDING_REMINDER_TIME -> spelled(setting, reminderTime, "08:00");
            case ONBOARDING_REMINDER_DAILY_DAYS -> spelled(setting, dailyDays, "14");
            case ONBOARDING_REMINDER_EVERY_DAYS -> spelled(setting, everyDays, "3");
            case VERIFICATION_DIGEST_ENABLED -> spelled(setting, digestEnabled, "true");
            case VERIFICATION_DIGEST_TIME -> spelled(setting, digestTime, "08:00");
            case VERIFICATION_DIGEST_HIGHLIGHT_DAYS -> spelled(setting, highlightDays, "3");
            case VERIFICATION_DIGEST_EXTRA_RECIPIENTS -> extraRecipients == null || extraRecipients.isBlank()
                    ? PlatformSetting.NONE : spelled(setting, extraRecipients, PlatformSetting.NONE);
            default -> throw new IllegalArgumentException(setting.key() + " isn't a notification job setting");
        };
    }

    private static String spelled(PlatformSetting setting, String configured, String fallback) {
        try {
            return setting.normalize(configured);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
