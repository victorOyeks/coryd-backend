package org.coryd.identity.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/** confirm must be true - the app asks "Delete your account? This can't be undone." first. */
public record DeleteAccountRequest(
        @NotNull @AssertTrue(message = "Confirm that you want to delete your account") Boolean confirm
) {
}
