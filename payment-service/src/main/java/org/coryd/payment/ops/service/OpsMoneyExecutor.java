package org.coryd.payment.ops.service;

import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestType;

/** Runs one kind of ops money request once it's allowed to run - at/below the threshold, or approved by a second admin. */
public interface OpsMoneyExecutor {

    OpsRequestType type();

    /**
     * Called inside the caller's transaction with the request EXECUTING; leaves it COMPLETED or FAILED and returns the
     * saved row. Throws BadRequestException when it can't run at all right now (e.g. less is refundable than it asks for),
     * which rolls the whole action back.
     */
    OpsMoneyRequest execute(OpsMoneyRequest request, OpsActor actor);
}
