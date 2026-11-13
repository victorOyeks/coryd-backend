package org.coryd.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Ops changing a person's name or email. Fields left out (null) stay as they are; at least one must be sent. */
public record OpsDetailsEditRequest(@Size(max = 100) String firstName,
                                    @Size(max = 100) String lastName,
                                    @Email @Size(max = 254) String email) {
}
