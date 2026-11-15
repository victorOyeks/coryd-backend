package org.coryd.notification.onboarding;

import org.coryd.common.enums.OnboardingStep;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Whether one person gets an onboarding reminder today, and about what (docs/ops-console/onboarding-reminders.md). One
 * reminder per person per run: the driver side first if they hold both roles and it has something for them to do, else
 * the rider side; the first step outstanding. Accepting the terms (identity-service) slots in after the profile.
 */
public final class OnboardingReminderPlanner {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final List<String> ROLE_ORDER = List.of("DRIVER", "RIDER");

    /** REMIND, or why not. */
    public enum Outcome { REMIND, COMPLETE, WAITING_ON_OPS, NOTHING_TO_DO, TOO_SOON_AFTER_SIGN_UP, ALREADY_TODAY, NOT_DUE }

    public record Plan(Outcome outcome, String role, OnboardingStep step, OnboardingStep.Reason reason, Instant anchorAt) {
    }

    private OnboardingReminderPlanner() {
    }

    public static Plan plan(OnboardingDataClient.Candidate person, OnboardingDataClient.Status status, LocalDate today,
                            LocalDate lastRemindedOn, int dailyDays, int everyDays) {
        boolean termsAccepted = person.termsAcceptedAt() != null;
        List<OnboardingDataClient.RoleStatus> roles = status == null || status.roles() == null ? List.of() : status.roles();
        Instant anchor = max(person.signedUpAt(), person.termsAcceptedAt());
        for (OnboardingDataClient.RoleStatus role : roles) {
            anchor = max(anchor, role.lastProgressAt());
        }

        OnboardingDataClient.RoleStatus chosen = null;
        OnboardingDataClient.Step next = null;
        boolean anyIncomplete = false;
        boolean anyWaiting = false;
        for (String name : ROLE_ORDER) {
            OnboardingDataClient.RoleStatus role = roles.stream().filter(r -> name.equals(r.role())).findFirst().orElse(null);
            if (role == null || person.roles() == null || !person.roles().contains(name)) {
                continue;
            }
            List<OnboardingDataClient.Step> steps = steps(role, termsAccepted);
            if (role.complete() && termsAccepted) {
                continue;
            }
            anyIncomplete = true;
            anyWaiting |= role.waitingOnOps();
            if (chosen == null && !steps.isEmpty()) {
                chosen = role;
                next = steps.get(0);
            }
        }
        if (chosen == null) {
            Outcome why = !anyIncomplete ? (roles.isEmpty() ? Outcome.NOTHING_TO_DO : Outcome.COMPLETE)
                    : anyWaiting ? Outcome.WAITING_ON_OPS : Outcome.NOTHING_TO_DO;
            return new Plan(why, null, null, null, anchor);
        }
        LocalDate anchorDay = anchor == null ? null : anchor.atZone(LAGOS).toLocalDate();
        Outcome outcome = switch (ReminderCadence.check(today, anchorDay, lastRemindedOn, dailyDays, everyDays)) {
            case DUE -> Outcome.REMIND;
            case ALREADY_TODAY -> Outcome.ALREADY_TODAY;
            case TOO_SOON_AFTER_SIGN_UP -> Outcome.TOO_SOON_AFTER_SIGN_UP;
            case NOT_DUE -> Outcome.NOT_DUE;
        };
        return new Plan(outcome, chosen.role(), next.step(), next.reason(), anchor);
    }

    /** The role's steps in order, with TERMS after the profile when they haven't accepted them. */
    static List<OnboardingDataClient.Step> steps(OnboardingDataClient.RoleStatus role, boolean termsAccepted) {
        List<OnboardingDataClient.Step> out = new ArrayList<>();
        List<OnboardingDataClient.Step> given = role.steps() == null ? List.of()
                : role.steps().stream().filter(Objects::nonNull).filter(s -> s.step() != null).toList();
        given.stream().filter(s -> s.step() == OnboardingStep.PROFILE).forEach(out::add);
        if (!termsAccepted) {
            out.add(new OnboardingDataClient.Step(OnboardingStep.TERMS, OnboardingStep.Reason.NOT_DONE));
        }
        given.stream().filter(s -> s.step() != OnboardingStep.PROFILE && s.step() != OnboardingStep.TERMS).forEach(out::add);
        if (role.complete() && termsAccepted) {
            return List.of();
        }
        return out;
    }

    static Instant max(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
