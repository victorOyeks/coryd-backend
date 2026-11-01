package org.coryd.identity.dto;

/** suspendedAt / suspensionReason are set while ops has the account suspended; joinedAt is when they signed up; roles
 * are the roles the account holds (a driver can be a rider too). */
public record UserContactResponse(String userId, String fullName, String firstName, String lastName, String phone,
                                  java.time.Instant suspendedAt, String suspensionReason, java.time.Instant joinedAt,
                                  java.util.Set<org.coryd.common.enums.UserRole> roles) {

    public UserContactResponse(String userId, String fullName, String firstName, String lastName, String phone) {
        this(userId, fullName, firstName, lastName, phone, null, null, null, null);
    }
}
