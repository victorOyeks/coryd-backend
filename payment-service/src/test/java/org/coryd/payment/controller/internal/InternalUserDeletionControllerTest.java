package org.coryd.payment.controller.internal;

import org.coryd.payment.service.EarningsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalUserDeletionControllerTest {

    private final EarningsService earnings = mock(EarningsService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InternalUserDeletionController controller = new InternalUserDeletionController(earnings, jdbc);

    @BeforeEach
    void setUp() {
        when(earnings.amountOwed("driver-1")).thenReturn(new BigDecimal("4750"));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("driver-1"))).thenReturn(0);
    }

    private List<String> blockers(InternalUserDeletionController.AllowOwing setting, boolean byOps) {
        ReflectionTestUtils.setField(controller, "allowOwing", setting);
        return controller.deletionBlockers("driver-1", byOps).data();
    }

    @Test
    void byDefaultNobodyCanDeleteAnAccountThatOwes() {
        assertThat(blockers(InternalUserDeletionController.AllowOwing.NEVER, true))
                .containsExactly("you owe CoRyd ₦4,750 - pay it back first");
    }

    @Test
    void opsCanDeleteAnAccountThatOwesButItsOwnerCant() {
        assertThat(blockers(InternalUserDeletionController.AllowOwing.OPS, true)).isEmpty();
        assertThat(blockers(InternalUserDeletionController.AllowOwing.OPS, false)).hasSize(1);
    }

    @Test
    void anyoneCanWhenItsAlwaysAllowed() {
        assertThat(blockers(InternalUserDeletionController.AllowOwing.ALWAYS, false)).isEmpty();
    }

    @Test
    void ridersPaymentsStillBeingSettledAlwaysBlock() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("driver-1"))).thenReturn(2);

        assertThat(blockers(InternalUserDeletionController.AllowOwing.ALWAYS, true))
                .singleElement().asString().contains("still being settled");
    }
}
