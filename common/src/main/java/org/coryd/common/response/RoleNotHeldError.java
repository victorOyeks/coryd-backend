package org.coryd.common.response;

import org.coryd.common.enums.UserRole;

import java.util.Set;

public record RoleNotHeldError(String code, Set<UserRole> heldRoles) {
}
