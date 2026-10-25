package org.coryd.identity.dto;

/**
 * Everyone CoRyd holds personal data about, for the NDPC registration tiers: every registered account,
 * plus every beta waitlist sign-up who hasn't created an account yet (so nobody is counted twice).
 */
public record DataSubjectCountResponse(long registeredUsers, long waitlistOnly, long total) {
}
