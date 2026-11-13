package org.coryd.identity.dto;

import org.coryd.common.enums.AccountActionType;

import java.time.Instant;
import java.util.List;

/** One entry of a user's account history, newest first. changes is empty when nothing was edited. */
public record AccountActionView(String id, AccountActionType action, OpsActor actor, String reason,
                                List<RecordAccountActionRequest.Change> changes, Instant at) {
}
