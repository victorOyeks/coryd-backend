package org.coryd.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.DriverProfileView;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The parts of a driver or rider profile that come from identity-service (account, devices, suspension history, account
 * history) and profile-service (email, verification summary, vehicles, emergency contacts). Each loads on its own: a
 * service that can't be reached blanks its part, never the whole profile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccountProfileParts {

    static final int RECENT_ACTIONS = 10;

    private final IdentityServiceClient identityServiceClient;
    private final ProfileServiceClient profileServiceClient;
    private final ObjectMapper objectMapper;

    record Parts(JsonNode account, JsonNode profile, JsonNode history) {

        JsonNode accountField(String name) {
            return account == null || account.get(name) == null || account.get(name).isNull() ? null : account.get(name);
        }

        JsonNode profileField(String name) {
            return profile == null || profile.get(name) == null || profile.get(name).isNull() ? null : profile.get(name);
        }

        Instant accountInstant(String name) {
            JsonNode node = accountField(name);
            try {
                return node == null ? null : Instant.parse(node.asText());
            } catch (Exception e) {
                return null;
            }
        }

        JsonNode recentActions() {
            return history == null ? null : history.get("content");
        }
    }

    Parts load(String userId) {
        return new Parts(quietly("identity account", () -> identityServiceClient.account(userId)),
                quietly("profile", () -> profileServiceClient.accountProfile(userId)),
                quietly("account history", () -> identityServiceClient.accountHistory(userId, 0, RECENT_ACTIONS)));
    }

    DriverProfileView.Account account(Parts parts, String ref, Instant joinedAt) {
        return new DriverProfileView.Account(ref, joinedAt, text(parts.accountField("status")), parts.accountField("roles"),
                parts.accountInstant("phoneVerifiedAt"), parts.accountInstant("lastSeenAt"), parts.accountInstant("sessionsValidAfter"),
                parts.accountField("devices"));
    }

    /** The driver's verification summary: status, verifiedSince, items and licence tries from the driver part, plus identity. */
    JsonNode driverVerification(Parts parts) {
        JsonNode driver = parts.profileField("driver");
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("status", driver == null ? null : driver.get("verificationStatus"));
        summary.set("verifiedSince", driver == null ? null : driver.get("verifiedSince"));
        summary.set("items", driver == null ? null : driver.get("items"));
        summary.set("licenceAttempts", driver == null ? null : driver.get("licenceAttempts"));
        summary.set("identity", parts.profileField("identity"));
        return parts.profile == null ? null : summary;
    }

    JsonNode riderVerification(Parts parts) {
        if (parts.profile == null) {
            return null;
        }
        ObjectNode summary = objectMapper.createObjectNode();
        summary.set("identity", parts.profileField("identity"));
        return summary;
    }

    static String email(Parts parts, String role) {
        JsonNode part = parts.profileField(role);
        return part == null || part.get("email") == null || part.get("email").isNull() ? null : part.get("email").asText();
    }

    private static String text(JsonNode node) {
        return node == null ? null : node.asText();
    }

    private static JsonNode quietly(String what, Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("Profile part '{}' unavailable: {}", what, e.getMessage());
            return null;
        }
    }
}
