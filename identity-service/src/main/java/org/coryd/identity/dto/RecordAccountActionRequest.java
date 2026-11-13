package org.coryd.identity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.AccountActionType;

import java.util.List;

/**
 * An action ops-service carried out in another service (a name, email or vehicle edit in profile-service, tries given back),
 * recorded in the account history. Suspend, reinstate and sign out everywhere aren't accepted here - their own calls record them.
 */
public record RecordAccountActionRequest(@NotNull AccountActionType action,
                                         @Size(max = 500) String reason,
                                         @Valid List<Change> changes,
                                         @Size(max = 36) String opsUserId,
                                         @Size(max = 150) String opsUserName) {

    /** One field changed: its name and the values before and after, as text. */
    public record Change(@Size(max = 60) String field, @Size(max = 255) String before, @Size(max = 255) String after) {
    }
}
