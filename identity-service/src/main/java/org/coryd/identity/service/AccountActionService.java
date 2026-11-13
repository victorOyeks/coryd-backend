package org.coryd.identity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.identity.dto.AccountActionView;
import org.coryd.identity.dto.AccountOverview;
import org.coryd.identity.dto.OpsActor;
import org.coryd.identity.dto.RecordAccountActionRequest;
import org.coryd.identity.entity.AccountAction;
import org.coryd.identity.repository.AccountActionRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The account history: every ops action on an account, who did it, why, and what changed. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountActionService {

    /** What ops-service may record for work it did in another service. The rest are recorded by identity-service's own calls. */
    static final Set<AccountActionType> RECORDABLE = EnumSet.of(AccountActionType.DETAILS_EDITED, AccountActionType.VEHICLE_EDITED,
            AccountActionType.LICENCE_ATTEMPTS_RESET, AccountActionType.IDENTITY_ATTEMPTS_RESET);

    private static final TypeReference<List<RecordAccountActionRequest.Change>> CHANGES = new TypeReference<>() {
    };

    private final AccountActionRepository accountActionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AccountAction record(String userId, AccountActionType action, String actorId, String actorName, String reason,
                                List<RecordAccountActionRequest.Change> changes) {
        return accountActionRepository.save(AccountAction.builder()
                .userId(userId)
                .action(action)
                .actorId(blankToNull(actorId))
                .actorName(blankToNull(actorName))
                .reason(blankToNull(reason))
                .changes(changes == null || changes.isEmpty() ? null : write(changes))
                .build());
    }

    /** POST /internal/users/{id}/account-actions - an edit or reset ops-service made in another service. */
    @Transactional
    public AccountActionView recordFromOps(String userId, RecordAccountActionRequest request) {
        if (!RECORDABLE.contains(request.action())) {
            throw new BadRequestException(request.action() + " is recorded by its own call, not here");
        }
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found");
        }
        AccountAction saved = record(userId, request.action(), request.opsUserId(), request.opsUserName(), request.reason(),
                request.changes());
        log.info("{} on {} by {} recorded", request.action(), userId, request.opsUserId());
        return view(saved);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AccountActionView> history(String userId, int page, int size) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User not found");
        }
        return PagedResponse.from(accountActionRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 100)))
                .map(this::view));
    }

    /** Each suspension and the reinstate that ended it, oldest first. */
    @Transactional(readOnly = true)
    public List<AccountOverview.SuspensionPeriod> suspensionHistory(String userId) {
        List<AccountOverview.SuspensionPeriod> periods = new ArrayList<>();
        AccountAction open = null;
        for (AccountAction a : accountActionRepository.findByUserIdAndActionInOrderByCreatedAtAsc(userId,
                List.of(AccountActionType.SUSPENDED, AccountActionType.REINSTATED))) {
            if (a.getAction() == AccountActionType.SUSPENDED) {
                if (open != null) {
                    periods.add(period(open, null));
                }
                open = a;
            } else if (open != null) {
                periods.add(period(open, a));
                open = null;
            }
        }
        if (open != null) {
            periods.add(period(open, null));
        }
        return periods;
    }

    /** Who suspended the account most recently, if recorded. */
    @Transactional(readOnly = true)
    public OpsActor lastSuspendedBy(String userId) {
        List<AccountAction> suspensions = accountActionRepository.findByUserIdAndActionInOrderByCreatedAtAsc(userId,
                List.of(AccountActionType.SUSPENDED));
        return suspensions.isEmpty() ? null : actor(suspensions.get(suspensions.size() - 1));
    }

    private static AccountOverview.SuspensionPeriod period(AccountAction suspended, AccountAction reinstated) {
        return new AccountOverview.SuspensionPeriod(suspended.getCreatedAt(), actor(suspended), suspended.getReason(),
                reinstated == null ? null : reinstated.getCreatedAt(), reinstated == null ? null : actor(reinstated),
                reinstated == null ? null : reinstated.getReason());
    }

    private AccountActionView view(AccountAction a) {
        return new AccountActionView(a.getId(), a.getAction(), actor(a), a.getReason(), read(a.getChanges()), a.getCreatedAt());
    }

    private static OpsActor actor(AccountAction a) {
        return a.getActorId() == null && a.getActorName() == null ? null : new OpsActor(a.getActorId(), a.getActorName());
    }

    private String write(List<RecordAccountActionRequest.Change> changes) {
        try {
            return objectMapper.writeValueAsString(changes);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't record the changes", e);
        }
    }

    private List<RecordAccountActionRequest.Change> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, CHANGES);
        } catch (Exception e) {
            log.warn("Unreadable account action changes: {}", e.getMessage());
            return List.of();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
