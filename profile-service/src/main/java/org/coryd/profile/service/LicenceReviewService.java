package org.coryd.profile.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.profile.client.SmileIdClient;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.dto.LicenceReviewResponse;
import org.coryd.profile.entity.VerificationCheck;
import org.coryd.profile.repository.VerificationCheckRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The Driver's Licence review screen: the licence a driver verified through Smile ID, shown in the console. Images are
 * fetched from Smile ID each time - we never keep them - using the job its result named.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenceReviewService {

    private final VerificationCheckRepository verificationCheckRepository;
    private final SmileIdClient smileIdClient;
    private final SmileIdProperties smileIdProperties;
    private final ObjectMapper objectMapper;

    public LicenceReviewResponse licence(String driverId, UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can view a driver's licence check");
        }
        VerificationCheck check = verificationCheckRepository.findByDriverId(driverId).stream()
                .filter(c -> c.getCheckType() == VerificationCheckType.DRIVERS_LICENCE)
                .max(Comparator.comparing(VerificationCheck::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseThrow(() -> new ResourceNotFoundException("This driver has no licence check yet"));

        JsonNode result = read(check.getRawResponse());
        JsonNode params = result == null ? null : field(result, "partner_params", "partnerParams");
        String smileUserId = text(params, "user_id", "userId");
        String smileJobId = check.getProviderReference() != null ? check.getProviderReference() : text(params, "job_id", "jobId");
        JsonNode idFields = result == null ? null : field(result, "id_fields", "idFields");
        LicenceReviewResponse.LicenceDetails licence = idFields == null ? null : new LicenceReviewResponse.LicenceDetails(
                fullName(idFields), text(idFields, "id_number", "idNumber"), text(idFields, "date_of_birth", "dateOfBirth"),
                text(idFields, "expiration_date", "expirationDate"));

        List<LicenceReviewResponse.Image> images = List.of();
        String unavailable = null;
        if (!smileIdProperties.isConfigured()) {
            unavailable = "Smile ID isn't configured here";
        } else if (smileUserId == null || smileJobId == null) {
            unavailable = "This check has no Smile ID job to fetch images from";
        } else {
            try {
                images = smileIdClient.jobImages(smileUserId, smileJobId).entrySet().stream()
                        .map(e -> new LicenceReviewResponse.Image(kind(e.getKey()), e.getKey(), e.getValue()))
                        .sorted(Comparator.comparingInt(i -> order(i.kind())))
                        .toList();
                if (images.isEmpty()) {
                    unavailable = "Smile ID has no images for this job";
                }
            } catch (Exception e) {
                unavailable = e.getMessage();
            }
        }
        return new LicenceReviewResponse(smileJobId, check.getUpdatedAt(), check.getOutcome() == null ? null : check.getOutcome().name(),
                check.getProviderSummary(), licence, images, unavailable);
    }

    /** Smile ID's names vary by product - id_card_image, id_card_back, selfie_image... */
    static String kind(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("selfie") || n.contains("liveness")) {
            return "SELFIE";
        }
        if (n.contains("back")) {
            return "LICENCE_BACK";
        }
        if (n.contains("id_card") || n.contains("document") || n.contains("front") || n.contains("id_image")) {
            return "LICENCE_FRONT";
        }
        return "OTHER";
    }

    private static int order(String kind) {
        return switch (kind) {
            case "LICENCE_FRONT" -> 0;
            case "LICENCE_BACK" -> 1;
            case "SELFIE" -> 2;
            default -> 3;
        };
    }

    private JsonNode read(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Could not read a stored Smile ID result: {}", e.getMessage());
            return null;
        }
    }

    private static JsonNode field(JsonNode node, String... names) {
        for (String name : names) {
            if (node != null && node.hasNonNull(name)) {
                return node.get(name);
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null || value.asText().isBlank() ? null : value.asText();
    }

    private static String fullName(JsonNode idFields) {
        String full = text(idFields, "full_name", "fullName");
        if (full != null) {
            return full;
        }
        String joined = String.join(" ", java.util.stream.Stream.of(text(idFields, "first_name", "firstName"),
                text(idFields, "other_names", "otherNames"), text(idFields, "last_name", "lastName")).filter(Objects::nonNull).toList());
        return joined.isBlank() ? null : joined;
    }
}
