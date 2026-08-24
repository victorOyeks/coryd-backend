package org.coryd.common.exception;

import org.coryd.common.enums.UserRole;

import java.util.Set;

/** Thrown by login when the password is correct but the account doesn't hold the requested role - distinct from BadRequestException so a client can reliably offer "log in with your existing role and add this one" instead of matching on message text. */
public class RoleNotHeldException extends CoRydException {

    public static final String CODE = "ROLE_NOT_HELD";

    private final Set<UserRole> heldRoles;

    public RoleNotHeldException(String message, Set<UserRole> heldRoles) {
        super(message);
        this.heldRoles = heldRoles;
    }

    public Set<UserRole> getHeldRoles() {
        return heldRoles;
    }
}
