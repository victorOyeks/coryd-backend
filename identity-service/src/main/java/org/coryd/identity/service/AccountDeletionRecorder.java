package org.coryd.identity.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.AccountDeletionStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.identity.dto.AccountDeletionView;
import org.coryd.identity.entity.AccountDeletionRequest;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.AccountDeletionRequestRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The record of account deletions ops can read: a refused try is kept (in its own transaction - the deletion itself rolls
 * back) as a PENDING request with the reasons; a deletion that goes through completes it. One pending request per account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionRecorder {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final AccountDeletionRequestRepository repository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refused(User user, String requestedBy, List<String> blockers) {
        Instant now = Instant.now();
        AccountDeletionRequest request = repository.findFirstByUserIdAndStatus(user.getId(), AccountDeletionStatus.PENDING)
                .map(r -> {
                    r.setAttempts(r.getAttempts() + 1);
                    return r;
                })
                .orElseGet(() -> snapshot(user, requestedBy, now));
        request.setBlockers(write(blockers));
        request.setLastAttemptAt(now);
        repository.save(request);
    }

    /** Part of the deletion's own transaction - recorded only if the account really goes. */
    @Transactional
    public void completed(User user, String deletedBy) {
        Instant now = Instant.now();
        AccountDeletionRequest request = repository.findFirstByUserIdAndStatus(user.getId(), AccountDeletionStatus.PENDING)
                .map(r -> {
                    r.setAttempts(r.getAttempts() + 1);
                    return r;
                })
                .orElseGet(() -> snapshot(user, deletedBy, now));
        request.setStatus(AccountDeletionStatus.COMPLETED);
        request.setBlockers(null);
        request.setLastAttemptAt(now);
        request.setCompletedAt(now);
        request.setCompletedBy(deletedBy);
        repository.save(request);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AccountDeletionView> list(AccountDeletionStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 50), Sort.by(Sort.Direction.DESC, "lastAttemptAt"));
        var result = status == null ? repository.findAll(pageable) : repository.findByStatus(status, pageable);
        return PagedResponse.from(result.map(this::view));
    }

    @Transactional(readOnly = true)
    public AccountDeletionView get(String id) {
        return repository.findById(id).map(this::view)
                .orElseThrow(() -> new ResourceNotFoundException("Account deletion request not found"));
    }

    public List<String> blockers(AccountDeletionRequest request) {
        if (request.getBlockers() == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(request.getBlockers(), STRINGS);
        } catch (Exception e) {
            return List.of(request.getBlockers());
        }
    }

    private AccountDeletionView view(AccountDeletionRequest r) {
        User user = r.getStatus() == AccountDeletionStatus.PENDING ? userRepository.findById(r.getUserId()).orElse(null) : null;
        return new AccountDeletionView(r.getId(), r.getUserId(), r.getStatus(),
                r.getRoles() == null || r.getRoles().isBlank() ? List.of() : Arrays.asList(r.getRoles().split(",")),
                r.getPhoneLast4(),
                user == null ? null : user.getFirstName(), user == null ? null : user.getLastName(), user == null ? null : user.getPhone(),
                r.getJoinedAt(), r.getRequestedBy(), r.getCreatedAt(), r.getAttempts(), r.getLastAttemptAt(), blockers(r),
                r.getCompletedAt(), r.getCompletedBy());
    }

    private static AccountDeletionRequest snapshot(User user, String requestedBy, Instant now) {
        String phone = user.getPhone();
        return AccountDeletionRequest.builder()
                .userId(user.getId())
                .status(AccountDeletionStatus.PENDING)
                .requestedBy(requestedBy)
                .roles(user.getRoles() == null ? null : user.getRoles().stream().map(Enum::name).sorted().collect(Collectors.joining(",")))
                .phoneLast4(phone == null || phone.length() < 4 ? phone : phone.substring(phone.length() - 4))
                .joinedAt(user.getCreatedAt())
                .lastAttemptAt(now)
                .build();
    }

    private String write(List<String> blockers) {
        try {
            return objectMapper.writeValueAsString(blockers);
        } catch (Exception e) {
            return String.join("; ", blockers);
        }
    }
}
