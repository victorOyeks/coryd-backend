package org.coryd.common.jobs;

import java.time.Instant;
import java.util.List;

/**
 * What a service can say about the outside providers it calls (Paystack, Smile ID, Termii, FCM, SMTP) - counted from the
 * records it already keeps, over the last {@code hours}.
 */
public interface IntegrationHealthSource {

    List<IntegrationHealth> integrations(Instant since);

    /** attempts may be null when the service only records failures. */
    record IntegrationHealth(String integration, String operation, Long attempts, long failures, Instant lastFailureAt,
                             String lastError) {
    }
}
