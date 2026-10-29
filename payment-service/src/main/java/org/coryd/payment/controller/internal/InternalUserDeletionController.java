package org.coryd.payment.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.service.EarningsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service-to-service only (identity-service, before deleting an account) - gated by InternalApiKeyFilter
 * via the /internal/** prefix. An account can't be deleted while money is still moving: a driver whose riders'
 * payments haven't been released or refunded yet, or - unless payment.account-deletion-allow-owing says otherwise
 * - a driver who owes CoRyd. byOps: ops is deleting it, not its owner.
 */
@Hidden
@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
public class InternalUserDeletionController {

    public enum AllowOwing { NEVER, OPS, ALWAYS }

    private final EarningsService earningsService;
    private final JdbcTemplate jdbc;

    @Value("${payment.account-deletion-allow-owing:NEVER}")
    private AllowOwing allowOwing;

    @GetMapping("/{userId}/deletion-blockers")
    @Transactional(readOnly = true)
    public ApiResponse<List<String>> deletionBlockers(@PathVariable String userId,
                                                      @RequestParam(defaultValue = "false") boolean byOps) {
        List<String> reasons = new ArrayList<>();
        boolean owingAllowed = allowOwing == AllowOwing.ALWAYS || (allowOwing == AllowOwing.OPS && byOps);
        BigDecimal owed = earningsService.amountOwed(userId);
        if (!owingAllowed && owed != null && owed.signum() > 0) {
            reasons.add("you owe CoRyd ₦" + NumberFormat.getNumberInstance(Locale.ENGLISH).format(owed) + " - pay it back first");
        }
        Integer settling = jdbc.queryForObject(
                "SELECT count(*) FROM payment.fare_holds WHERE driver_id = ? AND released = false AND refunded = false",
                Integer.class, userId);
        if (settling != null && settling > 0) {
            reasons.add("riders' payments for your trips are still being settled - cancel any upcoming trips, "
                    + "then try again once they're refunded or paid out");
        }
        return ApiResponse.ok(reasons);
    }
}
