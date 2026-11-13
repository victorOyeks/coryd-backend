package org.coryd.identity.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MfaSecretCipherTest {

    private static final String JWT = "test-secret-test-secret-test-secret-test-secret-0123456789";

    @Test
    void roundTripsWithAFreshIvAndNeverStoresTheSecretInClear() {
        MfaSecretCipher cipher = new MfaSecretCipher("", JWT);
        String a = cipher.encrypt("JBSWY3DPEHPK3PXP");
        String b = cipher.encrypt("JBSWY3DPEHPK3PXP");
        assertThat(a).startsWith("v1:").doesNotContain("JBSWY3DPEHPK3PXP").isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(cipher.decrypt(b)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void anotherKeyOrATamperedValueCantBeRead() {
        String sealed = new MfaSecretCipher("", JWT).encrypt("JBSWY3DPEHPK3PXP");
        assertThatThrownBy(() -> new MfaSecretCipher("a passphrase", JWT).decrypt(sealed)).hasMessage("Unreadable two-factor secret");
        String tampered = sealed.substring(0, sealed.length() - 4) + (sealed.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertThatThrownBy(() -> new MfaSecretCipher("", JWT).decrypt(tampered)).hasMessage("Unreadable two-factor secret");
    }

    @Test
    void theKeyIsTheConfiguredOneOrOneDerivedFromTheJwtSecretUnderItsOwnLabel() {
        byte[] raw = new byte[32];
        raw[0] = 7;
        assertThat(MfaSecretCipher.keyBytes(Base64.getEncoder().encodeToString(raw), JWT)).isEqualTo(raw);
        byte[] derived = MfaSecretCipher.keyBytes(null, JWT);
        assertThat(derived).hasSize(32).isNotEqualTo(JWT.getBytes(StandardCharsets.UTF_8));
        assertThat(MfaSecretCipher.keyBytes("passphrase", JWT)).hasSize(32).isNotEqualTo(derived);
    }
}
