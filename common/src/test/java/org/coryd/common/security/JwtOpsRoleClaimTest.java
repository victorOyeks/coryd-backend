package org.coryd.common.security;

import org.coryd.common.enums.OpsRole;
import org.coryd.common.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtOpsRoleClaimTest {

    private final JwtUtil jwt = build();

    private static JwtUtil build() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "test-secret-test-secret-test-secret-test-secret-0123456789");
        ReflectionTestUtils.setField(jwt, "expirationMs", 60_000L);
        jwt.buildParser();
        return jwt;
    }

    @Test
    void anOpsAdminSessionCarriesItsOpsRoleAndOtherSessionsNever() {
        CoRydUserDetails admin = jwt.extractUserDetails(jwt.generateToken("a-1", null, "Ada", "Ops", UserRole.OPS_ADMIN, OpsRole.FINANCE));
        assertThat(admin.getOpsRole()).isEqualTo("FINANCE");
        assertThat(admin.getActiveRole()).isEqualTo(UserRole.OPS_ADMIN);

        CoRydUserDetails rider = jwt.extractUserDetails(jwt.generateToken("r-1", "0801", "R", "R", UserRole.RIDER, OpsRole.SUPER_ADMIN));
        assertThat(rider.getOpsRole()).as("never on a rider token").isNull();

        CoRydUserDetails legacy = jwt.extractUserDetails(jwt.generateToken("a-2", null, "Old", "Ops", UserRole.OPS_ADMIN));
        assertThat(legacy.getOpsRole()).isNull();
        assertThat(OpsRole.effective(OpsRole.parse(legacy.getOpsRole()))).isEqualTo(OpsRole.SUPER_ADMIN);
    }
}
