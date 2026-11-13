package org.coryd.common.security.revocation;

import org.coryd.common.kafka.events.UserTokensRevokedEvent;

import java.util.List;

/**
 * Where TokenRevocationBootstrap gets the full picture from: identity-service's internal endpoint for every other service
 * (IdentityTokenRevocationSource), identity-service's own database inside identity-service.
 */
public interface TokenRevocationSource {

    /** Every user with a cut-off or a suspension, as it stands now. Throws if the source can't be reached. */
    List<UserTokensRevokedEvent> fetchAll();
}
