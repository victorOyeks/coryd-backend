package org.coryd.ops.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What an edit changed: changes is [{field, before, after}]. For a vehicle: vehicle is the vehicle now, sentBackToReview
 * true when its registration went back to review. historyRecorded is false in the rare case the edit was saved but
 * identity-service couldn't add it to the account history (it's logged) - the edit itself stands.
 */
public record AccountEditResult(JsonNode changes, JsonNode vehicle, boolean sentBackToReview, boolean historyRecorded) {
}
