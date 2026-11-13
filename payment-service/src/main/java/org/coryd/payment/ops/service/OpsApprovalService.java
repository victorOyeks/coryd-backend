package org.coryd.payment.ops.service;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.payment.ops.OpsMoneyProperties;
import org.coryd.payment.ops.dto.DecisionRequest;
import org.coryd.payment.ops.dto.OpsMoneyRequestView;
import org.coryd.payment.ops.dto.RetryRequest;
import org.coryd.payment.ops.entity.OpsMoneyAudit;
import org.coryd.payment.ops.entity.OpsMoneyRequest;
import org.coryd.payment.ops.entity.OpsRequestStatus;
import org.coryd.payment.ops.entity.OpsRequestType;
import org.coryd.payment.ops.repository.OpsMoneyRequestRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The two-person rule (plan decision 4). A request above the threshold waits in PENDING_APPROVAL; a DIFFERENT admin approves
 * it - and it runs then, under the same checks as if it had run straight away - or rejects it with a reason. Only the admin
 * who asked can cancel it. Each decision locks the request row, so a second approve, or an approve racing a cancel, finds
 * it already decided.
 */
@Slf4j
@Service
public class OpsApprovalService {

    private final OpsMoneyRequestRepository requestRepository;
    private final OpsRequestSupport support;
    private final OpsMoneyAuditService audit;
    private final OpsMoneyProperties properties;
    private final Map<OpsRequestType, OpsMoneyExecutor> executors;

    public OpsApprovalService(OpsMoneyRequestRepository requestRepository, OpsRequestSupport support, OpsMoneyAuditService audit,
                              OpsMoneyProperties properties, List<OpsMoneyExecutor> executors) {
        this.requestRepository = requestRepository;
        this.support = support;
        this.audit = audit;
        this.properties = properties;
        this.executors = new EnumMap<>(executors.stream().collect(Collectors.toMap(OpsMoneyExecutor::type, Function.identity())));
    }

    @Transactional(readOnly = true)
    public PagedResponse<OpsMoneyRequestView> list(List<OpsRequestStatus> statuses, List<OpsRequestType> types, String bookingId,
                                                   String driverId, Pageable pageable, OpsActor viewer) {
        List<OpsRequestStatus> s = statuses == null || statuses.isEmpty() ? Arrays.asList(OpsRequestStatus.values()) : statuses;
        List<OpsRequestType> t = types == null || types.isEmpty() ? Arrays.asList(OpsRequestType.values()) : types;
        return PagedResponse.from(requestRepository.search(s, t, blank(bookingId), blank(driverId), pageable)
                .map(r -> support.view(r, viewer)));
    }

    @Transactional(readOnly = true)
    public OpsMoneyRequestView get(String id, OpsActor viewer) {
        return support.view(requestRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("No such request")), viewer);
    }

    @Transactional
    public OpsMoneyRequestView approve(String id, DecisionRequest body, OpsActor approver) {
        OpsMoneyRequest request = pending(id);
        if (request.getRequestedBy().equals(approver.id())) {
            throw new BadRequestException("A different admin must approve this - you asked for it");
        }
        String note = body == null || body.reason() == null || body.reason().isBlank() ? null : body.reason().trim();
        decide(request, approver, note, OpsRequestStatus.EXECUTING);
        audit.record(approver, OpsMoneyAudit.Action.REQUEST_APPROVED, OpsMoneyAudit.TargetType.REQUEST, request.getId(), request.getId(),
                request.getAmount(), Map.of("status", OpsRequestStatus.PENDING_APPROVAL), Map.of("status", OpsRequestStatus.EXECUTING),
                note);
        OpsMoneyRequest done = executor(request).execute(request, approver);
        return support.view(done, approver);
    }

    @Transactional
    public OpsMoneyRequestView reject(String id, DecisionRequest body, OpsActor admin) {
        String reason = OpsRequestSupport.requireReason(body == null ? null : body.reason());
        OpsMoneyRequest request = pending(id);
        if (request.getRequestedBy().equals(admin.id())) {
            throw new BadRequestException("You asked for this - cancel it instead of rejecting it");
        }
        decide(request, admin, reason, OpsRequestStatus.REJECTED);
        OpsMoneyRequest saved = requestRepository.save(request);
        audit.record(admin, OpsMoneyAudit.Action.REQUEST_REJECTED, OpsMoneyAudit.TargetType.REQUEST, saved.getId(), saved.getId(),
                saved.getAmount(), Map.of("status", OpsRequestStatus.PENDING_APPROVAL), Map.of("status", saved.getStatus()), reason);
        return support.view(saved, admin);
    }

    @Transactional
    public OpsMoneyRequestView cancel(String id, DecisionRequest body, OpsActor admin) {
        OpsMoneyRequest request = pending(id);
        if (!request.getRequestedBy().equals(admin.id())) {
            throw new BadRequestException("Only the admin who asked for this can cancel it - reject it instead");
        }
        String note = body == null || body.reason() == null || body.reason().isBlank() ? null : body.reason().trim();
        decide(request, admin, note, OpsRequestStatus.CANCELLED);
        OpsMoneyRequest saved = requestRepository.save(request);
        audit.record(admin, OpsMoneyAudit.Action.REQUEST_CANCELLED, OpsMoneyAudit.TargetType.REQUEST, saved.getId(), saved.getId(),
                saved.getAmount(), Map.of("status", OpsRequestStatus.PENDING_APPROVAL), Map.of("status", saved.getStatus()), note);
        return support.view(saved, admin);
    }

    /**
     * A FAILED manual refund or payout retry, tried again. Above the threshold it goes back to PENDING_APPROVAL with the
     * admin retrying as the one asking - so a different admin has to approve the new attempt too.
     */
    @Transactional
    public OpsMoneyRequestView retry(String id, RetryRequest body, OpsActor admin) {
        String reason = OpsRequestSupport.requireReason(body == null ? null : body.reason());
        OpsMoneyRequest request = requestRepository.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("No such request"));
        if (request.getStatus() != OpsRequestStatus.FAILED) {
            throw new BadRequestException("Only a failed request can be retried - this one is " + request.getStatus());
        }
        if (request.getType() == OpsRequestType.DEBT_ADJUSTMENT) {
            throw new BadRequestException("A debt adjustment can't be retried - record a new one");
        }
        Map<String, Object> before = Map.of("status", request.getStatus(), "failureReason", String.valueOf(request.getFailureReason()));
        request.setFailureReason(null);
        request.setDecidedBy(null);
        request.setDecidedByName(null);
        request.setDecidedAt(null);
        request.setDecisionReason(null);
        if (properties.needsApproval(request.getAmount())) {
            request.setNeedsApproval(true);
            request.setStatus(OpsRequestStatus.PENDING_APPROVAL);
            request.setRequestedBy(admin.id());
            request.setRequestedByName(admin.name());
            OpsMoneyRequest saved = requestRepository.save(request);
            audit.record(admin, OpsMoneyAudit.Action.REQUEST_RESUBMITTED, OpsMoneyAudit.TargetType.REQUEST, saved.getId(), saved.getId(),
                    saved.getAmount(), before, Map.of("status", saved.getStatus()), reason);
            return support.view(saved, admin);
        }
        request.setStatus(OpsRequestStatus.EXECUTING);
        audit.record(admin, OpsMoneyAudit.Action.REQUEST_RESUBMITTED, OpsMoneyAudit.TargetType.REQUEST, request.getId(), request.getId(),
                request.getAmount(), before, Map.of("status", OpsRequestStatus.EXECUTING), reason);
        return support.view(executor(request).execute(request, admin), admin);
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return requestRepository.countByStatus(OpsRequestStatus.PENDING_APPROVAL);
    }

    private OpsMoneyRequest pending(String id) {
        OpsMoneyRequest request = requestRepository.findForUpdate(id).orElseThrow(() -> new ResourceNotFoundException("No such request"));
        if (request.getStatus() != OpsRequestStatus.PENDING_APPROVAL) {
            throw new BadRequestException("This request isn't waiting for approval - it is " + request.getStatus());
        }
        return request;
    }

    private static void decide(OpsMoneyRequest request, OpsActor admin, String reason, OpsRequestStatus status) {
        request.setDecidedBy(admin.id());
        request.setDecidedByName(admin.name());
        request.setDecidedAt(Instant.now());
        request.setDecisionReason(reason);
        request.setStatus(status);
    }

    private OpsMoneyExecutor executor(OpsMoneyRequest request) {
        OpsMoneyExecutor executor = executors.get(request.getType());
        if (executor == null) {
            throw new IllegalStateException("No executor for " + request.getType());
        }
        return executor;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
