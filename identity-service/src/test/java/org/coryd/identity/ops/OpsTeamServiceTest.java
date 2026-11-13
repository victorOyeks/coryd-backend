package org.coryd.identity.ops;

import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.BadRequestException;
import org.coryd.identity.dto.ops.ChangeOpsRoleRequest;
import org.coryd.identity.dto.ops.OpsTeamActionRequest;
import org.coryd.identity.entity.User;
import org.coryd.identity.repository.OpsInviteRepository;
import org.coryd.identity.repository.UserRepository;
import org.coryd.identity.service.AccountActionService;
import org.coryd.identity.service.AuthService;
import org.coryd.identity.service.OpsTeamService;
import org.coryd.identity.service.SessionRevocationService;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The last-super-admin guard. Through a consistent database it can't be reached by an active super admin acting on
 * someone else (that's already two) - it is what stops the second of two concurrent demotions (the rows are locked, the
 * count re-read) from leaving the console without a super admin.
 */
@SuppressWarnings("unchecked")
class OpsTeamServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final SessionRevocationService sessions = mock(SessionRevocationService.class);
    private final OpsTeamService service = new OpsTeamService(users, mock(OpsInviteRepository.class), sessions,
            mock(AccountActionService.class), mock(AuthService.class), mock(PasswordEncoder.class), mock(KafkaTemplate.class));

    private static User superAdmin(String id) {
        User u = User.builder().email(id + "@x").firstName("Super").lastName(id).passwordHash("hash").opsRole(OpsRole.SUPER_ADMIN)
                .roles(new HashSet<>(Set.of(UserRole.OPS_ADMIN))).build();
        u.setId(id);
        return u;
    }

    @Test
    void theLastActiveSuperAdminCantBeDemotedOrDeactivated() {
        User actor = superAdmin("a");
        User target = superAdmin("b");
        when(users.findById("a")).thenReturn(Optional.of(actor));
        when(users.findById("b")).thenReturn(Optional.of(target));
        // what the locked re-read sees once the other demotion committed: only the target is still a super admin
        when(users.findOpsAdmins()).thenReturn(List.of(target));

        assertThatThrownBy(() -> service.changeRole("b", new ChangeOpsRoleRequest(OpsRole.SUPPORT, null, "a", "A")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Super b is the last super admin - make someone else a super admin first");
        assertThatThrownBy(() -> service.deactivate("b", new OpsTeamActionRequest("Leaving", "a", "A")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Super b is the last super admin - make someone else a super admin first");
        verify(users, never()).save(any());
        verify(sessions, never()).endAllSessions(any(), any());
    }
}
