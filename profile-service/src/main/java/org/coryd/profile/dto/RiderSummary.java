package org.coryd.profile.dto;

import org.coryd.profile.entity.RiderProfile;

/** Lightweight, search-result-safe projection of RiderProfile - name/photo only, for enriching a driver's Trip Receipt with who each passenger was. */
public record RiderSummary(String userId, String fullName, String photoUrl) {

    public static RiderSummary from(RiderProfile profile) {
        return new RiderSummary(profile.getUserId(), profile.getFullName(), profile.getPhotoUrl());
    }
}
