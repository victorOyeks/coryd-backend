package org.coryd.trip.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.trip.service.RouteTemplateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Hourly, so each regular route's trip appears as soon as its day comes inside the posting window. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteTemplateJob {

    private final RouteTemplateService routeTemplateService;

    @Scheduled(cron = "${trip.route-templates.cron:0 5 * * * *}", zone = "Africa/Lagos")
    public void postUpcomingTrips() {
        try {
            int posted = routeTemplateService.postUpcomingTrips();
            if (posted > 0) {
                log.info("Posted {} trip(s) from regular routes", posted);
            }
        } catch (Exception e) {
            log.error("Posting regular routes failed: {}", e.getMessage(), e);
        }
    }
}
