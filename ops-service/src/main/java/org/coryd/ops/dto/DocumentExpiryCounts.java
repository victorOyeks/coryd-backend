package org.coryd.ops.dto;

/**
 * The Document Expiry Tracker's cards and tab badges. all is every tracked document; each is under exactly one of
 * expiringSoon, expired, verified or notVerified (more than 14 days left but not approved). withoutExpiryDate were
 * uploaded with no expiry date, so can't be tracked.
 */
public record DocumentExpiryCounts(long all, long expiringSoon, long expired, long verified, long notVerified, long withoutExpiryDate) {
}
