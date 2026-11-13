package org.coryd.identity.dto.ops;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.OpsRole;

/** POST /internal/ops-admins/{id}/role - reason optional. */
public record ChangeOpsRoleRequest(@NotNull(message = "Choose the new ops role") OpsRole opsRole, @Size(max = 500) String reason,
                                   @NotBlank String opsUserId, String opsUserName) {
}
