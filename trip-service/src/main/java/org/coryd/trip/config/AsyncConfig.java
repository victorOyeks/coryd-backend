package org.coryd.trip.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Backs @Async work that must never add latency to the request thread it's piggybacked onto -
 * route-deviation checking rides the driver's location-POST call (every 15-30s per active trip),
 * which has to stay fast regardless of how long a Maps API round-trip takes on the rare reroute
 * path. A bounded pool, not Spring's default unbounded thread-per-task executor - under heavy
 * concurrent trip volume, unbounded thread creation is a real resource-exhaustion risk, not a
 * style preference. CallerRunsPolicy as the rejection strategy: if the pool and its queue are
 * both saturated, the check runs synchronously on the calling thread instead of being silently
 * dropped or throwing - a rare, brief slowdown under extreme load is a safer failure mode than a
 * trip that quietly stops being checked for deviation at all.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("routeDeviationExecutor")
    public ThreadPoolTaskExecutor routeDeviationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("route-deviation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** Backs LiveSearchPushService - re-running every active live-search session's query must
     * never add latency to the booking/posting request that triggered it. */
    @Bean("liveSearchExecutor")
    public ThreadPoolTaskExecutor liveSearchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("live-search-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
