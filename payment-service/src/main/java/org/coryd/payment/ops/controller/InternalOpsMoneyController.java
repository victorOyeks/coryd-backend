package org.coryd.payment.ops.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.DebtEntryItem;
import org.coryd.payment.ops.dto.DebtAdjustmentRequest;
import org.coryd.payment.ops.dto.DecisionRequest;
import org.coryd.payment.ops.dto.DriverStatementView;
import org.coryd.payment.ops.dto.FinanceSummary;
import org.coryd.payment.ops.dto.ManualRefundRequest;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.PayoutAccountView;
import org.coryd.payment.ops.dto.PayoutDetail;
import org.coryd.payment.ops.dto.PayoutItem;
import org.coryd.payment.ops.dto.RefundFailureItem;
import org.coryd.payment.ops.dto.RefundableView;
import org.coryd.payment.ops.dto.RetryRequest;
import org.coryd.payment.ops.dto.RevealAccountRequest;
import org.coryd.payment.ops.dto.TransactionDetail;
import org.coryd.payment.ops.dto.TransactionFilter;
import org.coryd.payment.ops.dto.TransactionRow;
import org.coryd.payment.ops.dto.TransactionType;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyAuditRepository;
import org.coryd.payment.ops.service.OpsActor;
import org.coryd.payment.ops.service.OpsApprovalService;
import org.coryd.payment.ops.service.OpsEarningsService;
import org.coryd.payment.ops.service.OpsFinanceService;
import org.coryd.payment.ops.service.OpsPayoutService;
import org.coryd.payment.ops.service.OpsRefundFailureService;
import org.coryd.payment.ops.service.OpsRefundService;
import org.coryd.payment.ops.service.OpsTransactionQueryService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
import java.util.Map;

/**
 * Ops Console money (Phase 4) - ops-service only, with the admin's own token: every endpoint checks OPS_ADMIN itself and
 * signs what it does with that admin. Dates are Lagos days, inclusive.
 */
@Hidden
@RestController
@RequestMapping("/internal/ops/money")
@RequiredArgsConstructor
public class InternalOpsMoneyController {

    private final OpsTransactionQueryService transactionQueryService;
    private final OpsRefundService refundService;
    private final OpsApprovalService approvalService;
    private final OpsRefundFailureService refundFailureService;
    private final OpsPayoutService payoutService;
    private final OpsEarningsService earningsService;
    private final OpsFinanceService financeService;
    private final OpsMoneyAuditRepository auditRepository;

    // ---- transactions --------------------------------------------------------------------------------------------

    @GetMapping("/transactions")
    public ApiResponse<PagedResponse<TransactionRow>> transactions(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                   @RequestParam(required = false) TransactionType type,
                                                                   @RequestParam(required = false) String status,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                   @RequestParam(required = false) String riderId,
                                                                   @RequestParam(required = false) String driverId,
                                                                   @RequestParam(required = false) String bookingId,
                                                                   @RequestParam(required = false) List<String> bookingIds,
                                                                   @RequestParam(required = false) BigDecimal amountMin,
                                                                   @RequestParam(required = false) BigDecimal amountMax,
                                                                   @RequestParam(required = false) String reference,
                                                                   @RequestParam(required = false) TransactionFilter.Sort sort,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        OpsActor.of(principal);
        TransactionFilter filter = filter(type, status, from, to, riderId, driverId, bookingId, bookingIds, amountMin, amountMax, reference, sort);
        return ApiResponse.ok(transactionQueryService.list(filter, Math.max(page, 0), clamp(size)));
    }

    @GetMapping("/transactions/counts")
    public ApiResponse<Map<String, Long>> transactionCounts(@AuthenticationPrincipal CoRydUserDetails principal,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                            @RequestParam(required = false) String riderId,
                                                            @RequestParam(required = false) String driverId,
                                                            @RequestParam(required = false) String bookingId,
                                                            @RequestParam(required = false) List<String> bookingIds,
                                                            @RequestParam(required = false) BigDecimal amountMin,
                                                            @RequestParam(required = false) BigDecimal amountMax,
                                                            @RequestParam(required = false) String reference) {
        OpsActor.of(principal);
        return ApiResponse.ok(transactionQueryService.counts(
                filter(null, status, from, to, riderId, driverId, bookingId, bookingIds, amountMin, amountMax, reference, null)));
    }

    @GetMapping("/transactions/{type}/{id}")
    public ApiResponse<TransactionDetail> transaction(@AuthenticationPrincipal CoRydUserDetails principal,
                                                      @PathVariable TransactionType type, @PathVariable String id) {
        return ApiResponse.ok(transactionQueryService.detail(type, id, OpsActor.of(principal)));
    }

    // ---- manual refunds and approvals ---------------------------------------------------------------------------

    @GetMapping("/bookings/{bookingId}/refundable")
    public ApiResponse<RefundableView> refundable(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        OpsActor.of(principal);
        return ApiResponse.ok(refundService.refundable(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/refunds")
    public ApiResponse<OpsMoneyRequestView> refund(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId,
                                                   @Valid @RequestBody ManualRefundRequest body) {
        OpsMoneyRequestView view = refundService.submit(bookingId, body, OpsActor.of(principal));
        return ApiResponse.ok(message(view), view);
    }

    @GetMapping("/requests")
    public ApiResponse<PagedResponse<OpsMoneyRequestView>> requests(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                     @RequestParam(required = false) List<OpsRequestStatus> status,
                                                                     @RequestParam(required = false) List<OpsRequestType> type,
                                                                     @RequestParam(required = false) String bookingId,
                                                                     @RequestParam(required = false) String driverId,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(approvalService.list(status, type, bookingId, driverId, PageRequest.of(Math.max(page, 0), clamp(size)),
                OpsActor.of(principal)));
    }

    @GetMapping("/requests/{id}")
    public ApiResponse<OpsMoneyRequestView> request(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id) {
        return ApiResponse.ok(approvalService.get(id, OpsActor.of(principal)));
    }

    @PostMapping("/requests/{id}/approve")
    public ApiResponse<OpsMoneyRequestView> approve(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id,
                                                    @Valid @RequestBody(required = false) DecisionRequest body) {
        OpsMoneyRequestView view = approvalService.approve(id, body, OpsActor.of(principal));
        return ApiResponse.ok(message(view), view);
    }

    @PostMapping("/requests/{id}/reject")
    public ApiResponse<OpsMoneyRequestView> reject(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id,
                                                   @Valid @RequestBody(required = false) DecisionRequest body) {
        return ApiResponse.ok("Rejected - nothing was sent", approvalService.reject(id, body, OpsActor.of(principal)));
    }

    @PostMapping("/requests/{id}/cancel")
    public ApiResponse<OpsMoneyRequestView> cancel(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id,
                                                   @Valid @RequestBody(required = false) DecisionRequest body) {
        return ApiResponse.ok("Cancelled - nothing was sent", approvalService.cancel(id, body, OpsActor.of(principal)));
    }

    @PostMapping("/requests/{id}/retry")
    public ApiResponse<OpsMoneyRequestView> retryRequest(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id,
                                                         @Valid @RequestBody RetryRequest body) {
        OpsMoneyRequestView view = approvalService.retry(id, body, OpsActor.of(principal));
        return ApiResponse.ok(message(view), view);
    }

    // ---- refund failures -----------------------------------------------------------------------------------------

    @GetMapping("/refund-failures")
    public ApiResponse<PagedResponse<RefundFailureItem>> refundFailures(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                         @RequestParam(required = false) RefundFailureItem.Kind kind,
                                                                         @RequestParam(defaultValue = "0") int page,
                                                                         @RequestParam(defaultValue = "20") int size) {
        OpsActor.of(principal);
        return ApiResponse.ok(refundFailureService.list(kind, Math.max(page, 0), clamp(size)));
    }

    @PostMapping("/refund-failures/{holdId}/retry")
    public ApiResponse<OpsMoneyRequestView> retryRefund(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String holdId,
                                                        @Valid @RequestBody RetryRequest body) {
        OpsMoneyRequestView view = refundFailureService.retryCancellation(holdId, body, OpsActor.of(principal));
        return ApiResponse.ok(message(view), view);
    }

    // ---- payouts -------------------------------------------------------------------------------------------------

    @GetMapping("/payouts")
    public ApiResponse<PagedResponse<PayoutItem>> payouts(@AuthenticationPrincipal CoRydUserDetails principal,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String driverId,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                          @RequestParam(required = false) BigDecimal amountMin,
                                                          @RequestParam(required = false) BigDecimal amountMax,
                                                          @RequestParam(required = false) String reference,
                                                          @RequestParam(required = false) TransactionFilter.Sort sort,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        OpsActor.of(principal);
        TransactionFilter filter = filter(TransactionType.PAYOUT, payoutStatus(status), from, to, null, driverId, null, null, amountMin,
                amountMax, reference, sort);
        return ApiResponse.ok(payoutService.list(filter, Math.max(page, 0), clamp(size)));
    }

    @GetMapping("/payouts/counts")
    public ApiResponse<Map<String, Long>> payoutCounts(@AuthenticationPrincipal CoRydUserDetails principal,
                                                       @RequestParam(required = false) String driverId,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OpsActor.of(principal);
        return ApiResponse.ok(payoutService.counts(filter(TransactionType.PAYOUT, null, from, to, null, driverId, null, null, null, null, null, null)));
    }

    @GetMapping("/payouts/{id}")
    public ApiResponse<PayoutDetail> payout(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id) {
        return ApiResponse.ok(payoutService.detail(id, OpsActor.of(principal)));
    }

    @PostMapping("/payouts/{id}/retry")
    public ApiResponse<OpsMoneyRequestView> retryPayout(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String id,
                                                        @Valid @RequestBody RetryRequest body) {
        OpsMoneyRequestView view = payoutService.retry(id, body, OpsActor.of(principal));
        return ApiResponse.ok(message(view), view);
    }

    @GetMapping("/drivers/{driverId}/payout-account")
    public ApiResponse<PayoutAccountView> payoutAccount(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId) {
        OpsActor.of(principal);
        return ApiResponse.ok(payoutService.payoutAccount(driverId));
    }

    @PostMapping("/drivers/{driverId}/payout-account/reveal")
    public ApiResponse<PayoutAccountView.Account> reveal(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId,
                                                         @Valid @RequestBody RevealAccountRequest body) {
        return ApiResponse.ok("Revealed - this view is recorded", payoutService.reveal(driverId, body.recipientId(), body.reason(),
                OpsActor.of(principal)));
    }

    // ---- earnings and settlement debt ----------------------------------------------------------------------------

    @GetMapping("/drivers/{driverId}/earnings")
    public ApiResponse<DriverStatementView> earnings(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OpsActor actor = OpsActor.of(principal);
        LocalDate end = to == null ? LocalDate.now(OpsFinanceService.LAGOS) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be on or before to");
        }
        return ApiResponse.ok(earningsService.statement(driverId, start, end, OpsFinanceService.startOf(start),
                OpsFinanceService.startOf(end.plusDays(1)), actor));
    }

    @GetMapping("/drivers/{driverId}/debt-entries")
    public ApiResponse<PagedResponse<DebtEntryItem>> debtEntries(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                 @PathVariable String driverId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(earningsService.debtEntries(driverId, PageRequest.of(Math.max(page, 0), clamp(size)), OpsActor.of(principal)));
    }

    @PostMapping("/drivers/{driverId}/debt-adjustments")
    public ApiResponse<OpsMoneyRequestView> adjustDebt(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String driverId,
                                                       @Valid @RequestBody DebtAdjustmentRequest body) {
        OpsMoneyRequestView view = earningsService.adjust(driverId, body, OpsActor.of(principal));
        return ApiResponse.ok(message(view), view);
    }

    // ---- finance dashboard and audit ----------------------------------------------------------------------------

    @GetMapping("/finance/summary")
    public ApiResponse<FinanceSummary> finance(@AuthenticationPrincipal CoRydUserDetails principal,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        OpsActor.of(principal);
        return ApiResponse.ok(financeService.summary(from, to));
    }

    @GetMapping("/audit")
    public ApiResponse<PagedResponse<OpsMoneyAudit>> audit(@AuthenticationPrincipal CoRydUserDetails principal,
                                                          @RequestParam(required = false) String targetId,
                                                          @RequestParam(required = false) String actorId,
                                                          @RequestParam(required = false) OpsMoneyAudit.Action action,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        OpsActor.of(principal);
        return ApiResponse.ok(PagedResponse.from(auditRepository.search(blank(targetId), blank(actorId), action,
                PageRequest.of(Math.max(page, 0), clamp(size)))));
    }

    /** Two identical submits racing past the idempotency check - the second is refused, the first stands. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> duplicate(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("This was already submitted - refresh to see it"));
    }

    private static TransactionFilter filter(TransactionType type, String status, LocalDate from, LocalDate to, String riderId, String driverId,
                                            String bookingId, List<String> bookingIds, BigDecimal amountMin, BigDecimal amountMax,
                                            String reference, TransactionFilter.Sort sort) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (amountMin != null && amountMax != null && amountMin.compareTo(amountMax) > 0) {
            throw new BadRequestException("amountMin must not be more than amountMax");
        }
        return new TransactionFilter(type, status, from == null ? null : OpsFinanceService.startOf(from),
                to == null ? null : OpsFinanceService.startOf(to.plusDays(1)), riderId, driverId, bookingId,
                bookingIds == null ? null : bookingIds.stream().filter(id -> id != null && !id.isBlank()).toList(),
                amountMin, amountMax, reference, sort);
    }

    private static String payoutStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String s = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PENDING", "PROCESSING", "PAID", "FAILED").contains(s)) {
            throw new BadRequestException("Invalid status '" + status + "' - must be one of [PENDING, PROCESSING, PAID, FAILED]");
        }
        return s;
    }

    private static String message(OpsMoneyRequestView view) {
        return switch (view.status()) {
            case PENDING_APPROVAL -> "Above " + "the approval threshold - waiting for a second admin";
            case COMPLETED -> "Done";
            case FAILED -> "Failed - " + view.failureReason();
            default -> view.status().name();
        };
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(size, 100));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
