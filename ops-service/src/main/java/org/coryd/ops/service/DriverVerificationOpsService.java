package org.coryd.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.ops.client.ProfileServiceClient;
import org.springframework.stereotype.Service;

/** Ops Console module 2 - the driver verification queue, a driver's four items, and the decision. */
@Service
@RequiredArgsConstructor
public class DriverVerificationOpsService {

    private final ProfileServiceClient profileServiceClient;

    public JsonNode queue() {
        return profileServiceClient.pendingVerifications();
    }

    public JsonNode driver(String driverId) {
        return profileServiceClient.verificationStatus(driverId);
    }

    /** reviewedBy is always the signed-in admin - whatever the console sends for it is replaced, so the
     * audit trail can't be written in someone else's name. */
    public JsonNode decide(String driverId, ObjectNode decisionBatch, CoRydUserDetails admin) {
        String name = admin.getFullName();
        decisionBatch.put("reviewedBy", name == null || name.isBlank() ? admin.getUserId() : name);
        return profileServiceClient.decideVerification(driverId, decisionBatch);
    }
}
