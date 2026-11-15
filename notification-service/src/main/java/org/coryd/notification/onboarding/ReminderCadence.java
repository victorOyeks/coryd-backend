package org.coryd.notification.onboarding;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How often someone who hasn't finished signing up is reminded (docs/ops-console/onboarding-reminders.md). Days are Lagos
 * days, counted from the anchor - when they signed up or last made onboarding progress, whichever is later:
 * <ul>
 *   <li>day 0 (the anchor day itself): nothing - they may be signing up right now;</li>
 *   <li>days 1 to dailyDays: every day;</li>
 *   <li>after that: every everyDays days since the last reminder, until they finish.</li>
 * </ul>
 * Never twice on one day. Progress moves the anchor, so the daily stretch starts again.
 */
public final class ReminderCadence {

    public enum Verdict { DUE, TOO_SOON_AFTER_SIGN_UP, ALREADY_TODAY, NOT_DUE }

    private ReminderCadence() {
    }

    public static Verdict check(LocalDate today, LocalDate anchorDay, LocalDate lastRemindedOn, int dailyDays, int everyDays) {
        if (lastRemindedOn != null && !lastRemindedOn.isBefore(today)) {
            return Verdict.ALREADY_TODAY;
        }
        long day = anchorDay == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(anchorDay, today);
        if (day < 1) {
            return Verdict.TOO_SOON_AFTER_SIGN_UP;
        }
        if (day <= dailyDays) {
            return Verdict.DUE;
        }
        // past the daily stretch: every everyDays days - counted from the last reminder since the anchor
        if (lastRemindedOn == null || (anchorDay != null && lastRemindedOn.isBefore(anchorDay))) {
            return Verdict.DUE;
        }
        return ChronoUnit.DAYS.between(lastRemindedOn, today) >= Math.max(1, everyDays) ? Verdict.DUE : Verdict.NOT_DUE;
    }
}
