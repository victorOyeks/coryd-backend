package org.coryd.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The NDPC (Nigeria Data Protection Commission) registration tiers, by how many people CoRyd holds personal
 * data about. Ops is emailed once when the count nears a tier (warnPercent of it) and once when it reaches
 * it, so registration or an upgrade is never missed. The tiers follow NDPC's guidance notice - change them
 * here if NDPC updates its numbers or fees.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "ndpc")
public class NdpcThresholdProperties {

    private boolean enabled = true;

    /** The "approaching" email goes out at this percentage of a tier's threshold. */
    private int warnPercent = 80;

    private List<Tier> tiers = List.of(
            new Tier("Ordinary-High (MDP-OHL)", 200, "₦10,000"),
            new Tier("Extra-High (MDP-EHL)", 1_000, "₦100,000"),
            new Tier("Ultra-High (MDP-UHL)", 5_000, "₦250,000"));

    @Getter
    @Setter
    public static class Tier {
        private String name;
        private long threshold;
        private String yearlyFee;

        public Tier() {
        }

        public Tier(String name, long threshold, String yearlyFee) {
            this.name = name;
            this.threshold = threshold;
            this.yearlyFee = yearlyFee;
        }

        /** The count at which the "approaching" email goes out - never above the threshold itself. */
        public long warnAt(int warnPercent) {
            return Math.min(threshold, (long) Math.ceil(threshold * warnPercent / 100.0));
        }
    }
}
