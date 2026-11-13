package org.coryd.identity.dto.mfa;

import java.util.List;

/** Ten one-time recovery codes, xxxx-xxxx. Shown once - only their hashes are kept. */
public record RecoveryCodesView(List<String> recoveryCodes) {
}
