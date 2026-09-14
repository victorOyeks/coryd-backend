package org.coryd.notification.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.report.ReportPeriod;
import org.coryd.notification.service.ReportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ops reports, all at 06:00 in the operating timezone. Staggered by five minutes each so a
 * Monday the 1st fires three reports in sequence rather than three at once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "report.enabled", havingValue = "true", matchIfMissing = true)
public class ReportJob {

    private final ReportService reportService;

    @Scheduled(cron = "0 0 6 * * *", zone = "${report.timezone:Africa/Lagos}")
    public void daily() {
        run(ReportPeriod.DAILY);
    }

    @Scheduled(cron = "0 5 6 * * MON", zone = "${report.timezone:Africa/Lagos}")
    public void weekly() {
        run(ReportPeriod.WEEKLY);
    }

    @Scheduled(cron = "0 10 6 1 * *", zone = "${report.timezone:Africa/Lagos}")
    public void monthly() {
        run(ReportPeriod.MONTHLY);
    }

    private void run(ReportPeriod period) {
        try {
            reportService.sendReport(period);
        } catch (Exception e) {
            // A failed report must never take the service down or block the next one.
            log.error("{} report failed: {}", period, e.getMessage(), e);
        }
    }
}
