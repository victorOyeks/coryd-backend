package org.coryd.common.settings;

import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The catalogue's validation and the in-memory registry every service reads settings from. */
class PlatformSettingsTest {

    @Test
    void valuesAreCheckedAgainstTheirRangeAndStoredInOneSpelling() {
        assertThat(PlatformSetting.COMMISSION_PERCENT.normalize(" 7.50 ")).isEqualTo("7.5");
        assertThat(PlatformSetting.BOOKING_GRACE_MINUTES.normalize("15")).isEqualTo("15");
        assertThat(PlatformSetting.SOS_REALERT_MINUTES.normalize("30, 5,15,5")).isEqualTo("5,15,30");
        assertThatThrownBy(() -> PlatformSetting.COMMISSION_PERCENT.normalize("26"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 0 and 25");
        assertThatThrownBy(() -> PlatformSetting.LATE_CANCEL_FEE_PERCENT.normalize("-1")).hasMessageContaining("between 0 and 50");
        assertThatThrownBy(() -> PlatformSetting.BOOKING_GRACE_MINUTES.normalize("2.5")).hasMessageContaining("whole number");
        assertThatThrownBy(() -> PlatformSetting.COMMISSION_PERCENT.normalize("5.125")).hasMessageContaining("2 decimal places");
        assertThatThrownBy(() -> PlatformSetting.MONEY_APPROVAL_THRESHOLD.normalize("lots")).hasMessageContaining("must be a number");
        assertThatThrownBy(() -> PlatformSetting.SOS_REALERT_MINUTES.normalize("1,2,3,4,5,6,7")).hasMessageContaining("at most 6");
        assertThatThrownBy(() -> PlatformSetting.SOS_REALERT_MINUTES.normalize("5,999")).hasMessageContaining("between 1 and 240");
        assertThatThrownBy(() -> PlatformSetting.STUCK_TRIP_GRACE_HOURS.normalize(" ")).hasMessageContaining("needs a value");
    }

    @Test
    void booleanTimeAndEmailListSettingsAreCheckedAndStoredInOneSpelling() {
        assertThat(PlatformSetting.ONBOARDING_REMINDERS_ENABLED.normalize(" Yes ")).isEqualTo("true");
        assertThat(PlatformSetting.ONBOARDING_REMINDERS_ENABLED.normalize("off")).isEqualTo("false");
        assertThatThrownBy(() -> PlatformSetting.VERIFICATION_DIGEST_ENABLED.normalize("maybe")).hasMessageContaining("true or false");

        assertThat(PlatformSetting.ONBOARDING_REMINDER_TIME.normalize("8:00")).isEqualTo("08:00");
        assertThat(PlatformSetting.VERIFICATION_DIGEST_TIME.normalize("23:59")).isEqualTo("23:59");
        for (String bad : List.of("24:00", "8", "08:60", "8am", "08:00:00")) {
            assertThatThrownBy(() -> PlatformSetting.ONBOARDING_REMINDER_TIME.normalize(bad)).as(bad).hasMessageContaining("24-hour time");
        }

        PlatformSetting extra = PlatformSetting.VERIFICATION_DIGEST_EXTRA_RECIPIENTS;
        assertThat(extra.normalize("Ops@CoRyd.ng; ada@coryd.ng, ops@coryd.ng")).isEqualTo("ada@coryd.ng,ops@coryd.ng");
        assertThat(extra.normalize("NONE")).isEqualTo(PlatformSetting.NONE);
        assertThatThrownBy(() -> extra.normalize("ada@coryd.ng, not-an-email")).hasMessageContaining("'not-an-email' isn't an email address");
        assertThatThrownBy(() -> extra.normalize(" , ")).hasMessageContaining("at least one address");
        String eleven = String.join(",", java.util.stream.IntStream.range(0, 11).mapToObj(i -> "a" + i + "@coryd.ng").toList());
        assertThatThrownBy(() -> extra.normalize(eleven)).hasMessageContaining("at most 10");

        assertThat(PlatformSetting.ONBOARDING_REMINDER_DAILY_DAYS.normalize("14")).isEqualTo("14");
        assertThatThrownBy(() -> PlatformSetting.ONBOARDING_REMINDER_DAILY_DAYS.normalize("0")).hasMessageContaining("between 1 and 60");
        assertThatThrownBy(() -> PlatformSetting.ONBOARDING_REMINDER_EVERY_DAYS.normalize("31")).hasMessageContaining("between 1 and 30");
        assertThatThrownBy(() -> PlatformSetting.VERIFICATION_DIGEST_HIGHLIGHT_DAYS.normalize("0")).hasMessageContaining("between 1 and 30");
        assertThat(PlatformSetting.ONBOARDING_REMINDER_TIME.min()).as("no numeric range for a time").isNull();
    }

    @Test
    void typedReadersFallBackUntilOpsSetsAValue() {
        PlatformSettings settings = new PlatformSettings();
        assertThat(settings.bool(PlatformSetting.ONBOARDING_REMINDERS_ENABLED, true)).isTrue();
        assertThat(settings.timeOfDay(PlatformSetting.ONBOARDING_REMINDER_TIME, java.time.LocalTime.of(8, 0))).hasToString("08:00");
        assertThat(settings.emails(PlatformSetting.VERIFICATION_DIGEST_EXTRA_RECIPIENTS, List.of("x@coryd.ng"))).containsExactly("x@coryd.ng");

        settings.apply(event("onboarding.reminders-enabled", "false", null, null, 1));
        settings.apply(event("onboarding.reminder-time", "09:30", null, null, 1));
        settings.apply(event("verification.digest-extra-recipients", "a@coryd.ng,b@coryd.ng", null, null, 1));
        assertThat(settings.bool(PlatformSetting.ONBOARDING_REMINDERS_ENABLED, true)).isFalse();
        assertThat(settings.timeOfDay(PlatformSetting.ONBOARDING_REMINDER_TIME, java.time.LocalTime.of(8, 0))).hasToString("09:30");
        assertThat(settings.emails(PlatformSetting.VERIFICATION_DIGEST_EXTRA_RECIPIENTS, List.of())).containsExactly("a@coryd.ng", "b@coryd.ng");

        settings.apply(event("verification.digest-extra-recipients", "none", null, null, 2));
        assertThat(settings.emails(PlatformSetting.VERIFICATION_DIGEST_EXTRA_RECIPIENTS, List.of("x@coryd.ng"))).isEmpty();
        settings.apply(event("onboarding.reminder-time", "garbage", null, null, 2));
        assertThat(settings.timeOfDay(PlatformSetting.ONBOARDING_REMINDER_TIME, java.time.LocalTime.of(8, 0))).as("unreadable").hasToString("08:00");
        assertThat(PlatformSettings.bool(null, PlatformSetting.VERIFICATION_DIGEST_ENABLED, true)).isTrue();
    }

    @Test
    void everyKeyFitsTheAuditLogsTargetIdAndIsUnique() {
        assertThat(PlatformSetting.values()).allSatisfy(s -> assertThat(s.key().length()).isLessThanOrEqualTo(36));
        assertThat(java.util.Arrays.stream(PlatformSetting.values()).map(PlatformSetting::key).distinct().count())
                .isEqualTo(PlatformSetting.values().length);
    }

    @Test
    void theFallbackAppliesUntilOpsSetsAValueAndAfterAReset() {
        PlatformSettings settings = new PlatformSettings();
        assertThat(settings.integer(PlatformSetting.BOOKING_GRACE_MINUTES, 10)).isEqualTo(10);
        settings.apply(event("booking.grace-minutes", "20", null, null, 1));
        assertThat(settings.integer(PlatformSetting.BOOKING_GRACE_MINUTES, 10)).isEqualTo(20);
        settings.apply(event("booking.grace-minutes", null, null, null, 2));
        assertThat(settings.integer(PlatformSetting.BOOKING_GRACE_MINUTES, 10)).as("reset").isEqualTo(10);
        assertThat(PlatformSettings.integer(null, PlatformSetting.BOOKING_GRACE_MINUTES, 10)).as("no registry at all").isEqualTo(10);
    }

    @Test
    void anOlderRecordNeverOverwritesANewerOneWhicheverFeedItCameOn() {
        PlatformSettings settings = new PlatformSettings();
        settings.apply(event("commission.percent", "7", null, null, 5));
        settings.apply(event("commission.percent", "4", null, null, 3));
        assertThat(settings.decimal(PlatformSetting.COMMISSION_PERCENT, BigDecimal.ONE)).isEqualByComparingTo("7");
    }

    @Test
    void aScheduledValueTakesOverAtItsTimeWithoutAnyOtherRecord() {
        PlatformSettings settings = new PlatformSettings();
        Instant at = Instant.parse("2026-12-01T00:00:00Z");
        settings.useClock(Clock.fixed(at.minusSeconds(1), ZoneOffset.UTC));
        settings.apply(event("safety.sos-realert-minutes", "5,15,30", "3,10", at, 4));
        assertThat(settings.integers(PlatformSetting.SOS_REALERT_MINUTES, List.of(1))).containsExactly(5, 15, 30);
        settings.useClock(Clock.fixed(at, ZoneOffset.UTC));
        assertThat(settings.integers(PlatformSetting.SOS_REALERT_MINUTES, List.of(1))).containsExactly(3, 10);
    }

    @Test
    void listenersHearOnlyRealChangesAndUnknownKeysAreIgnored() {
        PlatformSettings settings = new PlatformSettings();
        List<PlatformSetting> heard = new ArrayList<>();
        settings.onChange(heard::add);
        settings.apply(event("commission.percent", "6", null, null, 1));
        settings.apply(event("commission.percent", "6", null, null, 2));
        settings.apply(event("no.such.setting", "1", null, null, 1));
        assertThat(heard).containsExactly(PlatformSetting.COMMISSION_PERCENT);
        assertThat(settings.snapshot()).containsOnlyKeys("commission.percent");
    }

    @Test
    void anUnreadableValueFallsBack() {
        PlatformSettings settings = new PlatformSettings();
        settings.apply(event("trips.stuck-grace-hours", "three", null, null, 1));
        assertThat(settings.integer(PlatformSetting.STUCK_TRIP_GRACE_HOURS, 3)).isEqualTo(3);
    }

    private static PlatformSettingChangedEvent event(String key, String value, String scheduled, Instant from, long version) {
        return PlatformSettingChangedEvent.builder().key(key).value(value).scheduledValue(scheduled).scheduledFrom(from).version(version)
                .changedAt(Instant.now()).build();
    }
}
