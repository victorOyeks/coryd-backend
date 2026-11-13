package org.coryd.identity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecoveryCodeFormatTest {

    @Test
    void recoveryCodesAreAcceptedHoweverTheyAreTyped() {
        assertThat(OpsMfaService.normalizeRecoveryCode("abcd-ef23")).isEqualTo("abcd-ef23");
        assertThat(OpsMfaService.normalizeRecoveryCode("ABCDEF23")).isEqualTo("abcd-ef23");
        assertThat(OpsMfaService.normalizeRecoveryCode("abc")).isNull();
        assertThat(OpsMfaService.normalizeRecoveryCode("abcd-ef2!")).isNull();
    }
}
