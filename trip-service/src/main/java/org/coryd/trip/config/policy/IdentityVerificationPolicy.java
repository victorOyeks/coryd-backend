package org.coryd.trip.config.policy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Nobody rides or drives without a verified NIN: with STRICT (the default) a rider can't book and a
 * driver can't post until their NIN + selfie check is approved - by Smile ID or by ops. OFF only for
 * environments where nobody can be verified yet.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "identity.verification")
public class IdentityVerificationPolicy {

    public enum Enforcement {
        /** Only verified people may book or post. */
        STRICT,
        /** Never enforced. */
        OFF
    }

    private Enforcement enforcement = Enforcement.STRICT;

    public boolean blocksUnverified() {
        return enforcement == Enforcement.STRICT;
    }
}
