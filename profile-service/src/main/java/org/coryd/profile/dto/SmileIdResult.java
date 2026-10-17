package org.coryd.profile.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * A Smile ID result (Biometric KYC for the NIN, Document Verification for a driver's licence) as delivered to our webhook - the parts we act on or keep for audit.
 * status is clear, attention, block or error; reason says why. id_fields is null unless the NIN was found.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SmileIdResult(
        String status,
        String message,
        String reason,
        String product,
        String createdAt,
        String completedAt,
        PartnerParams partnerParams,
        IdFields idFields,
        Map<String, String> imageLinks,
        String kycReceipt,
        Antifraud antifraud
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PartnerParams(String jobId, String userId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record IdFields(String firstName, String lastName, String otherNames, String fullName, String idNumber,
                           String idType, String dateOfBirth, String expirationDate, String gender, String phoneNumber,
                           String photoUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Antifraud(FraudRisk fraudRisk, SmileSecure smileSecure, Summary summary) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FraudRisk(List<String> riskIndicators, String riskLevel, Integer riskScore) {
    }

    /** suspect_users: other accounts Smile ID has seen with the same NIN or face. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SmileSecure(String status, String message, List<SuspectUser> suspectUsers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SuspectUser(String userId, List<String> reasons) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(Boolean fraudDetected, List<String> fraudSources) {
    }

    public String jobId() {
        return partnerParams == null ? null : partnerParams.jobId();
    }

    /** The copy kept for audit - links to the selfie, NIN photo and receipt removed (signed URLs into Smile ID's storage). */
    public SmileIdResult forAudit() {
        IdFields fields = idFields == null ? null : new IdFields(idFields.firstName(), idFields.lastName(), idFields.otherNames(),
                idFields.fullName(), idFields.idNumber(), idFields.idType(), idFields.dateOfBirth(), idFields.expirationDate(),
                idFields.gender(), idFields.phoneNumber(), idFields.photoUrl() == null ? null : "[removed]");
        return new SmileIdResult(status, message, reason, product, createdAt, completedAt, partnerParams, fields, null, null, antifraud);
    }
}
