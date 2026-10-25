package org.coryd.notification.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.service.NdpcThresholdService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Once a day, after the morning reports: has CoRyd neared or reached an NDPC registration tier? */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ndpc.enabled", havingValue = "true", matchIfMissing = true)
public class NdpcThresholdJob {

    private final NdpcThresholdService ndpcThresholdService;

    @Scheduled(cron = "${ndpc.cron:0 30 6 * * *}", zone = "${report.timezone:Africa/Lagos}")
    public void daily() {
        try {
            ndpcThresholdService.check();
        } catch (Exception e) {
            log.error("NDPC threshold check failed: {}", e.getMessage(), e);
        }
    }
}
