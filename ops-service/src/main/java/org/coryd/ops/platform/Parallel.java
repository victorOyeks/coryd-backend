package org.coryd.ops.platform;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Fans calls out to several services at once while keeping the admin's request attached - the forwarded-token interceptor
 * reads the Authorization header from it, and without it every call would go out with a system token and be refused.
 */
final class Parallel {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "ops-fanout");
        t.setDaemon(true);
        return t;
    });

    private Parallel() {
    }

    static <T> CompletableFuture<T> call(Supplier<T> work) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        return CompletableFuture.supplyAsync(() -> {
            RequestContextHolder.setRequestAttributes(request);
            try {
                return work.get();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        }, POOL);
    }
}
