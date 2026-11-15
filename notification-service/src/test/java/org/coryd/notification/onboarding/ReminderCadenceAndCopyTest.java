package org.coryd.notification.onboarding;

import org.coryd.common.enums.OnboardingStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The cadence (daily for the first days, then every few days, reset by progress), the planner's choices and the words. */
class ReminderCadenceAndCopyTest {

    static final LocalDate SIGNED_UP = LocalDate.of(2026, 11, 1);

    /** Simulates 40 days of runs from sign-up: which days a reminder goes out. */
    private static List<Integer> remindedDays(int daily, int every, int days) {
        List<Integer> out = new ArrayList<>();
        LocalDate last = null;
        for (int d = 0; d <= days; d++) {
            LocalDate today = SIGNED_UP.plusDays(d);
            if (ReminderCadence.check(today, SIGNED_UP, last, daily, every) == ReminderCadence.Verdict.DUE) {
                out.add(d);
                last = today;
            }
        }
        return out;
    }

    @Test
    void dailyForTheFirstFourteenDaysThenEveryThreeDays() {
        assertThat(remindedDays(14, 3, 26)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 17, 20, 23, 26);
        assertThat(remindedDays(2, 5, 13)).containsExactly(1, 2, 7, 12);
    }

    @Test
    void neverOnTheSignUpDayNorTwiceADay() {
        assertThat(ReminderCadence.check(SIGNED_UP, SIGNED_UP, null, 14, 3)).isEqualTo(ReminderCadence.Verdict.TOO_SOON_AFTER_SIGN_UP);
        LocalDate day3 = SIGNED_UP.plusDays(3);
        assertThat(ReminderCadence.check(day3, SIGNED_UP, day3, 14, 3)).isEqualTo(ReminderCadence.Verdict.ALREADY_TODAY);
        assertThat(ReminderCadence.check(SIGNED_UP.plusDays(16), SIGNED_UP, SIGNED_UP.plusDays(14), 14, 3))
                .isEqualTo(ReminderCadence.Verdict.NOT_DUE);
    }

    @Test
    void progressStartsTheDailyStretchAgain() {
        LocalDate progressed = SIGNED_UP.plusDays(20);
        LocalDate lastReminder = SIGNED_UP.plusDays(20);
        assertThat(ReminderCadence.check(SIGNED_UP.plusDays(21), progressed, lastReminder, 14, 3)).isEqualTo(ReminderCadence.Verdict.DUE);
        assertThat(ReminderCadence.check(SIGNED_UP.plusDays(22), progressed, SIGNED_UP.plusDays(21), 14, 3)).isEqualTo(ReminderCadence.Verdict.DUE);
        assertThat(ReminderCadence.check(progressed, progressed, SIGNED_UP.plusDays(17), 14, 3))
                .as("the day they made progress").isEqualTo(ReminderCadence.Verdict.TOO_SOON_AFTER_SIGN_UP);
        assertThat(ReminderCadence.check(SIGNED_UP.plusDays(40), SIGNED_UP, null, 14, 3))
                .as("never reminded and long past the daily stretch").isEqualTo(ReminderCadence.Verdict.DUE);
    }

    // ---- the planner ---------------------------------------------------------------------------------------------

    static final Instant SIGNUP = Instant.parse("2026-11-01T09:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private static OnboardingDataClient.Candidate person(boolean terms, String... roles) {
        return new OnboardingDataClient.Candidate("u-1", "Ada", List.of(roles), SIGNUP, terms ? SIGNUP : null);
    }

    private static OnboardingDataClient.RoleStatus role(String role, boolean complete, boolean waiting, OnboardingStep... steps) {
        return new OnboardingDataClient.RoleStatus(role, complete,
                java.util.Arrays.stream(steps).map(s -> new OnboardingDataClient.Step(s, OnboardingStep.Reason.NOT_DONE)).toList(), waiting, null);
    }

    private static OnboardingReminderPlanner.Plan plan(OnboardingDataClient.Candidate p, OnboardingDataClient.RoleStatus... roles) {
        return OnboardingReminderPlanner.plan(p, new OnboardingDataClient.Status("u-1", List.of(roles)), TODAY, null, 14, 3);
    }

    @Test
    void termsComeAfterTheProfileAndBeforeTheNin() {
        assertThat(plan(person(false, "RIDER"), role("RIDER", false, false, OnboardingStep.PROFILE, OnboardingStep.NIN)).step())
                .isEqualTo(OnboardingStep.PROFILE);
        assertThat(plan(person(false, "RIDER"), role("RIDER", false, false, OnboardingStep.NIN)).step()).isEqualTo(OnboardingStep.TERMS);
        assertThat(plan(person(false, "RIDER"), role("RIDER", true, false)).step()).as("verified, but never accepted the terms")
                .isEqualTo(OnboardingStep.TERMS);
        assertThat(plan(person(true, "RIDER"), role("RIDER", false, false, OnboardingStep.NIN)).step()).isEqualTo(OnboardingStep.NIN);
    }

    @Test
    void finishedAndWaitingOnOpsAreSkippedAndTheDriverSideComesFirst() {
        assertThat(plan(person(true, "RIDER"), role("RIDER", true, false)).outcome()).isEqualTo(OnboardingReminderPlanner.Outcome.COMPLETE);
        assertThat(plan(person(true, "DRIVER"), role("DRIVER", false, true)).outcome())
                .isEqualTo(OnboardingReminderPlanner.Outcome.WAITING_ON_OPS);
        OnboardingReminderPlanner.Plan both = plan(person(true, "DRIVER", "RIDER"), role("RIDER", false, false, OnboardingStep.PROFILE),
                role("DRIVER", false, false, OnboardingStep.INSURANCE));
        assertThat(both.role()).isEqualTo("DRIVER");
        assertThat(both.step()).isEqualTo(OnboardingStep.INSURANCE);
        OnboardingReminderPlanner.Plan riderLeft = plan(person(true, "DRIVER", "RIDER"), role("RIDER", false, false, OnboardingStep.PROFILE),
                role("DRIVER", false, true));
        assertThat(riderLeft.role()).as("driver side with ops - the rider side is still theirs").isEqualTo("RIDER");
        assertThat(OnboardingReminderPlanner.plan(person(true, "RIDER"), null, TODAY, null, 14, 3).outcome())
                .isEqualTo(OnboardingReminderPlanner.Outcome.NOTHING_TO_DO);
    }

    @Test
    void theAnchorIsTheLaterOfSignUpAndProgress() {
        Instant progress = Instant.parse("2026-11-05T07:00:00Z");
        OnboardingReminderPlanner.Plan plan = OnboardingReminderPlanner.plan(person(true, "DRIVER"), new OnboardingDataClient.Status("u-1",
                        List.of(new OnboardingDataClient.RoleStatus("DRIVER", false,
                                List.of(new OnboardingDataClient.Step(OnboardingStep.VEHICLE, OnboardingStep.Reason.NOT_DONE)), false, progress))),
                TODAY, LocalDate.of(2026, 11, 4), 14, 3);
        assertThat(plan.anchorAt()).isEqualTo(progress);
        assertThat(plan.outcome()).as("progress this morning").isEqualTo(OnboardingReminderPlanner.Outcome.TOO_SOON_AFTER_SIGN_UP);
    }

    // ---- the words -----------------------------------------------------------------------------------------------

    @Test
    void theMessageNamesTheNextStep() {
        assertThat(OnboardingReminderCopy.of(OnboardingStep.INSURANCE, OnboardingStep.Reason.NOT_DONE, "DRIVER", "Ada").body())
                .isEqualTo("Upload your insurance certificate to start driving.");
        assertThat(OnboardingReminderCopy.of(OnboardingStep.NIN, OnboardingStep.Reason.NOT_DONE, "RIDER", null).body())
                .isEqualTo("Verify your NIN to start booking rides.");
        assertThat(OnboardingReminderCopy.of(OnboardingStep.NIN, OnboardingStep.Reason.RETRY, "RIDER", null).body())
                .startsWith("Your NIN check needs another try");
        assertThat(OnboardingReminderCopy.of(OnboardingStep.ROADWORTHINESS, OnboardingStep.Reason.RESUBMIT, "DRIVER", null).body())
                .isEqualTo("CoRyd asked you to upload your roadworthiness certificate again. Upload it to start driving.");
        assertThat(OnboardingReminderCopy.of(OnboardingStep.LICENCE, OnboardingStep.Reason.RETRY, "DRIVER", null).body())
                .contains("driver's licence check needs another try");
        assertThat(OnboardingReminderCopy.of(OnboardingStep.NIN_LOCKED_OUT, OnboardingStep.Reason.LOCKED_OUT, "RIDER", "Ada").body())
                .contains("Contact CoRyd support");
        assertThat(OnboardingReminderCopy.of(OnboardingStep.TERMS, OnboardingStep.Reason.NOT_DONE, "RIDER", "Ada").title())
                .isEqualTo("You're almost there, Ada");
        for (OnboardingStep step : OnboardingStep.values()) {
            for (OnboardingStep.Reason reason : OnboardingStep.Reason.values()) {
                OnboardingReminderCopy.Message m = OnboardingReminderCopy.of(step, reason, "DRIVER", "A very long first name indeed");
                assertThat(m.title().length()).as("fits the feed's title column").isLessThanOrEqualTo(100);
                assertThat(m.body().length()).isLessThanOrEqualTo(500);
            }
        }
    }
}
