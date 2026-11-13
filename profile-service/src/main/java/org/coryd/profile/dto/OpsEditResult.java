package org.coryd.profile.dto;

import java.util.List;

/**
 * What an ops edit changed: each field with its value before and after (as text). For a vehicle edit, vehicle is the
 * vehicle now and sentBackToReview says its registration went back to review (an approved vehicle's identity changed).
 */
public record OpsEditResult(List<Change> changes, OpsAccountProfile.VehicleView vehicle, boolean sentBackToReview) {

    public record Change(String field, String before, String after) {
    }
}
