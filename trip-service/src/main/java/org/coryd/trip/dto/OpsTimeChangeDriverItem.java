package org.coryd.trip.dto;

import java.time.Instant;

/** A driver who keeps moving trips riders have booked at the last minute - for the Ops Console. */
public record OpsTimeChangeDriverItem(String driverId, long lastMinuteChanges, long totalChanges, Instant lastChangedAt) {
}
