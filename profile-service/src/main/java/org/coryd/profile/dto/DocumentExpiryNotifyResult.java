package org.coryd.profile.dto;

/** Notify all: how many drivers were sent a reminder, and how many were left out because they'd just had one. */
public record DocumentExpiryNotifyResult(int notified, long skippedRecentlyNotified) {
}
