package org.coryd.profile.dto;

/**
 * A driver's licence-check tries, for ops. lockedOut: every try used - the app tells the driver to contact support.
 * canReset / resetBlockedReason: whether ops may give the tries back, and why not.
 */
public record LicenceAttempts(int used, int max, int left, boolean lockedOut, boolean canReset, String resetBlockedReason) {
}
