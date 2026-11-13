package org.coryd.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.ProfileServiceClient;
import org.coryd.ops.dto.AccountEditResult;
import org.coryd.ops.dto.EditDetailsRequest;
import org.coryd.ops.dto.EditVehicleRequest;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Account management for the ops console: suspend / reinstate / sign out everywhere (identity-service records them in the
 * account history itself), name / email / vehicle edits and licence tries (made in profile-service, then recorded in the
 * account history here, with the admin and their reason), and the history and deletion requests to read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountOpsService {

    static final String PHONE_NOT_EDITABLE = "Phone numbers can't be changed from the ops console - the phone number is how the "
            + "person signs in, so only they can change it";

    private final IdentityServiceClient identityServiceClient;
    private final ProfileServiceClient profileServiceClient;

    public void suspend(String userId, String reason, CoRydUserDetails admin) {
        identityServiceClient.suspend(userId, reason, admin.getUserId(), name(admin));
    }

    public void reinstate(String userId, String reason, CoRydUserDetails admin) {
        identityServiceClient.reinstate(userId, reason, admin.getUserId(), name(admin));
    }

    public void signOutEverywhere(String userId, String reason, CoRydUserDetails admin) {
        identityServiceClient.signOutEverywhere(userId, reason, admin.getUserId(), name(admin));
    }

    public AccountEditResult editDetails(String userId, EditDetailsRequest request, CoRydUserDetails admin) {
        if (request.phone() != null || request.phoneNumber() != null) {
            throw new BadRequestException(PHONE_NOT_EDITABLE);
        }
        JsonNode result = profileServiceClient.editDetails(userId, request.firstName(), request.lastName(), request.email());
        JsonNode changes = result == null ? null : result.get("changes");
        boolean recorded = record(userId, AccountActionType.DETAILS_EDITED, request.reason(), changes, admin);
        return new AccountEditResult(changes, null, false, recorded);
    }

    public AccountEditResult editVehicle(String driverId, String vehicleId, EditVehicleRequest request, CoRydUserDetails admin) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("make", request.make());
        fields.put("model", request.model());
        fields.put("year", request.year());
        fields.put("color", request.color());
        fields.put("plateNumber", request.plateNumber());
        fields.put("seatCapacity", request.seatCapacity());
        JsonNode result = profileServiceClient.editVehicle(driverId, vehicleId, fields, admin.getUserId(), name(admin));
        JsonNode changes = result == null ? null : result.get("changes");
        boolean recorded = record(driverId, AccountActionType.VEHICLE_EDITED, request.reason(), changes, admin);
        return new AccountEditResult(changes, result == null ? null : result.get("vehicle"),
                result != null && result.path("sentBackToReview").asBoolean(false), recorded);
    }

    /** The driver's licence tries as they are now. */
    public JsonNode resetLicenceAttempts(String driverId, String note, CoRydUserDetails admin) {
        JsonNode attempts = profileServiceClient.resetLicenceAttempts(driverId, note, name(admin));
        record(driverId, AccountActionType.LICENCE_ATTEMPTS_RESET, note, null, admin);
        return attempts;
    }

    /** Called after an identity (NIN) tries reset succeeded in profile-service. */
    public void identityAttemptsReset(String userId, String note, CoRydUserDetails admin) {
        record(userId, AccountActionType.IDENTITY_ATTEMPTS_RESET, note, null, admin);
    }

    public JsonNode history(String userId, int page, int size) {
        return identityServiceClient.accountHistory(userId, Math.max(0, page), Math.clamp(size, 1, 100));
    }

    public JsonNode deletionRequests(AccountDeletionStatus status, int page, int size) {
        return identityServiceClient.accountDeletions(status, Math.max(0, page), Math.clamp(size, 1, 50));
    }

    public JsonNode deletionRequest(String id) {
        return identityServiceClient.accountDeletion(id);
    }

    /** The edit already stands in profile-service - a history that couldn't be written is logged loudly, not a failed edit. */
    private boolean record(String userId, AccountActionType action, String reason, JsonNode changes, CoRydUserDetails admin) {
        try {
            identityServiceClient.recordAccountAction(userId, action, reason, changes, admin.getUserId(), name(admin));
            return true;
        } catch (RuntimeException e) {
            log.error("{} on {} by {} was made but couldn't be added to the account history: {}", action, userId, admin.getUserId(),
                    e.getMessage());
            return false;
        }
    }

    static String name(CoRydUserDetails admin) {
        String name = admin.getFullName();
        return name == null || name.isBlank() ? admin.getUserId() : name;
    }
}
