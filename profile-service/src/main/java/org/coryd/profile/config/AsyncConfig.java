package org.coryd.profile.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Identity lookups run off the request thread so a driver saving their profile never waits on
 * FRSC. The pool is small and the queue bounded on purpose: if checks back up that far the
 * provider is already in trouble, and shedding the overflow beats holding thousands of tasks that
 * would each time out anyway. A shed check just leaves the item pending for ops, which is where
 * an unchecked item belongs.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "verificationExecutor")
    public Executor verificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("verification-");
        executor.initialize();
        return executor;
    }
}
