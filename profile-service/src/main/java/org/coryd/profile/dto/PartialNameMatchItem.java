package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationCheckType;

import java.time.Instant;

/**
 * Someone approved although only some of their names agreed with the record - checkType NIN_FACE (NIN check),
 * DRIVERS_LICENCE (licence) or NIN_LOOKUP (a driver's NIN against their licence). summary has both names
 * and the Smile ID job. If it's the wrong person, reject them with the usual identity (NIN_FACE / NIN_LOOKUP) or licence decision.
 */
public record PartialNameMatchItem(
        String checkId,
        String userId,
        String firstName,
        String lastName,
        VerificationCheckType checkType,
        Double nameMatchScore,
        String summary,
        Instant approvedAt
) {
}
