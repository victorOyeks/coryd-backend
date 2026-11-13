package org.coryd.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.IdentityReviewRole;
import org.coryd.common.enums.IdentityReviewSort;
import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.ProfileServiceClient;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * The identity (NIN) screen - riders, and drivers on either NIN path. profile-service owns the checks and every rule; this
 * adds each person's ref (R- for anyone with a rider profile, D- otherwise) and signs every action with the admin's name.
 */
@Service
@RequiredArgsConstructor
public class IdentityReviewOpsService {

    private final ProfileServiceClient profileServiceClient;

    public JsonNode list(IdentityReviewTab tab, String search, IdentityReviewRole role, IdentityReviewSort sort,
                         Sort.Direction direction, int page, int size) {
        JsonNode page0 = profileServiceClient.identityPeople(tab, search, role, sort, direction, page, size);
        if (page0 != null && page0.has("content")) {
            page0.get("content").forEach(IdentityReviewOpsService::addRef);
        }
        return page0;
    }

    public JsonNode counts(String search, IdentityReviewRole role) {
        return profileServiceClient.identityPeopleCounts(search, role);
    }

    public JsonNode person(String userId) {
        return withPersonRef(profileServiceClient.identityPerson(userId));
    }

    public JsonNode history(String userId) {
        return profileServiceClient.identityPersonHistory(userId);
    }

    public JsonNode images(String userId, String checkId) {
        return profileServiceClient.identityCheckImages(userId, checkId);
    }

    public JsonNode approve(String userId, String note, CoRydUserDetails admin) {
        return withPersonRef(profileServiceClient.identityAction(userId, "approve", note, null, reviewer(admin)));
    }

    public JsonNode reject(String userId, String reason, CoRydUserDetails admin) {
        return withPersonRef(profileServiceClient.identityAction(userId, "reject", null, reason, reviewer(admin)));
    }

    public JsonNode resetAttempts(String userId, String note, CoRydUserDetails admin) {
        return withPersonRef(profileServiceClient.identityAction(userId, "reset-attempts", note, null, reviewer(admin)));
    }

    private static JsonNode withPersonRef(JsonNode detail) {
        if (detail != null && detail.has("person")) {
            addRef(detail.get("person"));
        }
        return detail;
    }

    private static void addRef(JsonNode node) {
        if (node instanceof ObjectNode object && node.hasNonNull("userId")) {
            boolean rider = node.path("rider").asBoolean(false) || !node.path("driver").asBoolean(false);
            object.put("ref", TripOpsService.ref(rider ? "R" : "D", node.get("userId").asText()));
        }
    }

    private static String reviewer(CoRydUserDetails admin) {
        String name = admin.getFullName();
        return name == null || name.isBlank() ? admin.getUserId() : name;
    }
}
