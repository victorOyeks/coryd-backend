package org.coryd.profile.dto;

/**
 * The Document Expiry Tracker's cards and tab badges. Each tracked document is counted under exactly one status;
 * withoutExpiryDate are documents uploaded with no expiry date, which can't be tracked.
 */
public record DocumentExpiryCounts(long all, long expiringSoon, long expired, long verified, long notVerified, long withoutExpiryDate) {
}
