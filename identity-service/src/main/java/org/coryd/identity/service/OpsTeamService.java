package org.coryd.identity.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.AccountActionType;
import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.OpsAdminInvitedEvent;
import org.coryd.common.util.Names;
import org.coryd.identity.dto.AuthResponse;
import org.coryd.identity.dto.RecordAccountActionRequest.Change;
import org.coryd.identity.dto.ops.AcceptOpsInviteRequest;
import org.coryd.identity.dto.ops.ChangeOpsRoleRequest;
import org.coryd.identity.dto.ops.InviteOpsAdminRequest;
import org.coryd.identity.dto.ops.OpsAdminStatus;
import org.coryd.identity.dto.ops.OpsAdminView;
import org.coryd.identity.dto.ops.OpsInvitePreview;
import org.coryd.identity.dto.ops.OpsTeamActionRequest;
import org.coryd.identity.entity.OpsInvite;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.OpsInviteRepository;
import org.coryd.identity.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ops console's admin team: invite by email (a one-time set-password link, 72h), resend or revoke the invite, change
 * an admin's ops role, deactivate and reactivate. Only an active SUPER_ADMIN may act (checked here as well as in
 * ops-service). Nobody may deactivate themselves or change their own role, and the last active SUPER_ADMIN can't be
 * demoted or deactivated. Every change is recorded in the admin's account history (account_actions, OPS_* actions),
 * which the unified audit log reads as category TEAM.
 *
 * <p>Deactivation removes console access only: the OPS_ADMIN sign-in is refused and every session ends at once through
 * tokens_valid_after (SessionRevocationService) - the account itself isn't suspended. A role change cuts off the access
 * tokens issued so far (they carry the old role) but keeps refresh tokens, so the console refreshes into the new role.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsTeamService {

    static final String ROLE_CHANGED = "OPS_ROLE_CHANGED";
    static final String DEACTIVATED = "OPS_ADMIN_DEACTIVATED";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LAST_SEEN_THROTTLE = Duration.ofMinutes(1);

    private final UserRepository userRepository;
    private final OpsInviteRepository inviteRepository;
    private final SessionRevocationService sessionRevocationService;
    private final AccountActionService accountActionService;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${coryd.ops.console-url:https://admin.coryd.ng}")
    private String consoleUrl;

    @Value("${coryd.ops.invite-ttl-hours:72}")
    private long inviteTtlHours;

    // ---- read ---------------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<OpsAdminView> list() {
        List<User> admins = userRepository.findOpsAdmins();
        Map<String, OpsInvite> latest = latestInvites(admins.stream().map(User::getId).toList());
        Instant now = Instant.now();
        return admins.stream()
                .map(u -> view(u, latest.get(u.getId()), now))
                .sorted(Comparator.comparing((OpsAdminView v) -> v.status().ordinal())
                        .thenComparing(v -> v.fullName() == null ? "" : v.fullName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional(readOnly = true)
    public OpsAdminView get(String userId) {
        User user = admin(userId);
        return view(user, inviteRepository.findFirstByUserIdOrderByCreatedAtDesc(userId).orElse(null), Instant.now());
    }

    /** The console loaded - "last seen" moves on, at most once a minute. */
    @Transactional
    public void touch(String userId) {
        Instant now = Instant.now();
        userRepository.touchLastSeen(userId, now, now.minus(LAST_SEEN_THROTTLE));
    }

    // ---- invites ------------------------------------------------------------------------------------------------------

    @Transactional
    public OpsAdminView invite(InviteOpsAdminRequest request) {
        User actor = requireSuperAdmin(request.opsUserId());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        userRepository.findByEmail(email).ifPresent(existing -> {
            throw new BadRequestException(existing.getRoles().contains(UserRole.OPS_ADMIN)
                    ? email + " is already on the admin team"
                    : "That email address is already in use");
        });
        Instant now = Instant.now();
        User user = userRepository.save(User.builder()
                .email(email)
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .roles(new java.util.HashSet<>(List.of(UserRole.OPS_ADMIN)))
                .opsRole(request.opsRole())
                .invitedBy(actor.getId())
                .invitedByName(name(actor, request.opsUserName()))
                .invitedAt(now)
                .build());
        OpsInvite invite = issueInvite(user, actor, request.opsUserName(), false);
        accountActionService.record(user.getId(), AccountActionType.OPS_ADMIN_INVITED, actor.getId(), name(actor, request.opsUserName()),
                null, List.of(new Change("email", null, email), new Change("opsRole", null, request.opsRole().name())));
        log.info("Ops admin {} invited as {} by {}", user.getId(), request.opsRole(), actor.getId());
        return view(user, invite, now);
    }

    @Transactional
    public OpsAdminView resendInvite(String userId, OpsTeamActionRequest request) {
        User actor = requireSuperAdmin(request.opsUserId());
        User user = admin(userId);
        if (user.getPasswordHash() != null) {
            throw new BadRequestException(display(user) + " has already set up their account - there's no invite to resend");
        }
        if (user.getOpsDeactivatedAt() != null) {
            throw new BadRequestException("Reactivate " + display(user) + " before sending them a new invite");
        }
        revokePending(userId, Instant.now());
        OpsInvite invite = issueInvite(user, actor, request.opsUserName(), true);
        accountActionService.record(userId, AccountActionType.OPS_INVITE_RESENT, actor.getId(), name(actor, request.opsUserName()),
                blankToNull(request.reason()), null);
        return view(user, invite, Instant.now());
    }

    @Transactional
    public OpsAdminView revokeInvite(String userId, OpsTeamActionRequest request) {
        User actor = requireSuperAdmin(request.opsUserId());
        User user = admin(userId);
        Instant now = Instant.now();
        boolean pending = inviteRepository.findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(userId).stream().anyMatch(i -> i.isPending(now));
        if (!pending || user.getPasswordHash() != null) {
            throw new BadRequestException("There's no pending invite to revoke for " + display(user));
        }
        revokePending(userId, now);
        accountActionService.record(userId, AccountActionType.OPS_INVITE_REVOKED, actor.getId(), name(actor, request.opsUserName()),
                blankToNull(request.reason()), null);
        return view(user, inviteRepository.findFirstByUserIdOrderByCreatedAtDesc(userId).orElse(null), now);
    }

    /** The set-password page, before a password is chosen: who the invite is for. 400 when the link can't be used. */
    @Transactional(readOnly = true)
    public OpsInvitePreview preview(String token) {
        OpsInvite invite = inviteRepository.findByTokenHash(hash(token))
                .orElseThrow(() -> new BadRequestException("This invite link isn't valid - ask a super admin to send a new one"));
        User user = usable(invite, Instant.now());
        OpsRole role = OpsRole.effective(user.getOpsRole());
        return new OpsInvitePreview(user.getEmail(), user.getFirstName(), user.getLastName(), role, role.label(),
                invite.getCreatedByName(), invite.getExpiresAt());
    }

    /** Sets the password from the invite (once) and signs the admin in to the console. */
    @Transactional
    public AuthResponse accept(AcceptOpsInviteRequest request) {
        if (!request.password().equals(request.confirmPassword())) {
            throw new BadRequestException("Password and confirmation do not match");
        }
        Instant now = Instant.now();
        OpsInvite invite = inviteRepository.lockByTokenHash(hash(request.token()))
                .orElseThrow(() -> new BadRequestException("This invite link isn't valid - ask a super admin to send a new one"));
        User user = usable(invite, now);
        invite.setUsedAt(now);
        inviteRepository.save(invite);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        accountActionService.record(user.getId(), AccountActionType.OPS_INVITE_ACCEPTED, user.getId(),
                Names.join(user.getFirstName(), user.getLastName()), null, null);
        log.info("Ops admin {} accepted their invite", user.getId());
        return authService.opsSession(user);
    }

    // ---- role, deactivate, reactivate --------------------------------------------------------------------------------

    @Transactional
    public OpsAdminView changeRole(String userId, ChangeOpsRoleRequest request) {
        userRepository.lockOpsAdmins();
        User actor = requireSuperAdmin(request.opsUserId());
        User user = admin(userId);
        if (actor.getId().equals(user.getId())) {
            throw new BadRequestException("You can't change your own ops role - ask another super admin");
        }
        OpsRole before = OpsRole.effective(user.getOpsRole());
        if (before == request.opsRole()) {
            throw new BadRequestException(display(user) + " is already " + before.label());
        }
        if (before == OpsRole.SUPER_ADMIN && isActiveAdmin(user) && activeSuperAdmins() <= 1) {
            throw new BadRequestException(display(user) + " is the last super admin - make someone else a super admin first");
        }
        user.setOpsRole(request.opsRole());
        userRepository.save(user);
        if (user.getPasswordHash() != null) {
            // Tokens already out carry the old role; the console's next refresh gets the new one.
            sessionRevocationService.cutOffAccessTokens(user, ROLE_CHANGED);
        }
        accountActionService.record(userId, AccountActionType.OPS_ROLE_CHANGED, actor.getId(), name(actor, request.opsUserName()),
                blankToNull(request.reason()), List.of(new Change("opsRole", before.name(), request.opsRole().name())));
        log.info("Ops admin {} role {} -> {} by {}", userId, before, request.opsRole(), actor.getId());
        return get(userId);
    }

    @Transactional
    public OpsAdminView deactivate(String userId, OpsTeamActionRequest request) {
        userRepository.lockOpsAdmins();
        User actor = requireSuperAdmin(request.opsUserId());
        User user = admin(userId);
        if (actor.getId().equals(user.getId())) {
            throw new BadRequestException("You can't deactivate yourself - ask another super admin");
        }
        String why = requireReason(request.reason(), "Give a reason for deactivating this admin");
        if (user.getOpsDeactivatedAt() != null) {
            throw new BadRequestException(display(user) + " is already deactivated");
        }
        if (OpsRole.effective(user.getOpsRole()) == OpsRole.SUPER_ADMIN && isActiveAdmin(user) && activeSuperAdmins() <= 1) {
            throw new BadRequestException(display(user) + " is the last super admin - make someone else a super admin first");
        }
        Instant now = Instant.now();
        user.setOpsDeactivatedAt(now);
        user.setOpsDeactivatedBy(actor.getId());
        user.setOpsDeactivationReason(why);
        revokePending(userId, now);
        sessionRevocationService.endAllSessions(user, DEACTIVATED);
        accountActionService.record(userId, AccountActionType.OPS_ADMIN_DEACTIVATED, actor.getId(), name(actor, request.opsUserName()),
                why, List.of(new Change("consoleAccess", "ACTIVE", "DEACTIVATED")));
        log.info("Ops admin {} deactivated by {}: {}", userId, actor.getId(), why);
        return get(userId);
    }

    @Transactional
    public OpsAdminView reactivate(String userId, OpsTeamActionRequest request) {
        User actor = requireSuperAdmin(request.opsUserId());
        User user = admin(userId);
        String why = requireReason(request.reason(), "Give a reason for reactivating this admin");
        if (user.getOpsDeactivatedAt() == null) {
            throw new BadRequestException(display(user) + " isn't deactivated");
        }
        user.setOpsDeactivatedAt(null);
        user.setOpsDeactivatedBy(null);
        user.setOpsDeactivationReason(null);
        userRepository.save(user);
        accountActionService.record(userId, AccountActionType.OPS_ADMIN_REACTIVATED, actor.getId(), name(actor, request.opsUserName()),
                why, List.of(new Change("consoleAccess", "DEACTIVATED", "ACTIVE")));
        log.info("Ops admin {} reactivated by {}: {}", userId, actor.getId(), why);
        return get(userId);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private OpsInvite issueInvite(User user, User actor, String actorName, boolean resent) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        OpsInvite invite = inviteRepository.save(OpsInvite.builder()
                .userId(user.getId())
                .tokenHash(hash(token))
                .expiresAt(Instant.now().plus(Duration.ofHours(inviteTtlHours)))
                .createdBy(actor.getId())
                .createdByName(name(actor, actorName))
                .build());
        OpsAdminInvitedEvent event = OpsAdminInvitedEvent.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .opsRoleLabel(OpsRole.effective(user.getOpsRole()).label())
                .invitedByName(name(actor, actorName))
                .link(stripSlash(consoleUrl) + "/accept-invite?token=" + token)
                .expiresAt(invite.getExpiresAt())
                .resent(resent)
                .build();
        afterCommit(() -> kafkaTemplate.send(KafkaTopics.OPS_ADMIN_INVITED, user.getId(), event));
        return invite;
    }

    private void revokePending(String userId, Instant now) {
        for (OpsInvite open : inviteRepository.findByUserIdAndUsedAtIsNullAndRevokedAtIsNull(userId)) {
            open.setRevokedAt(now);
            inviteRepository.save(open);
        }
    }

    /** The invite's admin, when the link may still be used; otherwise a 400 saying why. */
    private User usable(OpsInvite invite, Instant now) {
        if (invite.getUsedAt() != null) {
            throw new BadRequestException("This invite link has already been used - sign in with your email and password");
        }
        if (invite.getRevokedAt() != null) {
            throw new BadRequestException("This invite link is no longer valid - ask a super admin to send a new one");
        }
        if (!invite.getExpiresAt().isAfter(now)) {
            throw new BadRequestException("This invite link has expired - ask a super admin to send a new one");
        }
        User user = userRepository.findById(invite.getUserId())
                .orElseThrow(() -> new BadRequestException("This invite link is no longer valid - ask a super admin to send a new one"));
        if (user.getOpsDeactivatedAt() != null || !user.getRoles().contains(UserRole.OPS_ADMIN)) {
            throw new BadRequestException("This invite link is no longer valid - ask a super admin to send a new one");
        }
        if (user.getPasswordHash() != null) {
            throw new BadRequestException("This invite link has already been used - sign in with your email and password");
        }
        return user;
    }

    /** The acting admin must be an active SUPER_ADMIN right now - not only on their (possibly older) token. */
    private User requireSuperAdmin(String actorId) {
        User actor = actorId == null ? null : userRepository.findById(actorId).orElse(null);
        if (actor == null || !actor.getRoles().contains(UserRole.OPS_ADMIN) || !isActiveAdmin(actor)
                || OpsRole.effective(actor.getOpsRole()) != OpsRole.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a super admin can manage the admin team");
        }
        return actor;
    }

    private User admin(String userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
        if (!user.getRoles().contains(UserRole.OPS_ADMIN)) {
            throw new ResourceNotFoundException("Admin not found");
        }
        return user;
    }

    /** Signed in at least once (or can), not deactivated, account not suspended. */
    private static boolean isActiveAdmin(User user) {
        return user.isActive() && user.getOpsDeactivatedAt() == null && user.getPasswordHash() != null;
    }

    private long activeSuperAdmins() {
        return userRepository.findOpsAdmins().stream()
                .filter(OpsTeamService::isActiveAdmin)
                .filter(u -> OpsRole.effective(u.getOpsRole()) == OpsRole.SUPER_ADMIN)
                .count();
    }

    private Map<String, OpsInvite> latestInvites(List<String> userIds) {
        Map<String, OpsInvite> latest = new LinkedHashMap<>();
        if (!userIds.isEmpty()) {
            inviteRepository.findByUserIdInOrderByCreatedAtDesc(userIds).forEach(i -> latest.putIfAbsent(i.getUserId(), i));
        }
        return latest;
    }

    static OpsAdminStatus status(User user, OpsInvite latest, Instant now) {
        if (!user.isActive()) {
            return OpsAdminStatus.SUSPENDED;
        }
        if (user.getOpsDeactivatedAt() != null) {
            return OpsAdminStatus.DEACTIVATED;
        }
        if (user.getPasswordHash() != null) {
            return OpsAdminStatus.ACTIVE;
        }
        if (latest == null || latest.getRevokedAt() != null) {
            return OpsAdminStatus.INVITE_REVOKED;
        }
        return latest.getExpiresAt().isAfter(now) ? OpsAdminStatus.INVITED : OpsAdminStatus.INVITE_EXPIRED;
    }

    private static OpsAdminView view(User u, OpsInvite latest, Instant now) {
        OpsRole role = OpsRole.effective(u.getOpsRole());
        OpsAdminStatus status = status(u, latest, now);
        boolean invitePending = status == OpsAdminStatus.INVITED || status == OpsAdminStatus.INVITE_EXPIRED;
        return new OpsAdminView(u.getId(), u.getFirstName(), u.getLastName(), Names.join(u.getFirstName(), u.getLastName()),
                u.getEmail(), u.getPhone(), role, role.label(), role.permissions(), status, u.getLastSeenAt(), u.getInvitedAt(),
                u.getInvitedBy(), u.getInvitedByName(), invitePending && latest != null ? latest.getExpiresAt() : null,
                u.getOpsDeactivatedAt(), u.getOpsDeactivationReason(), u.getCreatedAt());
    }

    private static String display(User user) {
        String name = Names.join(user.getFirstName(), user.getLastName());
        return name == null || name.isBlank() ? (user.getEmail() != null ? user.getEmail() : "This admin") : name;
    }

    private static String name(User actor, String given) {
        if (given != null && !given.isBlank()) {
            return given.trim();
        }
        return Names.join(actor.getFirstName(), actor.getLastName());
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(message);
        }
        return reason.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

}
