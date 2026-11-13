package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.ops.client.OpsMoneyClient;
import org.coryd.ops.dto.money.DebtAdjustmentBody;
import org.coryd.ops.dto.money.DecisionBody;
import org.coryd.ops.dto.money.ManualRefundBody;
import org.coryd.ops.dto.money.MoneySort;
import org.coryd.ops.dto.money.MoneyTransactionType;
import org.coryd.ops.dto.money.RetryBody;
import org.coryd.ops.dto.money.RevealBody;
import org.coryd.ops.service.MoneyOpsService;
import org.coryd.common.exception.BadRequestException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Ops Console money (Phase 4) - contract in docs/ops-console/money.md. Every action needs a reason; above the approval
 * threshold (₦50,000 by default) a refund, a retry or a debt adjustment waits for a different admin.
 */
@RestController
@RequestMapping("/money")
@RequiredArgsConstructor
public class MoneyController {

    private final MoneyOpsService money;

    // ---- transactions ------------------------------------------------------------------------------------------------

    @GetMapping("/transactions")
    public ApiResponse<JsonNode> transactions(@RequestParam(required = false) MoneyTransactionType type,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) String riderId,
                                              @RequestParam(required = false) String driverId,
                                              @RequestParam(required = false) String bookingId,
                                              @RequestParam(required = false) String tripId,
                                              @RequestParam(required = false) BigDecimal amountMin,
                                              @RequestParam(required = false) BigDecimal amountMax,
                                              @RequestParam(required = false) String reference,
                                              @RequestParam(required = false) MoneySort sort,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        MultiValueMap<String, String> params = filters(status, from, to, riderId, driverId, bookingId, tripId, amountMin, amountMax, reference);
        put(params, "type", type);
        put(params, "sort", sort);
        paging(params, page, size);
        return ApiResponse.ok(money.get("/transactions", params));
    }

    @GetMapping("/transactions/counts")
    public ApiResponse<JsonNode> transactionCounts(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) String riderId,
                                                   @RequestParam(required = false) String driverId,
                                                   @RequestParam(required = false) String bookingId,
                                                   @RequestParam(required = false) String tripId,
                                                   @RequestParam(required = false) BigDecimal amountMin,
                                                   @RequestParam(required = false) BigDecimal amountMax,
                                                   @RequestParam(required = false) String reference) {
        return ApiResponse.ok(money.get("/transactions/counts",
                filters(status, from, to, riderId, driverId, bookingId, tripId, amountMin, amountMax, reference)));
    }

    @GetMapping("/transactions/{type}/{id}")
    public ApiResponse<JsonNode> transaction(@PathVariable MoneyTransactionType type, @PathVariable String id) {
        return ApiResponse.ok(money.transaction(type.name(), id));
    }

    // ---- manual refunds and the approval queue ----------------------------------------------------------------------

    @GetMapping("/bookings/{bookingId}/refundable")
    public ApiResponse<JsonNode> refundable(@PathVariable String bookingId) {
        return ApiResponse.ok(money.get("/bookings/" + bookingId + "/refundable", new LinkedMultiValueMap<>()));
    }

    @PostMapping("/bookings/{bookingId}/refunds")
    public ApiResponse<JsonNode> refund(@PathVariable String bookingId, @Valid @RequestBody ManualRefundBody body) {
        return relay(money.post("/bookings/" + bookingId + "/refunds", body));
    }

    @GetMapping("/approvals")
    public ApiResponse<JsonNode> approvals(@RequestParam(required = false) List<String> status,
                                           @RequestParam(required = false) List<String> type,
                                           @RequestParam(required = false) String bookingId,
                                           @RequestParam(required = false) String driverId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (status != null) {
            params.put("status", status);
        }
        if (type != null) {
            params.put("type", type);
        }
        put(params, "bookingId", bookingId);
        put(params, "driverId", driverId);
        paging(params, page, size);
        return ApiResponse.ok(money.get("/requests", params));
    }

    @GetMapping("/approvals/{id}")
    public ApiResponse<JsonNode> approval(@PathVariable String id) {
        return ApiResponse.ok(money.get("/requests/" + id, new LinkedMultiValueMap<>()));
    }

    @PostMapping("/approvals/{id}/approve")
    public ApiResponse<JsonNode> approve(@PathVariable String id, @Valid @RequestBody(required = false) DecisionBody body) {
        return relay(money.post("/requests/" + id + "/approve", body));
    }

    @PostMapping("/approvals/{id}/reject")
    public ApiResponse<JsonNode> reject(@PathVariable String id, @Valid @RequestBody(required = false) DecisionBody body) {
        String reason = MoneyOpsService.requireReason(body == null ? null : body.reason());
        return relay(money.post("/requests/" + id + "/reject", new DecisionBody(reason)));
    }

    @PostMapping("/approvals/{id}/cancel")
    public ApiResponse<JsonNode> cancel(@PathVariable String id, @Valid @RequestBody(required = false) DecisionBody body) {
        return relay(money.post("/requests/" + id + "/cancel", body));
    }

    @PostMapping("/approvals/{id}/retry")
    public ApiResponse<JsonNode> retryRequest(@PathVariable String id, @Valid @RequestBody RetryBody body) {
        return relay(money.post("/requests/" + id + "/retry", body));
    }

    // ---- refund failures ---------------------------------------------------------------------------------------------

    @GetMapping("/refund-failures")
    public ApiResponse<JsonNode> refundFailures(@RequestParam(required = false) String kind,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        if (kind != null && !kind.isBlank()) {
            String k = kind.trim().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("CANCELLATION", "STUCK", "MANUAL").contains(k)) {
                throw new BadRequestException("Invalid kind '" + kind + "' - must be one of [CANCELLATION, STUCK, MANUAL]");
            }
            params.add("kind", k);
        }
        paging(params, page, size);
        return ApiResponse.ok(money.get("/refund-failures", params));
    }

    @PostMapping("/refund-failures/{holdId}/retry")
    public ApiResponse<JsonNode> retryRefund(@PathVariable String holdId, @Valid @RequestBody RetryBody body) {
        return relay(money.post("/refund-failures/" + holdId + "/retry", body));
    }

    // ---- payouts -----------------------------------------------------------------------------------------------------

    @GetMapping("/payouts")
    public ApiResponse<JsonNode> payouts(@RequestParam(required = false) String status,
                                         @RequestParam(required = false) String driverId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) BigDecimal amountMin,
                                         @RequestParam(required = false) BigDecimal amountMax,
                                         @RequestParam(required = false) String reference,
                                         @RequestParam(required = false) MoneySort sort,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        MultiValueMap<String, String> params = filters(status, from, to, null, driverId, null, null, amountMin, amountMax, reference);
        put(params, "sort", sort);
        paging(params, page, size);
        return ApiResponse.ok(money.get("/payouts", params));
    }

    @GetMapping("/payouts/counts")
    public ApiResponse<JsonNode> payoutCounts(@RequestParam(required = false) String driverId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(money.get("/payouts/counts", filters(null, from, to, null, driverId, null, null, null, null, null)));
    }

    @GetMapping("/payouts/{id}")
    public ApiResponse<JsonNode> payout(@PathVariable String id) {
        return ApiResponse.ok(money.get("/payouts/" + id, new LinkedMultiValueMap<>()));
    }

    @PostMapping("/payouts/{id}/retry")
    public ApiResponse<JsonNode> retryPayout(@PathVariable String id, @Valid @RequestBody RetryBody body) {
        return relay(money.post("/payouts/" + id + "/retry", body));
    }

    @GetMapping("/drivers/{driverId}/payout-account")
    public ApiResponse<JsonNode> payoutAccount(@PathVariable String driverId) {
        return ApiResponse.ok(money.get("/drivers/" + driverId + "/payout-account", new LinkedMultiValueMap<>()));
    }

    @PostMapping("/drivers/{driverId}/payout-account/reveal")
    public ApiResponse<JsonNode> reveal(@PathVariable String driverId, @Valid @RequestBody RevealBody body) {
        return relay(money.post("/drivers/" + driverId + "/payout-account/reveal", body));
    }

    // ---- earnings and settlement debt --------------------------------------------------------------------------------

    @GetMapping("/drivers/{driverId}/earnings")
    public ApiResponse<JsonNode> earnings(@PathVariable String driverId,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(money.get("/drivers/" + driverId + "/earnings", filters(null, from, to, null, null, null, null, null, null, null)));
    }

    @GetMapping("/drivers/{driverId}/debt-entries")
    public ApiResponse<JsonNode> debtEntries(@PathVariable String driverId, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        paging(params, page, size);
        return ApiResponse.ok(money.get("/drivers/" + driverId + "/debt-entries", params));
    }

    @PostMapping("/drivers/{driverId}/debt-adjustments")
    public ApiResponse<JsonNode> adjustDebt(@PathVariable String driverId, @Valid @RequestBody DebtAdjustmentBody body) {
        return relay(money.post("/drivers/" + driverId + "/debt-adjustments", body));
    }

    // ---- dashboard, pricing, audit -----------------------------------------------------------------------------------

    @GetMapping("/finance/summary")
    public ApiResponse<JsonNode> finance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(money.get("/finance/summary", filters(null, from, to, null, null, null, null, null, null, null)));
    }

    @GetMapping("/pricing")
    public ApiResponse<JsonNode> pricing(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) String corridor,
                                         @RequestParam(defaultValue = "false") boolean outliersOnly) {
        MultiValueMap<String, String> params = filters(null, from, to, null, null, null, null, null, null, null);
        put(params, "corridor", corridor);
        params.add("outliersOnly", String.valueOf(outliersOnly));
        return ApiResponse.ok(money.pricing(params));
    }

    @GetMapping("/audit")
    public ApiResponse<JsonNode> audit(@RequestParam(required = false) String targetId,
                                       @RequestParam(required = false) String actorId,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        put(params, "targetId", targetId);
        put(params, "actorId", actorId);
        put(params, "action", action);
        paging(params, page, size);
        return ApiResponse.ok(money.get("/audit", params));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private MultiValueMap<String, String> filters(String status, LocalDate from, LocalDate to, String riderId, String driverId, String bookingId,
                                                  String tripId, BigDecimal amountMin, BigDecimal amountMax, String reference) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must be on or before to");
        }
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        put(params, "status", status);
        put(params, "from", from);
        put(params, "to", to);
        put(params, "riderId", riderId);
        put(params, "driverId", driverId);
        put(params, "bookingId", bookingId);
        put(params, "amountMin", amountMin == null ? null : amountMin.toPlainString());
        put(params, "amountMax", amountMax == null ? null : amountMax.toPlainString());
        put(params, "reference", reference);
        money.resolveTrip(tripId, params);
        return params;
    }

    private static void put(MultiValueMap<String, String> params, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            params.add(name, String.valueOf(value).trim());
        }
    }

    private static void paging(MultiValueMap<String, String> params, int page, int size) {
        params.add("page", String.valueOf(Math.max(page, 0)));
        params.add("size", String.valueOf(Math.max(1, Math.min(size, 100))));
    }

    private static ApiResponse<JsonNode> relay(OpsMoneyClient.Result result) {
        return ApiResponse.ok(result.message() == null ? "Done" : result.message(), result.data());
    }
}
