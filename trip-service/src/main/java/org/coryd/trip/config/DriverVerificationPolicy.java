package org.coryd.trip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether an unverified driver may post routes. Until the ops dashboard exists nobody can call
 * VerificationAggregationService.decide(), so STRICT blocks every real driver permanently -
 * GRACE lets them operate for a bounded window instead, and keeps a deadline to point at.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "driver.verification")
public class DriverVerificationPolicy {

    public enum Enforcement {
        /** Approval required before the first route. Restore this once ops can approve drivers. */
        STRICT,
        /** Unverified drivers may post for gracePeriodDays after their first route. */
        GRACE,
        /** Never enforced. */
        OFF
    }

    /** Whether identity and vehicle must merely be on file, or actually approved by ops. */
    public enum PrerequisiteLevel {
        /** Uploaded/registered is enough. Works with no ops dashboard. */
        SUBMITTED,
        /** Ops must have approved them. Requires someone reviewing, or no driver can ever post. */
        APPROVED
    }

    private Enforcement enforcement = Enforcement.GRACE;
    private PrerequisiteLevel prerequisiteLevel = PrerequisiteLevel.APPROVED;
    private int gracePeriodDays = 30;

    public boolean blocksUnverified() {
        return enforcement == Enforcement.STRICT;
    }

    public boolean requiresApprovedPrerequisites() {
        return prerequisiteLevel == PrerequisiteLevel.APPROVED;
    }

    public boolean neverEnforces() {
        return enforcement == Enforcement.OFF;
    }

    /** Null when the driver has not posted yet, or when grace does not apply. */
    public Instant deadlineFrom(Instant firstRouteAt) {
        if (enforcement != Enforcement.GRACE || firstRouteAt == null) {
            return null;
        }
        return firstRouteAt.plus(Duration.ofDays(gracePeriodDays));
    }

    public boolean graceExpired(Instant firstRouteAt, Instant now) {
        Instant deadline = deadlineFrom(firstRouteAt);
        return deadline != null && now.isAfter(deadline);
    }
}
