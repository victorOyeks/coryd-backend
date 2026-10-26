package org.coryd.profile.dto;

/** ninStatus: CHECKING (result on its way - poll GET /drivers/me), IN_REVIEW (with ops), or MATCH (already confirmed). */
public record NinLookupResponse(String ninStatus, String ninLast4) {
}
