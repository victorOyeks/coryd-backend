package org.coryd.ops.dto;

/** Notify all: drivers sent a reminder, and those left out because they'd had one within the cool-down. */
public record DocumentExpiryNotifyResult(int notified, long skippedRecentlyNotified) {
}
