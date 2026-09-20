package org.coryd.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * The pool every {@code @Async} dispatch in {@code NotificationDispatchService} runs on.
 *
 * <p>Previously these ran on Spring Boot's default {@code applicationTaskExecutor}: eight threads
 * behind an <em>unbounded</em> queue. Combined with outbound calls that had no timeout, eight hung
 * Termii or SMTP calls were enough to park every thread permanently, after which every subsequent
 * notification accumulated in heap and was never sent - while the Kafka listener kept returning
 * immediately and kept committing offsets, so the service looked perfectly healthy and the backlog
 * was silently lost on the next restart.
 *
 * <p>Bounded queue plus CallerRunsPolicy fixes the second half of that: once the pool and its
 * queue are full, dispatch runs inline on the Kafka listener thread, which stops that listener
 * pulling more records until it catches up. Backpressure reaches the broker, where the records are
 * durable, instead of piling up in memory where they are not. That is only a safe trade because
 * every outbound call now has a timeout (see {@code HttpTimeouts} and the mail properties in
 * application.yml) - an inline send is bounded at ~10s, far inside the 300s max.poll.interval,
 * so backpressure can slow a listener without ever tripping a rebalance.
 *
 * <p>{@code @EnableAsync} lives on NotificationServiceApplication. This executor is not the single
 * TaskExecutor bean in the context, so callers must name it explicitly:
 * {@code @Async("notificationExecutor")}.
 */
@Configuration
public class NotificationAsyncConfig {

    @Bean("notificationExecutor")
    public ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(24);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notification-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Let in-flight sends finish on shutdown rather than dropping a queued OTP mid-deploy.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
