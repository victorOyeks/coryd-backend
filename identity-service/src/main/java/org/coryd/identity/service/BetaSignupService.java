package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.util.PhoneNumbers;
import org.coryd.identity.dto.BetaSignupRequest;
import org.coryd.identity.entity.BetaSignup;
import org.coryd.identity.repository.BetaSignupRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** The beta waitlist: the public form, the ops list, and noting when someone on it becomes a user. */
@Service
@RequiredArgsConstructor
public class BetaSignupService {

    /** Nigerian mobile numbers, local form: 070x, 080x, 081x, 090x, 091x. */
    private static final Pattern NG_MOBILE = Pattern.compile("^0[789][01]\\d{8}$");

    private final BetaSignupRepository betaSignupRepository;

    /** Same answer whether new or already on the list, so the form can't be used to find out who signed up. */
    @Transactional
    public void join(BetaSignupRequest request) {
        String number = PhoneNumbers.normalize(request.whatsappNumber());
        if (number == null || !NG_MOBILE.matcher(number).matches()) {
            throw new BadRequestException("Enter a valid Nigerian WhatsApp number, e.g. 0803 123 4567");
        }
        BetaSignup signup = betaSignupRepository.findByWhatsappNumber(number)
                .orElseGet(() -> BetaSignup.builder().whatsappNumber(number).build());
        signup.setName(request.name().trim());
        signup.setEmail(request.email().trim().toLowerCase(Locale.ROOT));
        signup.setWillMostly(request.willMostly());
        signup.setUsualRoute(request.usualRoute().trim());
        signup.setDevice(request.device());
        betaSignupRepository.save(signup);
    }

    @Transactional(readOnly = true)
    public PagedResponse<BetaSignup> list(BetaSignup.Status status, BetaSignup.Intent willMostly, BetaSignup.Device device,
                                          String search, Pageable pageable) {
        String term = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        return PagedResponse.from(betaSignupRepository.search(status, willMostly, device, term, pageable));
    }

    @Transactional(readOnly = true)
    public BetaSignupRepository.Summary summary() {
        return betaSignupRepository.summary();
    }

    /** Ops sent them the invite (WhatsApp, TestFlight / Play link) - recorded so nobody is invited twice. */
    @Transactional
    public BetaSignup markInvited(String id, String opsUserId) {
        BetaSignup signup = betaSignupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beta signup not found"));
        if (signup.getStatus() != BetaSignup.Status.INVITED) {
            signup.setStatus(BetaSignup.Status.INVITED);
            signup.setInvitedAt(Instant.now());
            signup.setInvitedBy(opsUserId);
        }
        return betaSignupRepository.save(signup);
    }

    /** Called when an account is created - a waitlist entry with the same number has now joined. */
    @Transactional
    public void markJoined(String normalisedPhone) {
        betaSignupRepository.findByWhatsappNumber(normalisedPhone)
                .filter(s -> s.getJoinedAt() == null)
                .ifPresent(s -> {
                    s.setJoinedAt(Instant.now());
                    betaSignupRepository.save(s);
                });
    }
}
