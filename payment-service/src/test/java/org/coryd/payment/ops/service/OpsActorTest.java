package org.coryd.payment.ops.service;

import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** payment-service's /internal paths aren't key-gated in production, so every money endpoint checks the role itself. */
class OpsActorTest {

    @Test
    void onlyAnOpsAdminTokenIsAnActor() {
        for (UserRole role : new UserRole[]{UserRole.RIDER, UserRole.DRIVER}) {
            assertThatThrownBy(() -> OpsActor.of(new CoRydUserDetails("u-1", "0800", "A", "B", role)))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> OpsActor.of(null)).isInstanceOf(AccessDeniedException.class);

        OpsActor actor = OpsActor.of(new CoRydUserDetails("ops-1", "0800", "Ada", "Obi", UserRole.OPS_ADMIN));
        assertThat(actor.id()).isEqualTo("ops-1");
        assertThat(actor.name()).isEqualTo("Ada Obi");
    }
}
