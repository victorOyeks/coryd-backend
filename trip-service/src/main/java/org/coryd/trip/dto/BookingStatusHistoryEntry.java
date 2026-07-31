package org.coryd.trip.dto;

import org.coryd.common.enums.BookingStatus;

import java.time.Instant;

public record BookingStatusHistoryEntry(BookingStatus status, Instant occurredAt) {
}
