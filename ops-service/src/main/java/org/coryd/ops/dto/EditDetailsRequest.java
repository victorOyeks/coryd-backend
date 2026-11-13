package org.coryd.ops.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Ops changing a rider's or driver's name or email. Send only what changes. reason is required and kept in the account
 * history. phone / phoneNumber are only here to be refused: a phone number can't be changed by ops.
 */
public record EditDetailsRequest(@Size(max = 100) String firstName,
                                 @Size(max = 100) String lastName,
                                 @Email @Size(max = 254) String email,
                                 String phone,
                                 String phoneNumber,
                                 @NotBlank(message = "is required") @Size(max = 500) String reason) {
}
